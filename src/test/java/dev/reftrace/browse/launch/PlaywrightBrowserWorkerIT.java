package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserIdentity;
import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.BrowserStartException;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Expectation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightBrowserWorkerIT {

    @Test
    void canBeDestroyedFromAnotherThreadAndSaysSoAfterwards() throws Exception {
        try (FixtureSite site = FixtureSite.start()) {
            BrowserSettings settings = BrowserFixture.settings(site);
            PlaywrightBrowserWorker worker = new PlaywrightBrowserWorker(settings,
                    new PageScripts(settings.urls(), settings.unwalkedBlocks()), ObservationRegistry.NOOP);
            PageDriver driver = worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM)).page();
            driver.open(URI.create(site.url("/plain")));

            Thread watchdog = new Thread(worker::forceKill, "watchdog");
            watchdog.start();
            watchdog.join();

            assertThatThrownBy(driver::anchors)
                    .isInstanceOf(PageCheckException.class)
                    .extracting(thrown -> ((PageCheckException) thrown).error().kind())
                    .isEqualTo(UntestedReason.BROWSER_CRASH);
            assertThatThrownBy(() -> worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM)))
                    .isInstanceOfSatisfying(BrowserStartException.class, failed ->
                            assertThat(failed).hasMessageContaining("destroyed by the watchdog"));
        }
    }

    @Test
    void saysWhichContextTheBrowserRefused() {
        try (FixtureSite site = FixtureSite.start()) {
            BrowserSettings settings = BrowserFixture.settings(site);
            try (PlaywrightBrowserWorker worker = new PlaywrightBrowserWorker(settings,
                    new PageScripts(settings.urls(), settings.unwalkedBlocks()), ObservationRegistry.NOOP)) {
                DeviceProfile desktop = BrowserFixture.desktop(BrowserEngine.CHROMIUM);
                DeviceProfile refused = new DeviceProfile("refused", BrowserEngine.CHROMIUM,
                        new BrowserIdentity("Mozilla/5.0\nX-Injected: yes", null), desktop.emulation());

                assertThatThrownBy(() -> worker.openSession(refused))
                        .isInstanceOf(BrowserStartException.class)
                        .hasMessageStartingWith("could not prepare a new chromium context for profile refused: ");
                try (BrowserSession next = worker.openSession(desktop)) {
                    next.page().open(URI.create(site.url("/plain")));
                }
            }
        }
    }

    @Test
    void givesEverySessionAContextOfItsOwn() {
        try (FixtureSite site = FixtureSite.start()) {
            BrowserSettings settings = BrowserFixture.settings(site);
            try (PlaywrightBrowserWorker worker = new PlaywrightBrowserWorker(settings,
                    new PageScripts(settings.urls(), settings.unwalkedBlocks()), ObservationRegistry.NOOP)) {
                Expectation.Cookie cookie = new Expectation.Cookie("ref");
                Expectation.LocalStorage localStorage = new Expectation.LocalStorage("ref");
                try (BrowserSession first = worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM))) {
                    first.page().open(URI.create(site.url("/stores?r=Fr3shCtx1")));
                    assertThat(first.page().stored(cookie)).isEqualTo("Fr3shCtx1");
                    assertThat(first.page().stored(localStorage)).isEqualTo("Fr3shCtx1");
                }
                try (BrowserSession second = worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM))) {
                    second.page().open(URI.create(site.url("/plain")));
                    assertThat(second.page().stored(cookie)).isNull();
                    assertThat(second.page().stored(localStorage)).isNull();
                }
            }
        }
    }

    @Test
    void writesTheTraceOfAVisitOnlyWhenAskedTo(@TempDir Path directory) {
        try (FixtureSite site = FixtureSite.start()) {
            BrowserSettings settings = BrowserFixture.settings(site);
            try (PlaywrightBrowserWorker worker = new PlaywrightBrowserWorker(settings,
                    new PageScripts(settings.urls(), settings.unwalkedBlocks()), ObservationRegistry.NOOP);
                 BrowserSession session = worker.openSession(BrowserFixture.desktop(BrowserEngine.CHROMIUM))) {
                session.page().open(URI.create(site.url("/plain")));
                assertThat(session.endTrace(null)).isFalse();

                session.page().open(URI.create(site.url("/storelinks")));
                Path file = directory.resolve("traces").resolve("001-desktop--storelinks--entry.zip");

                assertThat(session.endTrace(file)).isTrue();
                assertThat(file).isNotEmptyFile();
                assertThat(directory.resolve("traces")).isDirectoryContaining(path -> path.equals(file));
            }
        }
    }
}
