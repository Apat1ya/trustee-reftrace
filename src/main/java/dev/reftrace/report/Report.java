package dev.reftrace.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.Expectation;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.crawl.Walker;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Outcome;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.util.StdConverter;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public record Report(Run run, Coverage coverage, List<Page> pages) {

    public Report {
        pages = List.copyOf(pages);
    }

    public record Run(String id, URI site, Instant startedAt, Instant finishedAt, List<String> profiles) {

        public Run {
            profiles = List.copyOf(profiles);
        }
    }

    public record Coverage(Walker.Pages pages, Map<String, Long> scenarios, Walker.Links links, Checks checks,
                           Visits visits) {

        public Coverage {
            scenarios = Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
            if (links.checked() != checks.judged()) {
                throw new IllegalArgumentException("links checked (" + links.checked()
                        + ") must be the checks that passed or mismatched (" + checks.judged() + ")");
            }
        }

        public static Coverage of(Walker.Counters walk, List<String> scenarios, List<PageVisit> visits) {
            Map<String, Long> byScenario = new LinkedHashMap<>();
            for (String scenario : scenarios) {
                byScenario.put(scenario, visits.stream().filter(visit -> visit.scenarios().contains(scenario)).count());
            }
            return new Coverage(walk.pages(), byScenario, walk.links(),
                    Checks.count(visits.stream().flatMap(visit -> visit.checks().stream()).map(Check::outcome)
                            .toList()),
                    Visits.count(visits.stream().map(visit -> visit.checks().stream().map(Check::outcome)
                            .toList()).toList()));
        }
    }

    public record Checks(long pass, long mismatch, long untested, long failed) {

        public static Checks count(Collection<Outcome> outcomes) {
            long pass = 0;
            long mismatch = 0;
            long untested = 0;
            long failed = 0;
            for (Outcome outcome : outcomes) {
                switch (outcome) {
                    case PASS -> pass++;
                    case MISMATCH -> mismatch++;
                    case UNTESTED -> untested++;
                    case FAILED -> failed++;
                }
            }
            return new Checks(pass, mismatch, untested, failed);
        }

        public long judged() {
            return pass + mismatch;
        }
    }

    public record Visits(long pass, long mismatch, long untested, long failed, long nothingToCheck) {

        private static Visits count(Collection<? extends Collection<Outcome>> visits) {
            long pass = 0;
            long mismatch = 0;
            long untested = 0;
            long failed = 0;
            long nothingToCheck = 0;
            for (Collection<Outcome> outcomes : visits) {
                Optional<Outcome> worst = Outcome.worst(outcomes);
                if (worst.isEmpty()) {
                    nothingToCheck++;
                    continue;
                }
                switch (worst.get()) {
                    case PASS -> pass++;
                    case MISMATCH -> mismatch++;
                    case UNTESTED -> untested++;
                    case FAILED -> failed++;
                }
            }
            return new Visits(pass, mismatch, untested, failed, nothingToCheck);
        }
    }

    public record Page(URI page, String profile, List<String> scenarios, List<String> path, List<Problem> problems,
                       List<UntestedItem> untested) {

        public Page {
            scenarios = List.copyOf(scenarios);
            path = List.copyOf(path);
            problems = List.copyOf(problems);
            untested = List.copyOf(untested);
        }
    }

    public record Problem(String route, URI href, String selector,
                          @JsonSerialize(converter = RelativePath.class) @Nullable Path screenshot,
                          Checked checked, String expected, @Nullable String actual) {
    }

    public sealed interface Checked {

        static Checked of(Expectation expectation) {
            return switch (expectation) {
                case Expectation.PathSegment(int index) -> new PathSegment(index);
                case Expectation.Query(String name) -> new Query(name);
                case Expectation.QueryIfPresent(String name) -> new QueryIfPresent(name);
                case Expectation.Cookie(String name) -> new Cookie(name);
                case Expectation.LocalStorage(String name) -> new LocalStorage(name);
                case Expectation.Fail _ -> new Fail();
            };
        }

        record PathSegment(@JsonProperty("path-segment") int index) implements Checked {
        }

        record Query(@JsonProperty("query") String name) implements Checked {
        }

        record QueryIfPresent(@JsonProperty("query-if-present") String name) implements Checked {
        }

        record Cookie(@JsonProperty("cookie") String name) implements Checked {
        }

        record LocalStorage(@JsonProperty("local-storage") String name) implements Checked {
        }

        record Fail() implements Checked {

            @JsonValue
            public String word() {
                return ExpectEntry.FAIL;
            }
        }
    }

    public record UntestedItem(UntestedReason reason,
                               @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Integer status,
                               @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String selector,
                               String detail,
                               @JsonSerialize(converter = RelativePath.class) @Nullable Path screenshot) {
    }

    static final class RelativePath extends StdConverter<Path, String> {

        @Override
        public String convert(Path path) {
            return StreamSupport.stream(path.spliterator(), false).map(Path::toString)
                    .collect(Collectors.joining("/"));
        }
    }
}
