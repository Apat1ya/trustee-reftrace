package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.config.BrowserEngine;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebKitPagesIT {

    private static final String KEY = "Adp7Fixture2";

    private static FixtureSite site;
    private static PlaywrightBrowserWorker worker;
    private static PageDriver driver;

    @BeforeAll
    static void startEverything() {
        site = FixtureSite.start();
        BrowserSettings settings = BrowserFixture.settings(site);
        worker = new PlaywrightBrowserWorker(settings, new PageScripts(settings.urls(), settings.unwalkedBlocks()),
                ObservationRegistry.NOOP);
        driver = worker.openSession(BrowserFixture.phone(BrowserEngine.WEBKIT)).page();
        driver.open(URI.create(site.url("/home?r=" + KEY)));
    }

    @AfterAll
    static void stopEverything() {
        worker.close();
        site.close();
    }

    @Test
    void findsTheSameExitsAndOpensTheSameInterface() {
        assertThat(anchor("/drawer/install").visible()).isTrue();
        assertThat(anchor("/code/install").visible()).isTrue();
        assertThat(driver.anchors()).noneMatch(found -> String.valueOf(found.href()).contains("/store/install"));
    }

    @Test
    void tidiesUpAfterAnExitThatOpensItsOwnTab() {
        DomAnchor apk = driver.anchors().stream().filter(found -> String.valueOf(found.href()).endsWith(".apk"))
                .findFirst().orElseThrow();

        ClickObservation observation = driver.clickExit(apk.locator(), String.valueOf(apk.href()));
        ClickObservation next = clickTheDrawerExit();

        assertThat(observation).isInstanceOf(ClickObservation.Navigated.class);
        assertThat(next).isInstanceOf(ClickObservation.Navigated.class);
    }

    @Test
    void readsTheQrCodeBelowTheFoldPastASpinningPicture() {
        try (BrowserSession phone = worker.openSession(BrowserFixture.phone(BrowserEngine.WEBKIT))) {
            PageDriver page = phone.page();
            page.open(URI.create(site.url("/qrspinning")));

            assertThat(page.qrCodes()).singleElement().isInstanceOfSatisfying(QrReading.Decoded.class,
                    decoded -> assertThat(decoded.text()).isEqualTo(FixtureSite.QR_SHOWN));
        }
    }

    @Test
    void photographsAPageWhoseFontsNeverFinishLoading() {
        BrowserSession other = worker.openSession(BrowserFixture.phone(BrowserEngine.WEBKIT));
        try {
            PageDriver stuck = other.page();
            stuck.open(URI.create(site.url("/fontstuck?r=" + KEY)));

            long startedAt = System.nanoTime();
            byte @Nullable [] picture = stuck.screenshot(null);
            Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(picture).isNotNull().isNotEmpty();
            assertThat(took).isLessThan(BrowserFixture.settings(site).timeouts().operation());
        } finally {
            other.close();
        }
    }

    private static ClickObservation clickTheDrawerExit() {
        DomAnchor drawer = anchor("/drawer/install");
        return driver.clickExit(drawer.locator(), String.valueOf(drawer.href()));
    }

    private static DomAnchor anchor(String part) {
        return driver.anchors().stream()
                .filter(found -> String.valueOf(found.href()).contains(part))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no link containing " + part + " on this page"));
    }
}
