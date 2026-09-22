package dev.reftrace.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ConfigurationProperties("reftrace")
@Validated
public record ReftraceProperties(@NotNull @Valid Browser browser,
                                 @NotEmpty List<@Valid Profile> profiles,
                                 @Min(1) int parallelBrowsers,
                                 @NotNull @Valid Limits limits,
                                 @NotNull @Valid Report report,
                                 @NotBlank String keyParam,
                                 Boolean qrCheck,
                                 @NotEmpty List<@NotNull StartEntry> start,
                                 @NotEmpty List<@NotNull @Valid Route> routes,
                                 @NotEmpty List<@NotNull @Valid Scenario> scenarios,
                                 @NotNull CoverageMode coverage,
                                 @NotNull @Valid Schedule schedule) {

    private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z0-9._~-]+");

    public ReftraceProperties {
        profiles = copy(profiles);
        start = copy(start);
        routes = copy(routes);
        scenarios = copy(scenarios);
        qrCheck = Objects.requireNonNullElse(qrCheck, true);
    }

    public List<Start> starts() {
        return start.stream().map(StartEntry::toStart).toList();
    }

    @AssertTrue(message = "key-param must be a query parameter name of letters, digits, '.', '_', '~' or '-'")
    public boolean isKeyParamAQueryName() {
        return keyParam == null || keyParam.isBlank() || QUERY_NAME.matcher(keyParam).matches();
    }

    @AssertTrue(message = "every start entry is either a page URL or {sitemap: URL}, "
            + "each an absolute http(s) URL without a fragment")
    public boolean isEveryStartEntryUsable() {
        if (start == null) {
            return true;
        }
        return start.stream().allMatch(entry -> {
            try {
                return switch (entry.toStart()) {
                    case Start.Page page -> isOpenable(page.url());
                    case Start.Sitemap sitemap -> isOpenable(sitemap.url());
                };
            } catch (IllegalArgumentException _) {
                return false;
            }
        });
    }

    @AssertTrue(message = "every start page must be matched last by a route with follow: true")
    public boolean isEveryStartPageFollowed() {
        if (start == null || routes == null) {
            return true;
        }
        Routes lookup = new Routes(routes);
        return start.stream()
                .map(StartEntry::page)
                .filter(Objects::nonNull)
                .allMatch(page -> !isOpenable(page) || lookup.followed(page));
    }

    @AssertTrue(message = "expect: fail stands alone and only on a route with follow: false")
    public boolean isFailAloneOnUnfollowedRoutes() {
        if (routes == null) {
            return true;
        }
        return routes.stream().allMatch(route -> {
            boolean fails = route.expect().stream().anyMatch(ExpectEntry::fail);
            return !fails || (!route.follow() && route.expect().size() == 1);
        });
    }

    @AssertTrue(message = "expect: cookie and local-storage only on a route with follow: true")
    public boolean isStorageOnFollowedRoutes() {
        if (routes == null) {
            return true;
        }
        return routes.stream().allMatch(route -> route.follow() || route.onArrival().isEmpty());
    }

    @AssertTrue(message = "scenario names must be unique")
    public boolean isScenarioNamesUnique() {
        if (scenarios == null) {
            return true;
        }
        Set<String> names = new HashSet<>();
        return scenarios.stream().allMatch(scenario -> scenario.name() == null || names.add(scenario.name()));
    }

    @AssertTrue(message = "route names must be unique")
    public boolean isRouteNamesUnique() {
        if (routes == null) {
            return true;
        }
        Set<String> names = new HashSet<>();
        return routes.stream().allMatch(route -> route.name() == null || names.add(route.name()));
    }

    @AssertTrue(message = "every scenario starts with enter and has no other enter")
    public boolean isEveryScenarioEnteredOnce() {
        if (scenarios == null) {
            return true;
        }
        return scenarios.stream()
                .map(Scenario::steps)
                .filter(steps -> !steps.isEmpty())
                .allMatch(steps -> steps.getFirst() == StepWord.ENTER
                        && steps.lastIndexOf(StepWord.ENTER) == 0);
    }

    @AssertTrue(message = "a scenario that clicks needs a route with follow: true")
    public boolean isClickingPossible() {
        if (scenarios == null || routes == null) {
            return true;
        }
        boolean clicks = scenarios.stream().flatMap(scenario -> scenario.steps().stream())
                .anyMatch(step -> step == StepWord.CLICK || step == StepWord.CLICK_ANY);
        return !clicks || routes.stream().anyMatch(Route::follow);
    }

    private static boolean isOpenable(URI url) {
        String scheme = url.getScheme();
        return url.isAbsolute() && url.getHost() != null && url.getRawFragment() == null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    @AssertTrue(message = "profile names must be unique")
    public boolean isProfileNamesUnique() {
        Set<String> names = new HashSet<>();
        return profiles.stream().allMatch(profile -> profile.name() == null || names.add(profile.name()));
    }

    @AssertTrue(message = "limits.action.page-load, limits.action.operation, limits.visit.deadline and "
            + "limits.run.time must not be negative; 0 switches them off")
    public boolean isEverySwitchableTimeoutZeroOrMore() {
        if (limits == null) {
            return true;
        }
        Limits.Action action = limits.action();
        Stream<Duration> steps = action == null ? Stream.empty() : Stream.of(action.pageLoad(), action.operation());
        Stream<Duration> visit = limits.visit() == null ? Stream.empty() : Stream.of(limits.visit().deadline());
        Stream<Duration> run = limits.run() == null ? Stream.empty() : Stream.of(limits.run().time());
        return Stream.of(steps, visit, run).flatMap(durations -> durations)
                .allMatch(duration -> duration == null || !duration.isNegative());
    }

    @AssertTrue(message = "limits.action.settle-quiet, limits.action.settle-max, limits.action.click-navigation "
            + "and limits.visit.backoff must be positive")
    public boolean isEveryAnsweringWaitPositive() {
        if (limits == null) {
            return true;
        }
        Limits.Action action = limits.action();
        Stream<Duration> steps = action == null ? Stream.empty()
                : Stream.of(action.settleQuiet(), action.settleMax(), action.clickNavigation());
        Stream<Duration> visit = limits.visit() == null ? Stream.empty() : Stream.of(limits.visit().backoff());
        return Stream.concat(steps, visit).allMatch(ReftraceProperties::isPositive);
    }

    @AssertTrue(message = "limits.action.settle-quiet must not exceed limits.action.settle-max")
    public boolean isSettleQuietWithinSettleMax() {
        Limits.Action action = limits == null ? null : limits.action();
        if (action == null || action.settleQuiet() == null || action.settleMax() == null) {
            return true;
        }
        return action.settleQuiet().compareTo(action.settleMax()) <= 0;
    }

    private static boolean isPositive(Duration duration) {
        return duration == null || (!duration.isZero() && !duration.isNegative());
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record Browser(boolean headless, @NotEmpty List<@NotBlank String> blockedAnalyticsHosts, Clicks clicks,
                          List<@NotBlank String> unwalkedBlocks) {

        public Browser {
            blockedAnalyticsHosts = copy(blockedAnalyticsHosts);
            unwalkedBlocks = copy(unwalkedBlocks);
            clicks = Objects.requireNonNullElse(clicks, Clicks.ALL);
        }
    }

    public record Profile(@NotBlank String name,
                          @NotNull BrowserEngine engine,
                          @Nullable String userAgent,
                          @NotNull @Valid ViewportSize viewport,
                          @Positive double deviceScaleFactor,
                          boolean mobile,
                          boolean touch,
                          @Nullable String locale) {

        public Profile {
            if (deviceScaleFactor == 0) {
                deviceScaleFactor = 1;
            }
        }

        public record ViewportSize(@Positive int width, @Positive int height) {
        }
    }

    public record Limits(@NotNull @Valid Action action, @NotNull @Valid Visit visit, @NotNull @Valid Run run) {

        public record Action(@NotNull Duration pageLoad,
                             @NotNull Duration settleQuiet,
                             @NotNull Duration settleMax,
                             @NotNull Duration operation,
                             @NotNull Duration clickNavigation) {
        }

        public record Visit(@NotNull Duration deadline,
                            @Min(1) int maxAttempts,
                            @NotNull Duration backoff,
                            @DecimalMin("1.0") double backoffMultiplier) {
        }

        public record Run(@NotNull Duration time,
                          @Min(0) int depth,
                          @Min(0) int pages,
                          @Min(0) int visits) {
        }
    }

    public record Report(@NotBlank String outputDir,
                         @DefaultValue("true") boolean screenshots,
                         @DefaultValue("true") boolean traces,
                         @NotNull @Valid Retention retention) {

        public record Retention(@NotNull @DurationMin Duration run,
                                @NotNull @DurationMin Duration screenshots,
                                @NotNull @DurationMin Duration traces) {

            @AssertTrue(message = "report.retention.screenshots and report.retention.traces must not be longer "
                    + "than report.retention.run; 0 is forever")
            public boolean isEveryPartWithinTheRun() {
                if (run == null || run.isZero()) {
                    return true;
                }
                return Stream.of(screenshots, traces)
                        .allMatch(part -> part == null || (!part.isZero() && part.compareTo(run) <= 0));
            }
        }
    }

    public record Schedule(boolean enabled, @NotNull CronExpression cron, @NotNull ZoneId zone) {
    }
}
