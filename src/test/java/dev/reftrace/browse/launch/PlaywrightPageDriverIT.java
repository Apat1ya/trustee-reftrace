package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.page.BrowserTimeouts;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.browse.page.RevealPlan;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Clicks;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightPageDriverIT {

    private static final String KEY = "Adp7Fixture1";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};

    private static FixtureSite site;
    private static PlaywrightBrowserWorker worker;

    private @Nullable BrowserSession session;

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
    void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Test
    void waitsForTheSecondPassAndForTheButtonToChangeItsMind() {
        PageDriver driver = open("/home?r=" + KEY);

        List<String> exits = exitHrefs(driver);
        assertThat(exits).anyMatch(href -> href.contains(FixtureSite.STORE_HOST));
        assertThat(exits).noneMatch(href -> href.contains("/" + KEY + "/store/install"));
    }

    @Test
    void doesNotTakeTheFirstRenderPassForTheFinishedPage() {
        PageDriver driver = driver();
        long startedAt = System.nanoTime();
        driver.open(URI.create(site.url("/slowsecond?r=" + KEY)));
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took).isGreaterThan(Duration.ofSeconds(1));
        assertThat(exitHrefs(driver)).anyMatch(href -> href.contains(FixtureSite.STORE_HOST));
    }

    @Test
    void countsTheQuietPeriodFromTheRouterPass() {
        PageDriver driver = open("/earlystore?r=" + KEY);

        assertThat(exitHrefs(driver)).containsExactly("https://" + FixtureSite.STORE_HOST + "/app?r=" + KEY);
    }

    @Test
    void keepsTrackingOffTheNetwork() {
        open("/home?r=" + KEY);

        assertThat(site.counted()).noneMatch(request -> request.startsWith("/pixel.gif"));
    }

    @Test
    void opensTheDrawerAndTheHoverCardAndStillReportsAnExitItCouldNotUncover() {
        PageDriver driver = open("/home?r=" + KEY);

        assertThat(anchor(driver, "/drawer/install").visible()).isTrue();
        assertThat(anchor(driver, "/code/install").visible()).isTrue();
        assertThat(anchor(driver, "/away/install").visible()).isFalse();
    }

    @Test
    void leavesTheInterfaceClosedWhenRevealingIsSwitchedOff() {
        BrowserSettings closed = BrowserFixture.settingsWith(site,
                new RevealPlan(false, Duration.ofSeconds(5), 24, List.of(), List.of()), Clicks.ALL, List.of());
        try (PlaywrightBrowserWorker quiet = new PlaywrightBrowserWorker(closed,
                new PageScripts(closed.urls(), closed.unwalkedBlocks()), ObservationRegistry.NOOP)) {
            BrowserSession own = quiet.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM));
            PageDriver driver = own.page();
            driver.open(URI.create(site.url("/home?r=" + KEY)));

            assertThat(anchor(driver, "/drawer/install").visible()).isFalse();
            assertThat(anchor(driver, "/code/install").visible()).isFalse();
        }
    }

    @Test
    void opensWhatAConfiguredRevealerNames() {
        BrowserSettings named = BrowserFixture.settingsWith(site, new RevealPlan(true, Duration.ofSeconds(5), 1,
                List.of(new RevealPlan.Revealer("drawer", "#burger",
                        RevealPlan.RevealAction.CLICK)), List.of()), Clicks.ALL, List.of());
        try (PlaywrightBrowserWorker own = new PlaywrightBrowserWorker(named,
                new PageScripts(named.urls(), named.unwalkedBlocks()), ObservationRegistry.NOOP)) {
            BrowserSession opened = own.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM));
            PageDriver driver = opened.page();
            driver.open(URI.create(site.url("/home?r=" + KEY)));

            assertThat(anchor(driver, "/drawer/install").visible()).isTrue();
        }
    }

    @Test
    void clicksAnExitNoPointerCouldPress() {
        PageDriver driver = open("/unreachable?r=" + KEY);

        for (String what : List.of("/gone/install", "/inert/install")) {
            DomAnchor exit = anchor(driver, what);
            assertThat(exit.visible()).as(what).isEqualTo(what.startsWith("/inert"));

            ClickObservation observation = driver.clickExit(exit.locator(), String.valueOf(exit.href()));

            assertThat(reached(observation)).as(what).isEqualTo(String.valueOf(exit.href()));
        }
        assertThat(site.counted()).isEmpty();
    }

    @Test
    void aTimeoutOfZeroLetsTheBrowserWaitAsLongAsItTakes() {
        BrowserSettings unbounded = new BrowserSettings(true, site.patterns(),
                new BrowserTimeouts(Duration.ZERO, Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ZERO,
                        Duration.ofSeconds(3)),
                BrowserFixture.REVEAL, Clicks.ALL, List.of(), true);
        try (PlaywrightBrowserWorker own = new PlaywrightBrowserWorker(unbounded,
                new PageScripts(unbounded.urls(), unbounded.unwalkedBlocks()), ObservationRegistry.NOOP)) {
            BrowserSession opened = own.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM));
            PageDriver driver = opened.page();
            driver.open(URI.create(site.url("/home?r=" + KEY)));
            DomAnchor timer = anchor(driver, "/nokey/button/install");

            ClickObservation observation = driver.clickExit(timer.locator(), String.valueOf(timer.href()));

            assertThat(reached(observation)).endsWith("/" + KEY + "/button/install");
        }
    }

    @Test
    void takesWhereTheTabWasSentNotWhatThePageReportedOnTheWay() {
        PageDriver driver = open("/beacon");
        DomAnchor beacon = anchor(driver, "/beacon/install");

        ClickObservation observation = driver.clickExit(beacon.locator(), String.valueOf(beacon.href()));

        assertThat(reached(observation)).endsWith("/beaconkey/beacon/install");
        assertThat(site.counted()).isEmpty();
    }

    @Test
    void clicksTheNextExitAfterALinkThatOpenedItsOwnTab() {
        PageDriver driver = open("/home?r=" + KEY);
        DomAnchor apk = anchor(driver, ".apk");
        DomAnchor store = driver.anchors().stream()
                .filter(found -> found.visible() && String.valueOf(found.href()).contains(FixtureSite.STORE_HOST))
                .findFirst().orElseThrow();

        ClickObservation one = driver.clickExit(apk.locator(), String.valueOf(apk.href()));
        ClickObservation two = driver.clickExit(store.locator(), String.valueOf(store.href()));

        assertThat(reached(one)).endsWith(".apk");
        assertThat(one.pageIntact()).isTrue();
        assertThat(reached(two)).contains(FixtureSite.STORE_HOST).doesNotEndWith(".apk");
    }

    @Test
    void refusesToJudgeAPageThatNeverStandsStill() {
        PageDriver driver = driver();

        assertThatThrownBy(() -> driver.open(URI.create(site.url("/restless"))))
                .isInstanceOf(PageCheckException.class)
                .extracting(error -> ((PageCheckException) error).error())
                .satisfies(error -> {
                    assertThat(error.kind()).isEqualTo(UntestedReason.PAGE_LOAD_TIMEOUT);
                    assertThat(error.message()).contains("never stopped changing");
                });
    }

    @Test
    void reportsTheHttpStatusItself() {
        PageLoad load = driver().open(URI.create(site.url("/boom")));

        assertThat(load.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(load.finalUrl().toString()).endsWith("/boom");
    }

    @Test
    void failsWhenNothingIsListening() {
        PageDriver driver = driver();

        assertThatThrownBy(() -> driver.open(URI.create(site.unreachableUrl())))
                .isInstanceOf(PageCheckException.class)
                .extracting(error -> ((PageCheckException) error).error().kind())
                .isEqualTo(UntestedReason.NAVIGATION_ERROR);
    }

    @Test
    void failsWhenThePageNeverAnswers() {
        PageDriver driver = driver();

        assertThatThrownBy(() -> driver.open(URI.create(site.url("/hang"))))
                .isInstanceOf(PageCheckException.class)
                .extracting(error -> ((PageCheckException) error).error().kind())
                .isEqualTo(UntestedReason.PAGE_LOAD_TIMEOUT);
    }

    @Test
    void readsTheQrCodesOnScreen() {
        PageDriver driver = open("/qr");

        assertThat(driver.qrCodes()).satisfiesExactly(
                shown -> assertThat(shown).isInstanceOfSatisfying(QrReading.Decoded.class,
                        decoded -> assertThat(decoded.text()).isEqualTo(FixtureSite.QR_SHOWN)),
                covered -> assertThat(covered).isInstanceOf(QrReading.Unreadable.class));
        assertThat(site.counted()).isEmpty();
    }

    @Test
    void readsTheQrCodesWithoutWaitingForASpinningPictureToStop() {
        PageDriver driver = open("/qrspinning");
        long startedAt = System.nanoTime();

        List<QrReading> readings = driver.qrCodes();

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(readings).singleElement().isInstanceOfSatisfying(QrReading.Decoded.class,
                decoded -> assertThat(decoded.text()).isEqualTo(FixtureSite.QR_SHOWN));
    }

    @Test
    void outlinesTheLinkAScreenshotIsAboutAndTakesTheOutlineOffAgain() {
        PageDriver driver = open("/plain");
        DomAnchor exit = anchor(driver, "/plain/install");

        byte @Nullable [] outlined = driver.screenshot(exit.selector());
        byte @Nullable [] plain = driver.screenshot(null);

        assertThat(outlined).isNotNull().startsWith(PNG);
        assertThat(plain).isNotNull().startsWith(PNG);
        assertThat(outlined).isNotEqualTo(plain);
        assertThat(driver.screenshot("#nothing-like-this")).as("the page without an outline").isEqualTo(plain);
    }

    @Test
    void givesUpOnAScreenshotOfAPageThatStoppedAnswering() throws InterruptedException {
        long openedAt = System.nanoTime();
        PageDriver driver = open("/stuck");
        Thread.sleep(Math.max(0, 5500 - Duration.ofNanos(System.nanoTime() - openedAt).toMillis()));

        long startedAt = System.nanoTime();
        driver.screenshot("#exit");
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took).isLessThan(BrowserFixture.settings(site).timeouts().operation().multipliedBy(3));
    }

    @Test
    void refusesToBeDrivenFromAnotherThread() throws Exception {
        PageDriver driver = open("/plain");
        AtomicReference<Throwable> refused = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                driver.anchors();
            } catch (Throwable e) {
                refused.set(e);
            }
        });

        other.start();
        other.join();

        assertThat(refused.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be used from");
    }

    private PageDriver driver() {
        session = worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM));
        return session.page();
    }

    private PageDriver open(String path) {
        PageDriver driver = driver();
        driver.open(URI.create(site.url(path)));
        return driver;
    }

    private static List<String> exitHrefs(PageDriver driver) {
        return driver.anchors().stream().map(found -> String.valueOf(found.href()))
                .filter(href -> site.patterns().isExit(href))
                .toList();
    }

    private static DomAnchor anchor(PageDriver driver, String part) {
        return driver.anchors().stream()
                .filter(found -> String.valueOf(found.href()).contains(part))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no link containing " + part + " on this page"));
    }

    private static String reached(ClickObservation observation) {
        assertThat(observation).isInstanceOf(ClickObservation.Navigated.class);
        return String.valueOf(((ClickObservation.Navigated) observation).url());
    }
}
