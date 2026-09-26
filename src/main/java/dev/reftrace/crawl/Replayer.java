package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.scan.Origin;
import dev.reftrace.browse.scan.PageScanner;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Judge;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Replayer {

    private static final Logger log = LoggerFactory.getLogger(Replayer.class);

    private static final Set<UntestedReason> PASSING = Set.of(UntestedReason.PAGE_LOAD_TIMEOUT,
            UntestedReason.NAVIGATION_ERROR, UntestedReason.BROWSER_CRASH, UntestedReason.DOM_CHANGED,
            UntestedReason.LINK_NOT_FOUND);

    private final Routes routes;
    private final PageScanner scanner;
    private final Judge judge;
    private final String keyParam;
    private final boolean screenshots;
    private final ObservationRegistry observations;

    Replayer(Routes routes, Judge judge, String keyParam, boolean qrCheck, boolean screenshots,
             ObservationRegistry observations) {
        this.routes = routes;
        this.scanner = new PageScanner(routes, qrCheck, observations);
        this.judge = judge;
        this.keyParam = keyParam;
        this.screenshots = screenshots;
        this.observations = observations;
    }

    sealed interface Replayed {

        record Scanned(PageScan scan, List<Check> checks, Map<Check, byte[]> screenshots) implements Replayed {

            public Scanned {
                checks = List.copyOf(checks);
                screenshots = Map.copyOf(screenshots);
            }
        }

        record Unreached(Untested untested, Map<Check, byte[]> screenshots) implements Replayed {

            public Unreached {
                screenshots = Map.copyOf(screenshots);
            }
        }

        record Crashed() implements Replayed {
        }

        default boolean worthAnotherAttempt() {
            return switch (this) {
                case Scanned _, Crashed _ -> false;
                case Unreached(Untested untested, _) -> passing(untested);
            };
        }
    }

    private static boolean passing(Untested untested) {
        return PASSING.contains(untested.reason())
                || (untested instanceof Untested.HttpResponse(int statusCode, _)
                        && HttpStatusCode.valueOf(statusCode).is5xxServerError());
    }

    boolean mayContinue(WalkTask task) {
        return switch (task.path().getLast()) {
            case Step.Click(FoundLink followed) ->
                    routes.routeFor(followed.url()).map(Route::onArrival).orElse(List.of()).isEmpty();
            case Step.Reload _, Step.Enter _ -> false;
        };
    }

    Replayed replay(PageDriver page, WalkTask task, boolean continuing, boolean lastAttempt) {
        Timing timing = new Timing();
        Replayed replayed = replayTimed(page, task, continuing, lastAttempt, timing);
        if (log.isDebugEnabled()) {
            log.debug("{} steps of {} to {} ({}): {}, scan {} ms, screenshots {} ms, {} ms in all, {}",
                    task.path().size(), task.profile().name(), page.currentUrl(),
                    continuing ? "one step on" : "from the start", timing.steps, timing.scan, timing.screenshots,
                    timing.total(), switch (replayed) {
                        case Replayed.Scanned _ -> "scanned";
                        case Replayed.Unreached(Untested untested, _) -> "unreached: " + untested.reason();
                        case Replayed.Crashed _ -> "crashed";
                    });
        }
        return replayed;
    }

    private Replayed replayTimed(PageDriver page, WalkTask task, boolean continuing, boolean lastAttempt,
                                 Timing timing) {
        List<Step> path = task.path();
        List<Step> steps = continuing ? path.subList(path.size() - 1, path.size()) : path;
        try {
            int offset = path.size() - steps.size();
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                PageLoad load;
                switch (take(page, step, offset + index == 0, offset + index < path.size() - 1)) {
                    case Navigation.Landed(PageLoad landed) -> load = landed;
                    case Navigation.NotFollowed(Untested untested) -> {
                        timing.step(step);
                        return unreached(page, untested, lastAttempt, timing);
                    }
                }
                timing.step(step);
                HttpStatusCode status = load.httpStatus();
                if (status != null && status.isError()) {
                    if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                        log.warn("the site answered 429 Too Many Requests for {}", load.requestedUrl());
                    }
                    return unreached(page, new Untested.HttpResponse(status.value(), load.requestedUrl()), lastAttempt,
                            timing);
                }
            }
            PageScan scan = Observation.createNotStarted("scan", observations)
                    .observe(() -> scanner.scan(page, path.getLast() instanceof Step.Click(FoundLink followed)
                            ? new Origin.Followed(followed) : new Origin.Entered()));
            List<Check> checks = judge.judgePage(scan, Walker.expectedKey(path));
            timing.scan = timing.lap();
            Map<Check, byte[]> taken = screenshots(page, checks);
            timing.screenshots = timing.lap();
            return new Replayed.Scanned(scan, checks, taken);
        } catch (PageCheckException failed) {
            timing.steps.add("failed " + timing.lap() + " ms");
            Untested untested = new Untested.Issue(failed.error().kind(), null, failed.error().message());
            return unreached(page, untested, lastAttempt, timing);
        }
    }

    private Navigation take(PageDriver page, Step step, boolean first, boolean replay) {
        StepWord word = switch (step) {
            case Step.Enter _ -> first ? StepWord.ENTER : StepWord.ENTER_NEW_KEY;
            case Step.Click _ -> StepWord.CLICK;
            case Step.Reload _ -> StepWord.RELOAD;
        };
        Observation observation = Observation.createNotStarted("step." + word.word(), observations)
                .lowCardinalityKeyValue("reftrace.replay", String.valueOf(replay))
                .highCardinalityKeyValue("reftrace.url", switch (step) {
                    case Step.Enter enter -> enter.url().toString();
                    case Step.Click click -> click.link().url().toString();
                    case Step.Reload _ -> page.currentUrl().toString();
                });
        return observation.observe(() -> {
            Navigation navigation = switch (step) {
                case Step.Enter enter -> new Navigation.Landed(page.open(UriComponentsBuilder.fromUri(enter.url())
                        .replaceQueryParam(keyParam, enter.key().value()).build(true).toUri()));
                case Step.Click click -> page.follow(click.link());
                case Step.Reload _ -> new Navigation.Landed(page.reload());
            };
            switch (navigation) {
                case Navigation.Landed(PageLoad load) -> {
                    HttpStatusCode status = load.httpStatus();
                    if (status != null) {
                        observation.lowCardinalityKeyValue("reftrace.http.status", String.valueOf(status.value()));
                    }
                }
                case Navigation.NotFollowed(Untested untested) ->
                        observation.event(Observation.Event.of("not-followed", TraceEvents.untested(untested)));
            }
            return navigation;
        });
    }

    private Replayed.Unreached unreached(PageDriver page, Untested untested, boolean lastAttempt,
                                                Timing timing) {
        if (passing(untested) && !lastAttempt) {
            return new Replayed.Unreached(untested, Map.of());
        }
        Map<Check, byte[]> taken = screenshots(page, List.of(new Check.NotTested(untested)));
        timing.screenshots = timing.lap();
        return new Replayed.Unreached(untested, taken);
    }

    private static final class Timing {

        private final long startedAt = System.nanoTime();
        private final List<String> steps = new ArrayList<>();
        private long lapStartedAt = startedAt;
        private long scan;
        private long screenshots;

        void step(Step step) {
            String word = switch (step) {
                case Step.Enter _ -> "enter";
                case Step.Click _ -> "click";
                case Step.Reload _ -> "reload";
            };
            steps.add(word + " " + lap() + " ms");
        }

        long lap() {
            long now = System.nanoTime();
            long took = (now - lapStartedAt) / 1_000_000;
            lapStartedAt = now;
            return took;
        }

        long total() {
            return (System.nanoTime() - startedAt) / 1_000_000;
        }
    }

    private Map<Check, byte[]> screenshots(PageDriver page, List<Check> checks) {
        Map<Check, byte[]> taken = new HashMap<>();
        if (!screenshots) {
            return taken;
        }
        for (Check check : checks) {
            byte @Nullable [] png = switch (check) {
                case Check.Mismatch mismatch when mismatch.isOnArrival() -> page.screenshot(null);
                case Check.Mismatch(FoundLink link, _, _, _) ->
                        page.screenshot(link.visible() ? link.selector() : null);
                case Check.NotTested(Untested untested) -> switch (untested.reason()) {
                    case BROWSER_CRASH, INTERNAL -> null;
                    case QR_HIDDEN -> page.screenshot(null);
                    default -> page.screenshot(untested.selector());
                };
                case Check.Pass _, Check.Failed _ -> null;
            };
            if (png != null) {
                taken.put(check, png);
            }
        }
        return taken;
    }
}
