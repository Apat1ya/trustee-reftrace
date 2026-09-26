package dev.reftrace.crawl;

import dev.reftrace.browse.BrowserWorkerFactory;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.Untested;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Routes;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Judge;
import dev.reftrace.judge.Outcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class WalkWorkers {

    private static final Logger log = LoggerFactory.getLogger(WalkWorkers.class);

    private final BrowserWorkerFactory browsers;
    private final Judge judge;
    private final Routes routes;
    private final String keyParam;
    private final boolean qrCheck;
    private final Settings settings;
    private final Clock clock;
    private final ObservationRegistry observations;

    public WalkWorkers(BrowserWorkerFactory browsers, Judge judge, Routes routes, String keyParam, boolean qrCheck,
                       Settings settings, Clock clock, ObservationRegistry observations) {
        this.browsers = browsers;
        this.judge = judge;
        this.routes = routes;
        this.keyParam = keyParam;
        this.qrCheck = qrCheck;
        this.settings = settings;
        this.clock = clock;
        this.observations = observations;
    }

    public record Settings(int contexts, ReftraceProperties.Limits.Visit visit, ReftraceProperties.Limits.Run run,
                           Duration progressEvery, boolean screenshots, boolean traces) {

        public Settings {
            if (contexts < 1) {
                throw new IllegalArgumentException("a walk needs at least one browser context: " + contexts);
            }
        }

        public static Settings of(ReftraceProperties properties) {
            return new Settings(properties.parallelBrowsers(), properties.limits().visit(), properties.limits().run(),
                    Duration.ofSeconds(30), properties.report().screenshots(), properties.report().traces());
        }
    }

    public record Walked(List<PageVisit> visits, List<Walker.Counters> counters, int abandoned) {

        public Walked {
            visits = List.copyOf(visits);
            counters = List.copyOf(counters);
        }
    }

    public Walked walk(List<Walker.Walk> walks, String runId, Path runDirectory, Consumer<PageVisit> visited) {
        return new Collector(walks, runId,
                new Replayer(routes, judge, keyParam, qrCheck, settings.screenshots(), observations),
                new Screenshots(runDirectory), settings.traces() ? new Traces(runDirectory) : null, visited).run();
    }

    private Done visit(Lane lane, Pulled pulled, String runId) {
        WalkTask task = pulled.task();
        Observation visit = Observation.createNotStarted("visit", observations)
                .lowCardinalityKeyValue("reftrace.profile", task.profile().name())
                .lowCardinalityKeyValue("reftrace.scenarios", String.join(",", task.node().scenarios()))
                .lowCardinalityKeyValue("reftrace.depth", String.valueOf(task.depth()))
                .lowCardinalityKeyValue("reftrace.lane", String.valueOf(lane.index))
                .highCardinalityKeyValue("reftrace.run.id", runId)
                .highCardinalityKeyValue("reftrace.page.url", Traces.headedFor(task.path()).toString())
                .highCardinalityKeyValue("reftrace.path", String.join(" > ", Step.spelled(task.path(), keyParam)))
                .start();
        TraceNumbers.tag(visit, "reftrace.queue.wait", Duration.ofNanos(System.nanoTime() - pulled.queuedAt()).toMillis());
        try (Observation.Scope _ = visit.openScope()) {
            Replayer.Replayed replayed = lane.visit(task);
            describe(visit, replayed);
            return new Done(lane, pulled, replayed, visit);
        } catch (RuntimeException | Error e) {
            visit.error(e);
            visit.stop();
            throw e;
        }
    }

    private static void describe(Observation visit, Replayer.Replayed replayed) {
        List<Check> checks = switch (replayed) {
            case Replayer.Replayed.Scanned(PageScan scan, List<Check> judged, _) -> {
                visit.highCardinalityKeyValue("reftrace.landed.url", scan.landedUrl().toString());
                yield judged;
            }
            case Replayer.Replayed.Unreached(Untested untested, _) -> List.of(new Check.NotTested(untested));
            case Replayer.Replayed.Crashed _ -> List.of(new Check.Failed());
        };
        Map<Outcome, Long> counted = checks.stream().collect(Collectors.groupingBy(Check::outcome,
                () -> new EnumMap<>(Outcome.class), Collectors.counting()));
        for (Outcome outcome : Outcome.values()) {
            visit.highCardinalityKeyValue("reftrace.checks." + outcome.name().toLowerCase(Locale.ROOT),
                    String.valueOf(counted.getOrDefault(outcome, 0L)));
        }
        visit.lowCardinalityKeyValue("reftrace.outcome", Outcome.worst(counted.keySet())
                .map(outcome -> outcome.name().toLowerCase(Locale.ROOT))
                .orElse("nothing-to-check"));
        List<Observation.Event> events = checks.stream().map(TraceEvents::of).filter(Objects::nonNull).toList();
        events.forEach(visit::event);
        if (counted.containsKey(Outcome.FAILED)) {
            visit.error(new TraceEvents.MonitorFailure(events.stream()
                    .filter(event -> event.getName().equals("failed"))
                    .map(Observation.Event::getContextualName)
                    .collect(Collectors.joining("; "))));
        }
    }

    private final class Collector {

        private final List<Walker.Walk> walks;
        private final String runId;
        private final Replayer replayer;
        private final List<Lane> lanes;
        private final List<CompletionService<Done>> services;
        private final BlockingQueue<Future<Done>> finished = new LinkedBlockingQueue<>();
        private final List<PageVisit> visits = new ArrayList<>();
        private final Screenshots screenshots;
        private final Consumer<PageVisit> visited;
        private @Nullable Pulled held;
        private int cursor;
        private int inFlight;
        private long mismatches;
        private final Set<URI> pages = new HashSet<>();
        private int opened;
        private int beyondPages;
        private int beyondVisits;

        private Collector(List<Walker.Walk> walks, String runId, Replayer replayer, Screenshots screenshots,
                          @Nullable Traces traces, Consumer<PageVisit> visited) {
            this.walks = List.copyOf(walks);
            this.runId = runId;
            this.replayer = replayer;
            this.screenshots = screenshots;
            this.visited = visited;
            this.lanes = IntStream.range(0, settings.contexts())
                    .mapToObj(index -> new Lane(index, browsers, replayer, settings.visit(), traces, observations))
                    .toList();
            this.services = lanes.stream()
                    .<CompletionService<Done>>map(lane -> new ExecutorCompletionService<>(lane.thread, finished))
                    .toList();
        }

        Walked run() {
            Duration time = settings.run().time();
            Instant deadline = time.isZero() ? Instant.MAX : clock.instant().plus(time);
            Instant progressAt = clock.instant().plus(settings.progressEvery());
            int abandoned = 0;
            try {
                while (true) {
                    dispatch();
                    if (inFlight == 0 && fetch() == null) {
                        break;
                    }
                    startIdle();
                    Instant now = clock.instant();
                    if (!now.isBefore(deadline)) {
                        abandoned = abort("the run time of " + settings.run().time() + " is over");
                        break;
                    }
                    if (!now.isBefore(progressAt)) {
                        log.info("walked {} visits, {} queued, {} in flight, {} mismatches", visits.size(),
                                queued(), inFlight, mismatches);
                        progressAt = now.plus(settings.progressEvery());
                    }
                    Instant wake = progressAt.isBefore(deadline) ? progressAt : deadline;
                    Future<Done> done = finished.poll(Duration.between(now, wake).toMillis() + 1,
                            TimeUnit.MILLISECONDS);
                    if (done != null) {
                        collect(done.get());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                abandoned = abort("the run was interrupted");
            } catch (ExecutionException e) {
                throw new IllegalStateException("a lane lost its visit", e);
            } finally {
                shutdown(abandoned > 0);
            }
            logLimits();
            return new Walked(visits, walks.stream().map(Walker.Walk::counters).toList(), abandoned);
        }

        private void logLimits() {
            int beyondDepth = walks.stream().mapToInt(Walker.Walk::beyondDepth).sum();
            if (beyondDepth > 0) {
                log.info("limits.run.depth of {} left out {} clicks past it", settings.run().depth(), beyondDepth);
            }
            if (beyondPages > 0) {
                log.info("limits.run.pages of {} left out {} visits to other pages", settings.run().pages(),
                        beyondPages);
            }
            if (beyondVisits > 0) {
                log.info("limits.run.visits of {} left out {} visits", settings.run().visits(), beyondVisits);
            }
        }

        private void dispatch() {
            while (true) {
                Pulled next = fetch();
                if (next == null) {
                    return;
                }
                URI page = address(next.task());
                if (leftOut(page)) {
                    held = null;
                    continue;
                }
                if (inFlight == lanes.size()) {
                    return;
                }
                held = null;
                pages.add(page);
                opened++;
                Lane lane = pick(next.task());
                lane.busy = true;
                endIdle(lane, "task");
                inFlight++;
                services.get(lanes.indexOf(lane)).submit(() -> visit(lane, next, runId));
            }
        }

        private boolean leftOut(URI page) {
            int visitLimit = settings.run().visits();
            if (visitLimit > 0 && opened >= visitLimit) {
                if (beyondVisits++ == 0) {
                    log.info("limits.run.visits of {} is reached: no further page is visited", visitLimit);
                }
                return true;
            }
            int pageLimit = settings.run().pages();
            if (pageLimit > 0 && pages.size() >= pageLimit && !pages.contains(page)) {
                if (beyondPages++ == 0) {
                    log.info("limits.run.pages of {} is reached: only pages already opened are visited",
                            pageLimit);
                }
                return true;
            }
            return false;
        }

        private URI address(WalkTask task) {
            return Landing.of(task.path(), false, Walker.target(task), keyParam).address();
        }

        private Lane pick(WalkTask task) {
            List<Lane> idle = lanes.stream().filter(lane -> !lane.busy).toList();
            List<Step> parent = task.path().subList(0, task.path().size() - 1);
            return idle.stream()
                    .filter(lane -> replayer.mayContinue(task) && lane.shown != null
                            && lane.shown.profile().equals(task.profile()) && lane.shown.path().equals(parent))
                    .findFirst()
                    .or(() -> idle.stream().filter(lane -> lane.shown == null).findFirst())
                    .orElseGet(idle::getFirst);
        }

        private void startIdle() {
            for (Lane lane : lanes) {
                if (!lane.busy && lane.idle == null) {
                    lane.idle = Observation.createNotStarted("lane.idle", observations)
                            .lowCardinalityKeyValue("reftrace.lane", String.valueOf(lane.index))
                            .highCardinalityKeyValue("reftrace.run.id", runId)
                            .start();
                }
            }
        }

        private void endIdle(Lane lane, String why) {
            Observation idle = lane.idle;
            if (idle != null) {
                idle.lowCardinalityKeyValue("reftrace.idle.ended", why).stop();
                lane.idle = null;
            }
        }

        private @Nullable Pulled fetch() {
            if (held == null) {
                for (int offset = 0; offset < walks.size() && held == null; offset++) {
                    int index = (cursor + offset) % walks.size();
                    walks.get(index).take().ifPresent(queued -> held = new Pulled(index, queued.task(),
                            queued.queuedAt()));
                    if (held != null) {
                        cursor = index + 1;
                    }
                }
            }
            return held;
        }

        private void collect(Done done) {
            done.lane().busy = false;
            inFlight--;
            WalkTask task = done.pulled().task();
            Walker.Walk walk = walks.get(done.pulled().walk());
            done.lane().shown = done.replayed() instanceof Replayer.Replayed.Scanned ? task : null;
            Landing landing;
            List<Check> checks;
            Map<Check, byte[]> pictures;
            boolean repeat = false;
            switch (done.replayed()) {
                case Replayer.Replayed.Scanned(PageScan scan, List<Check> judged, var taken) -> {
                    Walker.Visited visited = walk.visited(task, scan);
                    landing = visited.landing();
                    repeat = visited.repeat();
                    checks = judged;
                    pictures = taken;
                }
                case Replayer.Replayed.Unreached(Untested untested, var taken) -> {
                    landing = walk.failed(task);
                    checks = List.of(new Check.NotTested(untested));
                    pictures = taken;
                }
                case Replayer.Replayed.Crashed _ -> {
                    landing = walk.failed(task);
                    checks = List.of(new Check.Failed());
                    pictures = Map.of();
                }
            }
            done.visit().lowCardinalityKeyValue("reftrace.repeat", String.valueOf(repeat)).stop();
            pages.add(landing.address());
            mismatches += checks.stream().filter(check -> check.outcome() == Outcome.MISMATCH).count();
            logUntested(task, landing, checks);
            PageVisit visit = new PageVisit(task.profile(), task.node().scenarios(), task.path(), landing, checks,
                    saved(task, landing, checks, pictures));
            visits.add(visit);
            visited.accept(visit);
        }

        private void logUntested(WalkTask task, Landing landing, List<Check> checks) {
            for (Check check : checks) {
                if (check instanceof Check.NotTested(Untested untested)) {
                    log.warn("untested {}{}{} on {} ({}, {}) via {}: {}", untested.reason(),
                            untested instanceof Untested.HttpResponse(int statusCode, _) ? " " + statusCode : "",
                            untested.selector() == null ? "" : " at " + untested.selector(), landing.address(),
                            task.profile().name(), task.node().scenarios(), Step.spelled(task.path(), keyParam),
                            untested.detail());
                }
            }
        }

        private Map<Check, Path> saved(WalkTask task, Landing landing, List<Check> checks,
                                       Map<Check, byte[]> pictures) {
            Map<Check, Path> files = new HashMap<>();
            for (Check check : checks) {
                byte @Nullable [] png = pictures.get(check);
                @Nullable Path file = png == null ? null : screenshots.save(task.profile(), landing.address(), check, png);
                if (file != null) {
                    files.put(check, file);
                }
            }
            return files;
        }

        private int queued() {
            return walks.stream().mapToInt(Walker.Walk::queued).sum() + (held == null ? 0 : 1);
        }

        private int abort(String why) {
            int abandoned = queued() + inFlight;
            log.warn("{}: stopping with {} visits done and {} left undone", why, visits.size(), abandoned);
            lanes.forEach(lane -> {
                lane.kill();
                lane.thread.shutdownNow();
            });
            return Math.max(abandoned, 1);
        }

        private void shutdown(boolean aborted) {
            lanes.forEach(lane -> endIdle(lane, "run-end"));
            for (Lane lane : lanes) {
                if (!aborted) {
                    lane.thread.execute(lane::close);
                }
                lane.thread.shutdown();
            }
            for (Lane lane : lanes) {
                try {
                    Duration deadline = settings.visit().deadline();
                    if (!lane.thread.awaitTermination(deadline.isZero() ? Long.MAX_VALUE : deadline.toMillis(),
                            TimeUnit.MILLISECONDS)) {
                        log.warn("a browser lane did not stop within {}", settings.visit().deadline());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            for (Future<Done> left = finished.poll(); left != null; left = finished.poll()) {
                if (left.state() == Future.State.SUCCESS) {
                    left.resultNow().visit().stop();
                }
            }
        }
    }

    private record Pulled(int walk, WalkTask task, long queuedAt) {
    }

    private record Done(Lane lane, Pulled pulled, Replayer.Replayed replayed, Observation visit) {
    }
}
