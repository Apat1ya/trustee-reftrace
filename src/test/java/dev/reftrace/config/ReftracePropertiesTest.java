package dev.reftrace.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReftracePropertiesTest {

    private static final String SITE_AND_DEEP_LINK = "reftrace.routes[0].name=route-0; "
            + "reftrace.routes[0].match[0]=trustee.io; reftrace.routes[0].follow=true; "
            + "reftrace.routes[1].name=route-1; reftrace.routes[1].match[0]=*.app.link";
    private static final String SWITCHABLE = "limits.action.page-load, limits.action.operation, "
            + "limits.visit.deadline and limits.run.time must not be negative";
    private static final String ANSWERING = "limits.action.settle-quiet, limits.action.settle-max, "
            + "limits.action.click-navigation and limits.visit.backoff must be positive";
    private static final String RETENTION = "report.retention.screenshots and report.retention.traces must not be longer";
    private static final String EXPECT_ONE_KIND = "an expect entry names exactly one of path-segment, query, "
            + "query-if-present, cookie, local-storage, fail";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CoreConfiguration.class);

    @Test
    void theShippedConfigurationBindsAndValidates() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ReftraceProperties properties = context.getBean(ReftraceProperties.class);

            assertThat(properties.starts()).containsExactly(
                    new Start.Page(URI.create("https://trustee.io/")),
                    new Start.Sitemap(URI.create("https://trustee.io/main-sitemap.xml")));
            assertThat(properties.routes().get(0).onLink()).containsExactly(new Expectation.QueryIfPresent("r"));
            assertThat(properties.routes().get(2).onLink()).containsExactly(new Expectation.PathSegment(1));
            assertThat(properties.routes().get(3).onLink()).containsExactly(new Expectation.Fail());
            assertThat(properties.scenarios().get(2).steps()).containsExactly(StepWord.ENTER, StepWord.CLICK_ANY,
                    StepWord.ENTER_NEW_KEY, StepWord.CLICK_ANY);
            assertThat(properties.schedule().cron()).hasToString("0 0 3 * * *");
            assertThat(properties.schedule().zone()).isEqualTo(ZoneId.of("UTC"));
        });
    }

    @ParameterizedTest
    @CsvSource({
            "https://trusteeplus.app.link/Rf7xQ2mK9pL, true",
            "https://other.app.link/x, false",
            "https://app.link/x, false",
            "https://api2.branch.io/v1/open, false",
            "https://t.ki/anything, false",
            "https://t.ki/banner_pl, true",
            "https://apps.apple.com/pl/app/trustee-plus/id1634455978, true",
            "https://itunes.apple.com/app/id1634455978, true",
            "https://play.google.com/store/apps/details?id=com.trusteeplus, true"})
    void theShippedRoutesNeverFollowTheHostsOfAttributionAndInstalls(URI url, boolean checked) {
        runner.run(context -> {
            Routes routes = context.getBean(Routes.class);

            assertThat(routes.followed(url)).isFalse();
            assertThat(routes.routeFor(url)).get()
                    .satisfies(route -> assertThat(!route.expect().isEmpty()).isEqualTo(checked));
        });
    }

    @Test
    void checksQrCodesUnlessSwitchedOff() {
        ReftraceProperties omitted = new Binder(new MapConfigurationPropertySource(Map.of("reftrace.key-param", "r")))
                .bindOrCreate("reftrace", ReftraceProperties.class);

        assertThat(omitted.qrCheck()).isTrue();
    }

    @Test
    void splitsTheExpectationsOfARouteIntoTheLinkAndTheArrival() {
        runner.withPropertyValues(
                        "reftrace.routes[0].name=route-0",
                        "reftrace.routes[0].match[0]=trustee.io",
                        "reftrace.routes[0].follow=true",
                        "reftrace.routes[0].expect[0].cookie=ref",
                        "reftrace.routes[0].expect[1].local-storage=referral",
                        "reftrace.routes[0].expect[2].query=r")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Route route = context.getBean(ReftraceProperties.class).routes().getFirst();
                    assertThat(route.onArrival()).containsExactly(new Expectation.Cookie("ref"),
                            new Expectation.LocalStorage("referral"));
                    assertThat(route.onLink()).containsExactly(new Expectation.Query("r"));
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            reftrace.limits.action.page-load=0s; reftrace.limits.action.operation=0s; reftrace.limits.visit.deadline=0s; reftrace.limits.run.time=0s
            reftrace.limits.action.settle-quiet=8s
            reftrace.limits.run.depth=0; reftrace.limits.run.pages=0; reftrace.limits.run.visits=0
            reftrace.report.retention.run=3d; reftrace.report.retention.screenshots=3d; reftrace.report.retention.traces=1d
            reftrace.report.retention.run=0; reftrace.report.retention.screenshots=5m; reftrace.report.retention.traces=0
            """)
    void accepts(String properties) {
        runner.withPropertyValues(split(properties)).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void refuses(String hint, String properties) {
        runner.withPropertyValues(split(properties)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(stackTraceOf(context)).contains(hint);
        });
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("profiles", "reftrace.profiles="),
                Arguments.of("profile names must be unique", "reftrace.profiles[0].name=desktop-chrome; "
                        + "reftrace.profiles[0].engine=chromium; reftrace.profiles[0].viewport.width=1440; "
                        + "reftrace.profiles[0].viewport.height=900; reftrace.profiles[1].name=desktop-chrome; "
                        + "reftrace.profiles[1].engine=webkit; reftrace.profiles[1].viewport.width=390; "
                        + "reftrace.profiles[1].viewport.height=844"),
                Arguments.of("width", "reftrace.profiles[0].viewport.width=0"),
                Arguments.of("blockedAnalyticsHosts", "reftrace.browser.blocked-analytics-hosts="),
                Arguments.of("parallelBrowsers", "reftrace.parallel-browsers=0"),
                Arguments.of(SWITCHABLE, "reftrace.limits.action.page-load=-1s"),
                Arguments.of(SWITCHABLE, "reftrace.limits.action.operation=-1s"),
                Arguments.of(SWITCHABLE, "reftrace.limits.visit.deadline=-1s"),
                Arguments.of(SWITCHABLE, "reftrace.limits.run.time=-1s"),
                Arguments.of(ANSWERING, "reftrace.limits.action.settle-quiet=0s"),
                Arguments.of(ANSWERING, "reftrace.limits.action.settle-max=0s"),
                Arguments.of(ANSWERING, "reftrace.limits.action.click-navigation=0s"),
                Arguments.of(ANSWERING, "reftrace.limits.visit.backoff=-5s"),
                Arguments.of("limits.action.settle-quiet must not exceed limits.action.settle-max",
                        "reftrace.limits.action.settle-quiet=60s; reftrace.limits.action.settle-max=1s"),
                Arguments.of("limits.run.depth", "reftrace.limits.run.depth=-1"),
                Arguments.of("limits.run.pages", "reftrace.limits.run.pages=-3"),
                Arguments.of("limits.run.visits", "reftrace.limits.run.visits=-3"),
                Arguments.of("limits.visit.maxAttempts", "reftrace.limits.visit.max-attempts=0"),
                Arguments.of("limits.visit.backoffMultiplier", "reftrace.limits.visit.backoff-multiplier=0.5"),
                Arguments.of(RETENTION, "reftrace.report.retention.run=3d; reftrace.report.retention.traces=1d"),
                Arguments.of(RETENTION, "reftrace.report.retention.run=3d; reftrace.report.retention.screenshots=3d; "
                        + "reftrace.report.retention.traces=PT73H"),
                Arguments.of(RETENTION, "reftrace.report.retention.run=3d; reftrace.report.retention.screenshots=0; "
                        + "reftrace.report.retention.traces=1d"),
                Arguments.of("key-param must be a query parameter name", "reftrace.key-param=r&x"),
                Arguments.of("keyParam", "reftrace.key-param="),
                Arguments.of("every start entry is either a page URL or {sitemap: URL}", "reftrace.start[0]=/ua/"),
                Arguments.of("every start entry is either a page URL or {sitemap: URL}",
                        "reftrace.start[0]=ftp://trustee.io/"),
                Arguments.of("every start entry is either a page URL or {sitemap: URL}",
                        "reftrace.start[0].page=https://trustee.io/; "
                                + "reftrace.start[0].sitemap=https://trustee.io/main-sitemap.xml"),
                Arguments.of("start", "reftrace.start="),
                Arguments.of("every start page must be matched last by a route with follow: true",
                        "reftrace.start[0]=https://trusteee.io/"),
                Arguments.of("every start page must be matched last by a route with follow: true",
                        SITE_AND_DEEP_LINK.replace("*.app.link", "trustee.io/") + "; reftrace.routes[1].follow=false"),
                Arguments.of("every night", "reftrace.schedule.cron=every night"),
                Arguments.of("Mars/Olympus", "reftrace.schedule.zone=Mars/Olympus"),
                Arguments.of("coverage", "reftrace.coverage=sometimes"),
                Arguments.of("unknown step: jump", "reftrace.scenarios[0].name=entry; "
                        + "reftrace.scenarios[0].steps[0]=enter; reftrace.scenarios[0].steps[1]=jump"),
                Arguments.of("scenarios", "reftrace.scenarios="),
                Arguments.of("steps", "reftrace.scenarios[0].name=entry"),
                Arguments.of("every scenario starts with enter and has no other enter",
                        "reftrace.scenarios[0].name=browse; reftrace.scenarios[0].steps[0]=click*"),
                Arguments.of("every scenario starts with enter and has no other enter",
                        "reftrace.scenarios[0].name=twice; reftrace.scenarios[0].steps[0]=enter; "
                                + "reftrace.scenarios[0].steps[1]=enter"),
                Arguments.of("scenario names must be unique", "reftrace.scenarios[0].name=entry; "
                        + "reftrace.scenarios[0].steps[0]=enter; reftrace.scenarios[1].name=entry; "
                        + "reftrace.scenarios[1].steps[0]=enter"),
                Arguments.of("routes", "reftrace.routes="),
                Arguments.of("match", "reftrace.routes[0].name=route-0; reftrace.routes[0].match[0]=trustee.io; "
                        + "reftrace.routes[0].follow=true; reftrace.routes[1].name=route-1; "
                        + "reftrace.routes[1].follow=false"),
                Arguments.of("routes[1].name", SITE_AND_DEEP_LINK.replace("reftrace.routes[1].name=route-1; ", "")),
                Arguments.of("routes[1].name", SITE_AND_DEEP_LINK.replace("route-1", " ")),
                Arguments.of("route names must be unique", SITE_AND_DEEP_LINK.replace("route-1", "route-0")),
                Arguments.of("a match has no scheme, port, query or fragment",
                        SITE_AND_DEEP_LINK.replace("*.app.link", "https://trustee.io")),
                Arguments.of("a match has no scheme, port, query or fragment",
                        SITE_AND_DEEP_LINK.replace("*.app.link", "trustee.io:443")),
                Arguments.of("a host is exact or starts with '*.'",
                        SITE_AND_DEEP_LINK.replace("*.app.link", "trustee*.io")),
                Arguments.of("an expect entry is {path-segment: n}, {query: name}, {query-if-present: name}, "
                        + "{cookie: name}, {local-storage: name} or fail", SITE_AND_DEEP_LINK + "; reftrace.routes[1].expect=never"),
                Arguments.of(EXPECT_ONE_KIND, SITE_AND_DEEP_LINK
                        + "; reftrace.routes[1].expect[0].path-segment=1; reftrace.routes[1].expect[0].query=r"),
                Arguments.of(EXPECT_ONE_KIND, SITE_AND_DEEP_LINK
                        + "; reftrace.routes[0].expect[0].cookie=ref; reftrace.routes[0].expect[0].local-storage=ref"),
                Arguments.of("expect: cookie and local-storage only on a route with follow: true",
                        SITE_AND_DEEP_LINK + "; reftrace.routes[1].follow=false; reftrace.routes[1].expect[0].cookie=ref"),
                Arguments.of("expect: cookie and local-storage only on a route with follow: true",
                        SITE_AND_DEEP_LINK + "; reftrace.routes[1].expect[0].local-storage=ref"),
                Arguments.of("cookie name must not be blank", SITE_AND_DEEP_LINK + "; reftrace.routes[0].expect[0].cookie="),
                Arguments.of("path segments are counted from 1",
                        SITE_AND_DEEP_LINK + "; reftrace.routes[1].expect[0].path-segment=0"),
                Arguments.of("expect: fail stands alone and only on a route with follow: false", SITE_AND_DEEP_LINK
                        + "; reftrace.routes[1].expect[0]=fail; reftrace.routes[1].expect[1].path-segment=1"),
                Arguments.of("expect: fail stands alone and only on a route with follow: false",
                        SITE_AND_DEEP_LINK + "; reftrace.routes[0].expect=fail"));
    }

    private static String[] split(String properties) {
        return Stream.of(properties.split(";")).map(String::trim).toArray(String[]::new);
    }

    private static String stackTraceOf(AssertableApplicationContext context) {
        StringWriter text = new StringWriter();
        Objects.requireNonNull(context.getStartupFailure()).printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
