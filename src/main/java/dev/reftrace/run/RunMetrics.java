package dev.reftrace.run;

import dev.reftrace.browse.UntestedReason;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Outcome;
import dev.reftrace.report.Report;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class RunMetrics {

    private static final Logger log = LoggerFactory.getLogger(RunMetrics.class);
    private static final String SECONDS = "seconds";
    private static final String NOTHING_TO_CHECK = "nothing_to_check";

    private enum Status {
        OK,
        ISSUES_FOUND,
        NO_REPORT
    }

    private record Last(Instant finishedAt, Status status, @Nullable Results results) {
    }

    private record Results(Duration duration, long pages, long visits, long linksChecked,
                           Map<String, Report.Checks> checks, Map<UntestedReason, Long> untested) {

        long count(String profile, Outcome outcome) {
            Report.@Nullable Checks counted = checks.get(profile);
            if (counted == null) {
                return 0;
            }
            return switch (outcome) {
                case PASS -> counted.pass();
                case MISMATCH -> counted.mismatch();
                case UNTESTED -> counted.untested();
                case FAILED -> counted.failed();
            };
        }
    }

    private final MeterRegistry registry;
    private final List<String> profiles;
    private final AtomicReference<@Nullable Instant> started = new AtomicReference<>();
    private final AtomicReference<@Nullable Last> last = new AtomicReference<>();

    RunMetrics(MeterRegistry registry, List<String> profiles) {
        this.registry = registry;
        this.profiles = List.copyOf(profiles);
        guarded("register the visit counters", () -> {
            for (String profile : this.profiles) {
                for (String outcome : visitOutcomes()) {
                    visits(outcome, profile);
                }
            }
        });
    }

    void started(Instant at) {
        guarded("record the start of a run", () -> {
            started.set(at);
            gauge("reftrace.run.last.start.timestamp", "When the last run started", started,
                    value -> seconds(value.get()), SECONDS);
        });
    }

    void visited(PageVisit visit) {
        guarded("count a visit", () -> visits(visitOutcome(visit), visit.profile().name()).increment());
    }

    void finished(Instant startedAt, Instant finishedAt, Report.Coverage coverage, List<PageVisit> visits) {
        guarded("record the end of a run", () -> {
            Results results = new Results(Duration.between(startedAt, finishedAt), coverage.pages().unique(),
                    coverage.pages().visits(), coverage.links().checked(), checks(visits), untested(visits));
            Status status = coverage.checks().mismatch() > 0 ? Status.ISSUES_FOUND : Status.OK;
            last.set(new Last(finishedAt, status, results));
            registerEnd();
            registerResults();
        });
    }

    void failed(Instant at) {
        guarded("record a run without a report", () -> {
            last.updateAndGet(before -> new Last(at, Status.NO_REPORT, before == null ? null : before.results()));
            registerEnd();
        });
    }

    private void registerEnd() {
        gauge("reftrace.run.last.finish.timestamp", "When the last run ended, with or without a report", last,
                value -> seconds(finishedAt(value.get())), SECONDS);
        for (Status status : Status.values()) {
            Gauge.builder("reftrace.run.last.status", last, value -> is(value.get(), status))
                    .description("How the last run ended: 1 for its status, 0 for the others")
                    .tag("status", label(status))
                    .register(registry);
        }
    }

    private void registerResults() {
        result("reftrace.run.last.duration", "How long the last run with a report took",
                results -> results.duration().toMillis() / 1000.0, SECONDS);
        result("reftrace.run.last.pages", "Distinct pages the last run with a report visited",
                Results::pages, null);
        result("reftrace.run.last.visits", "Page visits of the last run with a report", Results::visits, null);
        result("reftrace.run.last.links.checked", "Links the last run with a report checked",
                Results::linksChecked, null);
        for (String profile : profiles) {
            for (Outcome outcome : Outcome.values()) {
                Gauge.builder("reftrace.run.last.checks", last,
                                value -> resultOf(value.get(), results -> results.count(profile, outcome)))
                        .description("Checks of the last run with a report, by outcome and profile")
                        .tag("outcome", label(outcome))
                        .tag("profile", profile)
                        .register(registry);
            }
        }
        for (UntestedReason reason : UntestedReason.values()) {
            Gauge.builder("reftrace.run.last.untested", last,
                            value -> resultOf(value.get(), results -> results.untested().getOrDefault(reason, 0L)))
                    .description("Items the last run with a report could not test, by reason")
                    .tag("reason", label(reason))
                    .register(registry);
        }
    }

    private void result(String name, String description, ToDoubleFunction<Results> value, @Nullable String unit) {
        gauge(name, description, last, ref -> resultOf(ref.get(), value), unit);
    }

    private <T> void gauge(String name, String description, T state, ToDoubleFunction<T> value,
                           @Nullable String unit) {
        Gauge.builder(name, state, value).description(description).baseUnit(unit).register(registry);
    }

    private Counter visits(String outcome, String profile) {
        return Counter.builder("reftrace.visits")
                .description("Page visits as they complete, by what their checks came to and profile")
                .tag("outcome", outcome)
                .tag("profile", profile)
                .register(registry);
    }

    private static String visitOutcome(PageVisit visit) {
        return Outcome.worst(visit.checks().stream().map(Check::outcome).toList())
                .map(RunMetrics::label)
                .orElse(NOTHING_TO_CHECK);
    }

    private static List<String> visitOutcomes() {
        return Stream.concat(Arrays.stream(Outcome.values()).map(RunMetrics::label), Stream.of(NOTHING_TO_CHECK))
                .toList();
    }

    private Map<String, Report.Checks> checks(List<PageVisit> visits) {
        Map<String, List<Outcome>> byProfile = visits.stream().collect(Collectors.groupingBy(
                visit -> visit.profile().name(),
                Collectors.flatMapping(visit -> visit.checks().stream().map(Check::outcome), Collectors.toList())));
        return profiles.stream().collect(Collectors.toMap(profile -> profile,
                profile -> Report.Checks.count(byProfile.getOrDefault(profile, List.of()))));
    }

    private static Map<UntestedReason, Long> untested(List<PageVisit> visits) {
        Map<UntestedReason, Long> counts = new EnumMap<>(UntestedReason.class);
        for (PageVisit visit : visits) {
            for (Check check : visit.checks()) {
                switch (check) {
                    case Check.Pass _, Check.Mismatch _ -> { }
                    case Check.NotTested(var item) -> counts.merge(item.reason(), 1L, Long::sum);
                    case Check.Failed _ -> counts.merge(UntestedReason.INTERNAL, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    private static double resultOf(@Nullable Last last, ToDoubleFunction<Results> value) {
        Results results = last == null ? null : last.results();
        return results == null ? Double.NaN : value.applyAsDouble(results);
    }

    private static double is(@Nullable Last last, Status status) {
        return last == null ? Double.NaN : last.status() == status ? 1 : 0;
    }

    private static @Nullable Instant finishedAt(@Nullable Last last) {
        return last == null ? null : last.finishedAt();
    }

    private static double seconds(@Nullable Instant at) {
        return at == null ? Double.NaN : at.toEpochMilli() / 1000.0;
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void guarded(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("could not {} in the metrics", what, e);
        }
    }
}
