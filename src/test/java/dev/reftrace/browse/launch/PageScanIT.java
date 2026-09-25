package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.browse.scan.Origin;
import dev.reftrace.browse.scan.PageScanner;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Clicks;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class PageScanIT {

    private static final String KEY = "Adp7Fixture3";

    private static FixtureSite site;
    private static PlaywrightBrowserWorker worker;

    private final List<BrowserSession> sessions = new ArrayList<>();

    @BeforeAll
    static void startEverything() {
        site = FixtureSite.start();
        BrowserSettings settings = BrowserFixture.settings(site);
        worker = new PlaywrightBrowserWorker(settings, new PageScripts(settings.urls(), settings.unwalkedBlocks()),
                ObservationRegistry.NOOP);
    }

    @AfterAll
    static void stopEverything() {
        worker.close();
        site.close();
    }

    @AfterEach
    void closeSessions() {
        sessions.forEach(BrowserSession::close);
        sessions.clear();
    }

    @Test
    void readsEveryLinkOfThePageWithoutOneRequestReachingTheCounter() {
        PageDriver driver = open("/home?r=" + KEY);

        PageScan scan = scanEntered(site.routes(), driver);

        assertThat(scan.landedUrl().toString()).endsWith("/home?r=" + KEY);
        assertThat(scan.links()).filteredOn(link -> link.how() == How.HREF).extracting(FoundLink::selector)
                .doesNotHaveDuplicates()
                .noneMatch(selector -> selector.startsWith("a#"));
        assertThat(link(scan, How.HREF, url -> url.getPath().equals("/plain")).visible()).isTrue();
        assertThat(link(scan, How.HREF, url -> url.getPath().endsWith("/away/install")).visible()).isFalse();
        assertThat(link(scan, How.HREF, url -> FixtureSite.STORE_HOST.equals(url.getHost())).visible()).isTrue();
        FoundLink timer = link(scan, How.CLICK, url -> true);
        assertThat(timer.url().getPath()).endsWith("/" + KEY + "/button/install");
        assertThat(link(scan, How.HREF, url -> url.getPath().endsWith("/nokey/button/install")).selector())
                .isEqualTo(timer.selector());
        assertThat(scan.untested()).isEmpty();
        assertThat(site.counted()).isEmpty();
    }

    @Test
    void clicksEveryExitWhateverKeepsAVisitorFromIt() {
        PageScan scan = scanEntered(site.routes(), open("/unreachable?r=" + KEY));

        assertThat(scan.links()).filteredOn(link -> link.how() == How.CLICK)
                .extracting(link -> link.url().getPath())
                .containsExactly("/go/" + KEY + "/clipped-script/install", "/go/" + KEY + "/covered-script/install");
        assertThat(scan.untested()).singleElement().satisfies(untested -> {
            assertThat(untested.reason()).isEqualTo(UntestedReason.CLICK_NO_NAVIGATION);
            assertThat(untested.reason().bySite()).isFalse();
            assertThat(untested.selector()).isEqualTo("#dead");
            assertThat(untested.detail())
                    .endsWith(" ms of the click on " + site.exitBase() + "/" + KEY + "/dead/install");
        });
        assertThat(site.counted()).isEmpty();
    }

    @Test
    void anExitClickThatGoesNowhereIsTracedAsHavingWaitedOutItsTimeout() {
        TestObservationRegistry observations = TestObservationRegistry.create();
        BrowserSettings settings = BrowserFixture.settings(site);
        try (PlaywrightBrowserWorker own = new PlaywrightBrowserWorker(settings,
                new PageScripts(settings.urls(), settings.unwalkedBlocks()), observations)) {
            PageDriver driver = own.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM)).page();
            driver.open(URI.create(site.url("/unreachable?r=" + KEY)));

            new PageScanner(site.routes(), true, observations).scan(driver, new Origin.Entered());
        }

        Predicate<Observation.ContextView> inTheClicks = parent -> "page.exit-clicks".equals(parent.getName());
        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("page.exit-clicks", 1)
                .forAllObservationsWithNameEqualTo("page.exit-click", click -> click
                        .hasParentObservationContextMatching(inTheClicks))
                .hasAnObservation(click -> click.hasNameEqualTo("page.exit-click")
                        .hasHighCardinalityKeyValue("reftrace.selector", "#dead")
                        .hasLowCardinalityKeyValue("reftrace.click", "no-navigation")
                        .hasLowCardinalityKeyValue("reftrace.wait.outcome", "timeout")
                        .hasLowCardinalityKeyValue("reftrace.wait.for", "reaction"))
                .hasAnObservation(click -> click.hasNameEqualTo("page.exit-click")
                        .hasLowCardinalityKeyValue("reftrace.click", "navigated")
                        .hasLowCardinalityKeyValue("reftrace.wait.outcome", "condition"))
                .hasAnObservation(load -> load.hasNameEqualTo("page.load")
                        .hasLowCardinalityKeyValue("reftrace.wait.outcome", "condition")
                        .hasLowCardinalityKeyValue("reftrace.wait.for", "document"));
    }

    @Test
    void clicksOnlyWhatAVisitorCanReachWhenAskedTo() {
        BrowserSettings reachable = BrowserFixture.settingsWith(site, BrowserFixture.REVEAL, Clicks.REACHABLE,
                List.of());
        try (PlaywrightBrowserWorker own = new PlaywrightBrowserWorker(reachable,
                new PageScripts(reachable.urls(), reachable.unwalkedBlocks()), ObservationRegistry.NOOP)) {
            PageDriver driver = own.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM)).page();
            driver.open(URI.create(site.url("/unreachable?r=" + KEY)));

            PageScan scan = scanEntered(site.routes(), driver);

            assertThat(scan.links()).noneMatch(link -> link.how() == How.CLICK);
            assertThat(scan.links()).filteredOn(link -> link.how() == How.HREF)
                    .extracting(link -> link.url().getPath())
                    .contains("/go/" + KEY + "/clipped/install", "/go/" + KEY + "/covered/install");
            assertThat(scan.untested()).extracting(Untested::selector).containsExactly("#dead");
        }
        assertThat(site.counted()).isEmpty();
    }

    @Test
    void followsALinkIntoTheSiteAndSettlesThere() {
        PageDriver driver = open("/home?r=" + KEY);
        FoundLink plain = link(scanEntered(site.routes(), driver), How.HREF, url -> url.getPath().equals("/plain"));

        Navigation navigation = driver.follow(plain);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.Landed.class, landed -> {
            assertThat(landed.load().requestedUrl()).isEqualTo(plain.url());
            assertThat(landed.load().finalUrl().toString()).endsWith("/plain?r=" + KEY);
        });
        assertThat(driver.lastLoad().finalUrl()).isEqualTo(driver.currentUrl());
    }

    @Test
    void findsTheSameLinkAgainAfterAFreshLoad() {
        FoundLink plain = link(scanEntered(site.routes(), open("/home?r=" + KEY)), How.HREF,
                url -> url.getPath().equals("/plain"));
        PageDriver fresh = open("/home?r=" + KEY);

        assertThat(fresh.follow(plain)).isInstanceOf(Navigation.Landed.class);
        assertThat(fresh.currentUrl().getPath()).isEqualTo("/plain");
    }

    @Test
    void reportsALinkThatIsNotOnThePage() {
        PageDriver driver = open("/plain");
        FoundLink gone = new FoundLink(URI.create(site.url("/nowhere")), "main > a.gone", "gone", How.HREF, true,
                false);

        Navigation navigation = driver.follow(gone);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed -> {
            assertThat(notFollowed.untested().reason()).isEqualTo(UntestedReason.LINK_NOT_FOUND);
            assertThat(notFollowed.untested().selector()).isEqualTo("main > a.gone");
        });
        assertThat(driver.currentUrl().getPath()).isEqualTo("/plain");
    }

    @Test
    void namesTheElementItClickedWhenTheClickGoesWrong() {
        PageDriver driver = open("/plain");
        String store = link(scanEntered(site.routes(), driver), How.HREF,
                url -> FixtureSite.STORE_HOST.equals(url.getHost())).selector();
        FoundLink moved = new FoundLink(URI.create("https://" + FixtureSite.STORE_HOST + "/app"), "main > a.gone",
                "store page", How.HREF, true, false);

        Navigation navigation = driver.follow(moved);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed -> {
            assertThat(notFollowed.untested().reason()).isEqualTo(UntestedReason.CLICK_FAILED);
            assertThat(notFollowed.untested().selector()).isEqualTo(store).isNotEqualTo(moved.selector());
        });
        assertThat(site.counted()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"CHROMIUM", "WEBKIT"})
    void showsAsStepsOnlyTheLinksThisVisitorIsShown(BrowserEngine engine) {
        PageScan scan = scanEntered(site.routes(), openOn(engine, "/steps"));

        assertThat(scan.links()).filteredOn(link -> link.url().getPath().equals("/plain"))
                .extracting(FoundLink::selector, FoundLink::visible)
                .containsExactlyInAnyOrder(tuple("#desktop-copy", false), tuple("#desktop-only", false),
                        tuple("#drawer", true), tuple("#shown", true), tuple("#covered", true),
                        tuple("#clipped", true), tuple("#moving", true), tuple("#faded", false),
                        tuple("#invisible", false), tuple("#folded", false), tuple("#inert", true),
                        tuple("#far", true));
    }

    @ParameterizedTest
    @CsvSource({"CHROMIUM, shown", "CHROMIUM, drawer", "CHROMIUM, covered", "CHROMIUM, clipped", "CHROMIUM, moving",
            "CHROMIUM, far", "WEBKIT, drawer", "WEBKIT, covered", "WEBKIT, clipped", "WEBKIT, moving", "WEBKIT, far"})
    void followsAShownLinkWhateverKeepsThePointerFromIt(BrowserEngine engine, String id) {
        PageDriver driver = openOn(engine, "/steps");
        FoundLink link = scanEntered(site.routes(), driver).links().stream()
                .filter(found -> found.selector().equals("#" + id))
                .findFirst()
                .orElseThrow();

        Navigation navigation = driver.follow(link);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.Landed.class, landed -> assertThat(
                landed.load().finalUrl()).isEqualTo(URI.create(site.url("/plain?from=" + id))));
    }

    @Test
    void saysWhatKeepsAVisitorFromAStepItDoesNotClick() {
        TestObservationRegistry observations = TestObservationRegistry.create();
        Navigation covered;
        Navigation beside;
        Navigation shown;
        try (PlaywrightBrowserWorker own = observed(observations, Clicks.REACHABLE)) {
            PageDriver driver = own(own);
            driver.open(URI.create(site.url("/steps")));
            covered = driver.follow(step("covered", "Covered"));
            beside = driver.follow(step("drawer", "Drawer"));
            shown = driver.follow(step("shown", "Shown"));
        }

        assertThat(covered).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed -> {
            assertThat(notFollowed.untested().reason()).isEqualTo(UntestedReason.CLICK_FAILED);
            assertThat(notFollowed.untested().selector()).isEqualTo("#covered");
            assertThat(notFollowed.untested().detail()).isEqualTo("not clickable: covered by <div class=\"cover\"></div>");
        });
        assertThat(beside).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed ->
                assertThat(notFollowed.untested().detail()).isEqualTo("not clickable: outside of the viewport"));
        assertThat(shown).isInstanceOf(Navigation.Landed.class);
        Predicate<Observation.ContextView> inTheClick = parent -> "page.click".equals(parent.getName());
        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("page.click.reaction", 1)
                .hasAnObservation(actionable -> actionable.hasNameEqualTo("page.click.actionable")
                        .hasParentObservationContextMatching(inTheClick)
                        .hasLowCardinalityKeyValue("reftrace.click.blocker", "intercepted"))
                .hasAnObservation(click -> click.hasNameEqualTo("page.click")
                        .hasLowCardinalityKeyValue("reftrace.click", "not-reachable")
                        .hasLowCardinalityKeyValue("reftrace.click.blocker", "intercepted")
                        .hasHighCardinalityKeyValue("reftrace.click.interceptor", "<div class=\"cover\"></div>")
                        .doesNotHaveLowCardinalityKeyValueWithKey("reftrace.wait.outcome"))
                .hasAnObservation(click -> click.hasNameEqualTo("page.click")
                        .hasLowCardinalityKeyValue("reftrace.click", "not-reachable")
                        .hasLowCardinalityKeyValue("reftrace.click.blocker", "outside-viewport")
                        .doesNotHaveHighCardinalityKeyValueWithKey("reftrace.click.interceptor"))
                .hasAnObservation(click -> click.hasNameEqualTo("page.click")
                        .hasLowCardinalityKeyValue("reftrace.click", "navigated")
                        .hasLowCardinalityKeyValue("reftrace.click.blocker", "none"));
    }

    @Test
    void saysSoWhenAStepGoesNowhere() {
        TestObservationRegistry observations = TestObservationRegistry.create();
        Navigation navigation;
        try (PlaywrightBrowserWorker own = observed(observations, Clicks.ALL)) {
            PageDriver driver = own(own);
            driver.open(URI.create(site.url("/steps")));
            navigation = driver.follow(step("inert", "Goes nowhere"));
        }

        assertThat(navigation).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed ->
                assertThat(notFollowed.untested().reason()).isEqualTo(UntestedReason.CLICK_NO_NAVIGATION));
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("page.click").that()
                .hasLowCardinalityKeyValue("reftrace.click", "no-navigation")
                .hasLowCardinalityKeyValue("reftrace.click.blocker", "none")
                .hasLowCardinalityKeyValue("reftrace.wait.outcome", "timeout")
                .hasLowCardinalityKeyValue("reftrace.wait.for", "reaction");
    }

    @Test
    void letsOnlyAShownLinkStandInForAStepThatMoved() {
        PageDriver driver = open("/steps");
        FoundLink drawer = new FoundLink(URI.create(site.url("/plain?from=drawer")), "main > a.gone", "Drawer",
                How.HREF, true, false);
        FoundLink desktopOnly = new FoundLink(URI.create(site.url("/plain?from=desktop-only")), "main > a.gone",
                "Desktop only", How.HREF, true, false);

        assertThat(driver.follow(desktopOnly)).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed ->
                assertThat(notFollowed.untested().reason()).isEqualTo(UntestedReason.LINK_NOT_FOUND));
        assertThat(driver.follow(drawer)).isInstanceOf(Navigation.Landed.class);
    }

    private FoundLink step(String id, String text) {
        return new FoundLink(URI.create(site.url("/plain?from=" + id)), "#" + id, text, How.HREF, true, false);
    }

    @Test
    void timesTheTwoWaitsOfAClickApart() {
        TestObservationRegistry observations = TestObservationRegistry.create();
        try (PlaywrightBrowserWorker own = observed(observations, Clicks.ALL)) {
            PageDriver driver = own(own);
            driver.open(URI.create(site.url("/plain")));
            assertThat(driver.follow(new FoundLink(URI.create(site.url("/plain?r=plainkey")), "#internal",
                    "read on", How.HREF, true, false))).isInstanceOf(Navigation.Landed.class);
        }

        Predicate<Observation.ContextView> inTheClick = parent -> "page.click".equals(parent.getName());
        TestObservationRegistryAssert.assertThat(observations)
                .hasAnObservation(actionable -> actionable.hasNameEqualTo("page.click.actionable")
                        .hasParentObservationContextMatching(inTheClick)
                        .hasLowCardinalityKeyValue("reftrace.click.blocker", "none"))
                .hasAnObservation(reaction -> reaction.hasNameEqualTo("page.click.reaction")
                        .hasParentObservationContextMatching(inTheClick))
                .hasObservationWithNameEqualTo("page.click").that()
                .hasLowCardinalityKeyValue("reftrace.click", "navigated")
                .hasLowCardinalityKeyValue("reftrace.click.blocker", "none")
                .doesNotHaveHighCardinalityKeyValueWithKey("reftrace.click.interceptor")
                .hasLowCardinalityKeyValue("reftrace.wait.outcome", "condition")
                .hasLowCardinalityKeyValue("reftrace.wait.for", "reaction")
                .doesNotHaveEvent("call-log");
    }

    private PlaywrightBrowserWorker observed(TestObservationRegistry observations, Clicks clicks) {
        BrowserSettings settings = BrowserFixture.settingsWith(site, BrowserFixture.REVEAL, clicks, List.of());
        return new PlaywrightBrowserWorker(settings, new PageScripts(settings.urls(), settings.unwalkedBlocks()),
                observations);
    }

    private static PageDriver own(PlaywrightBrowserWorker worker) {
        return worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM)).page();
    }

    @Test
    void settlesAPageWhoseOnlyWayTowardsTheAppIsAQrCode() {
        PageDriver driver = driver();
        long startedAt = System.nanoTime();
        driver.open(URI.create(site.url("/qronly")));
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        PageScan scan = scanEntered(site.routes(), driver);

        assertThat(took).isLessThan(BrowserFixture.settings(site).timeouts().settleMax());
        assertThat(link(scan, How.QR, url -> true).url()).isEqualTo(URI.create(FixtureSite.QR_SHOWN));
        assertThat(scan.untested()).extracting(Untested::reason).containsExactly(UntestedReason.QR_HIDDEN);
        assertThat(link(scan, How.HREF, url -> url.getPath().equals("/redirect"))).isNotNull();
    }

    @Test
    void readsAPageWithNoWayTowardsTheAppOnceTheWaitIsOver() {
        PageDriver driver = driver();
        long startedAt = System.nanoTime();
        driver.open(URI.create(site.url("/bare")));
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took).isGreaterThanOrEqualTo(BrowserFixture.settings(site).timeouts().settleMax());
        List<FoundLink> links = scanEntered(site.routes(), driver).links();
        assertThat(links).extracting(FoundLink::selector)
                .containsExactly("a:nth-of-type(1)", "a:nth-of-type(2)");
        assertThat(driver.follow(links.get(1))).isInstanceOf(Navigation.Landed.class);
        assertThat(driver.currentUrl().getPath()).isEqualTo("/home");
    }

    @Test
    void takesOnlyAWholeHostTheRoutesStopForASignThatThePageIsDone() {
        Duration settleMax = BrowserFixture.settings(site).timeouts().settleMax();

        assertThat(timeToOpen("/banner")).isGreaterThanOrEqualTo(settleMax);
        assertThat(timeToOpen("/banner?r=" + KEY)).isLessThan(settleMax);
    }

    @Test
    void landsWhereTheServerRedirected() {
        PageLoad redirected = driver().open(URI.create(site.url("/redirect?r=" + KEY)));

        assertThat(redirected.finalUrl().toString()).endsWith("/plain?r=" + KEY);
    }

    @Test
    void followsALinkThroughARedirect() {
        PageDriver driver = open("/qronly");
        FoundLink more = link(scanEntered(site.routes(), driver), How.HREF, url -> url.getPath().equals("/redirect"));

        Navigation navigation = driver.follow(more);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.Landed.class, landed ->
                assertThat(landed.load().finalUrl().getPath()).isEqualTo("/plain"));
    }

    @Test
    void readsWhatThePageAFollowedLinkLedToStores() {
        Expectation.Cookie cookie = new Expectation.Cookie("ref");
        Expectation.LocalStorage localStorage = new Expectation.LocalStorage("ref");
        Routes routes = new Routes(List.of(
                new Route("127.0.0.1", List.of(Fixtures.match("127.0.0.1")), true, List.of(
                        new ExpectEntry(null, null, null, "ref", null, false),
                        new ExpectEntry(null, null, null, null, "ref", false))),
                new Route("127.0.0.1/go/**", List.of(Fixtures.match("127.0.0.1/go/**")), false, List.of())));
        PageDriver storing = open("/storelinks");
        FoundLink toStores = link(scanEntered(routes, storing), How.HREF, url -> url.getPath().equals("/stores"));
        PageDriver forgetting = open("/storelinks");
        FoundLink toPlain = link(scanEntered(routes, forgetting), How.HREF, url -> url.getPath().equals("/plain"));

        assertThat(storing.follow(toStores)).isInstanceOf(Navigation.Landed.class);
        assertThat(forgetting.follow(toPlain)).isInstanceOf(Navigation.Landed.class);

        assertThat(scanFollowed(routes, storing, toStores).stored()).containsExactly(
                new StoredValue(toStores, "127.0.0.1", cookie, "Stor3dKey1"),
                new StoredValue(toStores, "127.0.0.1", localStorage, "Stor3dKey1"));
        assertThat(scanFollowed(routes, forgetting, toPlain).stored()).containsExactly(
                new StoredValue(toPlain, "127.0.0.1", cookie, null),
                new StoredValue(toPlain, "127.0.0.1", localStorage, null));
        assertThat(scanEntered(routes, storing).stored()).as("a page scanned without the link that led to it")
                .isEmpty();
    }

    private PageDriver driver() {
        return driverOn(BrowserEngine.CHROMIUM);
    }

    private PageDriver driverOn(BrowserEngine engine) {
        BrowserSession session = worker.openSession(BrowserFixture.desktop(engine));
        sessions.add(session);
        return session.page();
    }

    private Duration timeToOpen(String path) {
        long startedAt = System.nanoTime();
        driver().open(URI.create(site.url(path)));
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private PageDriver open(String path) {
        return openOn(BrowserEngine.CHROMIUM, path);
    }

    private PageDriver openOn(BrowserEngine engine, String path) {
        PageDriver driver = driverOn(engine);
        driver.open(URI.create(site.url(path)));
        return driver;
    }

    private static PageScan scanEntered(Routes routes, PageDriver page) {
        return new PageScanner(routes, true, ObservationRegistry.NOOP).scan(page, new Origin.Entered());
    }

    private static PageScan scanFollowed(Routes routes, PageDriver page, FoundLink followed) {
        return new PageScanner(routes, true, ObservationRegistry.NOOP).scan(page, new Origin.Followed(followed));
    }

    private static FoundLink link(PageScan scan, How how, Predicate<URI> url) {
        return scan.links().stream()
                .filter(link -> link.how() == how && url.test(link.url()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such " + how + " link among " + scan.links()));
    }
}
