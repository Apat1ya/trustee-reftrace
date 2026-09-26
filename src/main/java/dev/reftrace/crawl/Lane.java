package dev.reftrace.crawl;

import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.BrowserStartException;
import dev.reftrace.browse.BrowserWorker;
import dev.reftrace.browse.BrowserWorkerFactory;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Outcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryOperations;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class Lane {

    private static final Logger log = LoggerFactory.getLogger(Lane.class);

    final ExecutorService thread;

    final int index;

    private final BrowserWorkerFactory browsers;
    private final Replayer replayer;
    private final RetryOperations attempts;
    private final int maxAttempts;
    private final Duration visitDeadline;
    private final @Nullable Traces traces;
    private final ObservationRegistry observations;

    private volatile @Nullable BrowserWorker worker;
    private volatile boolean killed;
    private @Nullable BrowserSession session;
    private @Nullable DeviceProfile profile;
    private @Nullable List<Step> at;

    @Nullable WalkTask shown;
    boolean busy;
    @Nullable Observation idle;

    Lane(int index, BrowserWorkerFactory browsers, Replayer replayer, ReftraceProperties.Limits.Visit limits,
         @Nullable Traces traces, ObservationRegistry observations) {
        this.thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("walk-" + index).factory());
        this.index = index;
        this.browsers = browsers;
        this.replayer = replayer;
        this.attempts = new RetryTemplate(policy(limits));
        this.maxAttempts = limits.maxAttempts();
        this.visitDeadline = limits.deadline();
        this.traces = traces;
        this.observations = observations;
    }

    private static RetryPolicy policy(ReftraceProperties.Limits.Visit limits) {
        ExponentialBackOff schedule = new ExponentialBackOff(limits.backoff().toMillis(), limits.backoffMultiplier());
        schedule.setMaxInterval(Long.MAX_VALUE);
        schedule.setMaxAttempts(limits.maxAttempts() - 1L);
        return RetryPolicy.builder().backOff(schedule).includes(WorthAnotherAttempt.class).build();
    }

    Replayer.Replayed visit(WalkTask task) {
        AtomicInteger made = new AtomicInteger();
        try {
            return Objects.requireNonNull(attempts.execute(() -> {
                int number = made.incrementAndGet();
                Replayer.Replayed replayed = attempt(task, number, number == maxAttempts);
                if (replayed.worthAnotherAttempt()) {
                    throw new WorthAnotherAttempt(replayed);
                }
                return replayed;
            }));
        } catch (RetryException outOfAttempts) {
            if (outOfAttempts.getLastException() instanceof WorthAnotherAttempt worth) {
                return lastOf(task, worth.replayed);
            }
            log.warn("replaying {} broke down", task.path(), outOfAttempts.getLastException());
            return new Replayer.Replayed.Crashed();
        }
    }

    private Replayer.Replayed lastOf(WalkTask task, Replayer.Replayed replayed) {
        if (replayed instanceof Replayer.Replayed.Unreached(Untested untested, _)
                && untested.reason() == UntestedReason.BROWSER_CRASH) {
            log.warn("replaying {} crashed the browser on the last of {} attempts: {}", task.path(), maxAttempts,
                    untested.detail());
            return new Replayer.Replayed.Crashed();
        }
        return replayed;
    }

    private Replayer.Replayed attempt(WalkTask task, int number, boolean last) {
        Observation observation = Observation.createNotStarted("attempt", observations)
                .lowCardinalityKeyValue("reftrace.attempt", String.valueOf(number));
        return observation.observe(() -> {
            Replayer.Replayed replayed = attemptObserved(task, number == 1, last, observation);
            boolean retried = !last && replayed.worthAnotherAttempt();
            observation.lowCardinalityKeyValue("reftrace.retried", String.valueOf(retried));
            if (retried && replayed instanceof Replayer.Replayed.Unreached(Untested untested, _)) {
                observation.lowCardinalityKeyValue("reftrace.retry.reason", untested.reason().name()
                        + (untested instanceof Untested.HttpResponse(int statusCode, _) ? " " + statusCode : ""));
            }
            return replayed;
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private Replayer.Replayed attemptObserved(WalkTask task, boolean first, boolean last, Observation observation) {
        CompletableFuture<Void> deadline = new CompletableFuture<>();
        if (!visitDeadline.isZero()) {
            deadline.orTimeout(visitDeadline.toMillis(), TimeUnit.MILLISECONDS);
        }
        deadline.whenComplete((_, error) -> {
            if (error instanceof TimeoutException) {
                kill();
            }
        });
        try {
            boolean continuing = first && continues(task);
            observation.lowCardinalityKeyValue("reftrace.continuing", String.valueOf(continuing));
            BrowserSession tab = continuing ? Objects.requireNonNull(session) : fresh(task.profile());
            Replayer.Replayed replayed = replayer.replay(tab.page(), task, continuing, last);
            endTrace(tab, task, replayed, last);
            if (replayed instanceof Replayer.Replayed.Scanned) {
                at = task.path();
            } else {
                closeSession();
            }
            if (replayed instanceof Replayer.Replayed.Unreached(Untested untested, _)
                    && untested.reason() == UntestedReason.BROWSER_CRASH) {
                dropWorker();
            }
            return replayed;
        } catch (BrowserStartException e) {
            log.warn("replaying {} found no browser: {}", task.path(), e.getMessage(), e);
            observation.error(e);
            dropWorker();
            return new Replayer.Replayed.Crashed();
        } catch (RuntimeException e) {
            log.warn("replaying {} broke down", task.path(), e);
            observation.error(e);
            Replayer.Replayed crashed = new Replayer.Replayed.Crashed();
            BrowserSession broken = session;
            if (broken != null) {
                endTrace(broken, task, crashed, true);
            }
            closeSession();
            return crashed;
        } finally {
            deadline.complete(null);
        }
    }

    private void endTrace(BrowserSession tab, WalkTask task, Replayer.Replayed replayed, boolean last) {
        Traces traces = this.traces;
        if (traces == null) {
            return;
        }
        boolean keep = failed(replayed) && (last || !replayed.worthAnotherAttempt());
        @Nullable Path file = keep ? traces.fileFor(task) : null;
        boolean written = Observation.createNotStarted(file == null ? "trace.discard" : "trace.save", observations)
                .observe(() -> tab.endTrace(file));
        if (written) {
            log.warn("kept the browser trace of the failed visit to {} ({}, {}) in {}",
                    Traces.headedFor(task.path()), task.profile().name(), task.node().scenarios(), file);
        }
    }

    private static boolean failed(Replayer.Replayed replayed) {
        return switch (replayed) {
            case Replayer.Replayed.Scanned(_, List<Check> checks, _) ->
                    checks.stream().anyMatch(check -> check.outcome() == Outcome.FAILED);
            case Replayer.Replayed.Unreached(Untested untested, _) -> !untested.reason().bySite();
            case Replayer.Replayed.Crashed _ -> true;
        };
    }

    private boolean continues(WalkTask task) {
        List<Step> path = task.path();
        return session != null && task.profile().equals(profile) && path.size() > 1
                && path.subList(0, path.size() - 1).equals(at) && replayer.mayContinue(task);
    }

    private BrowserSession fresh(DeviceProfile wanted) {
        closeSession();
        if (killed) {
            dropWorker();
        }
        BrowserWorker current = worker;
        if (current == null) {
            current = Observation.createNotStarted("playwright.start", observations).observe(browsers::create);
            worker = current;
            killed = false;
        }
        BrowserWorker started = current;
        BrowserSession opened = Observation.createNotStarted("context.open", observations)
                .lowCardinalityKeyValue("reftrace.profile", wanted.name())
                .observe(() -> started.openSession(wanted));
        session = opened;
        profile = wanted;
        return opened;
    }

    void kill() {
        killed = true;
        BrowserWorker current = worker;
        if (current != null) {
            current.forceKill();
        }
    }

    void close() {
        dropSession();
        dropWorker();
    }

    private void closeSession() {
        if (session != null) {
            Observation.createNotStarted("context.close", observations).observe(this::dropSession);
        } else {
            dropSession();
        }
    }

    private void dropSession() {
        BrowserSession current = session;
        session = null;
        profile = null;
        at = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                log.debug("a browser context did not close cleanly: {}", e.toString());
            }
        }
    }

    private void dropWorker() {
        dropSession();
        BrowserWorker current = worker;
        worker = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                log.debug("a browser did not close cleanly: {}", e.toString());
            }
        }
    }

    private static final class WorthAnotherAttempt extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Replayer.Replayed replayed;

        private WorthAnotherAttempt(Replayer.Replayed replayed) {
            super(null, null, false, false);
            this.replayed = replayed;
        }
    }
}
