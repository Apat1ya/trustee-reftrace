package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.browse.scan.Origin;
import dev.reftrace.browse.scan.PageScanner;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Clicks;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class SiteInterfaceIT {

    private static final String KEY = "Adp7Fixture5";

    private static FixtureSite site;
    private static PlaywrightBrowserWorker worker;
    private static PlaywrightBrowserWorker reachableWorker;

    private final List<BrowserSession> sessions = new ArrayList<>();

    @BeforeAll
    static void startEverything() {
        site = FixtureSite.start();
        BrowserSettings settings = BrowserFixture.shipped(site, Clicks.ALL);
        worker = new PlaywrightBrowserWorker(settings, new PageScripts(settings.urls(), settings.unwalkedBlocks()),
                ObservationRegistry.NOOP);
        BrowserSettings reachable = BrowserFixture.shipped(site, Clicks.REACHABLE);
        reachableWorker = new PlaywrightBrowserWorker(reachable,
                new PageScripts(reachable.urls(), reachable.unwalkedBlocks()), ObservationRegistry.NOOP);
    }

    @AfterAll
    static void stopEverything() {
        worker.close();
        reachableWorker.close();
        site.close();
    }

    @AfterEach
    void closeSessions() {
        sessions.forEach(BrowserSession::close);
        sessions.clear();
    }

    @ParameterizedTest
    @CsvSource({"CHROMIUM, menu", "CHROMIUM, submenu", "WEBKIT, menu", "WEBKIT, submenu"})
    void opensTheMenuALinkSitsInBeforeClickingIt(BrowserEngine engine, String from) {
        PageDriver driver = phone(reachableWorker, engine, "/burger?r=" + KEY);
        FoundLink link = new PageScanner(site.routes(), true, ObservationRegistry.NOOP)
                .scan(driver, new Origin.Entered()).links().stream()
                .filter(found -> String.valueOf(found.url().getQuery()).endsWith("from=" + from))
                .findFirst()
                .orElseThrow();

        Navigation navigation = driver.follow(link);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.Landed.class, landed -> assertThat(
                landed.load().finalUrl()).isEqualTo(URI.create(site.url("/plain?r=" + KEY + "&from=" + from))));
    }

    @ParameterizedTest
    @CsvSource({"CHROMIUM", "WEBKIT"})
    void closesTheCardOrderFormAnAddressOpenedAndChecksThePageUnderIt(BrowserEngine engine) {
        PageDriver driver = phone(worker, engine, "/cardform?physical-card-form=open&r=" + KEY);

        PageScan scan = new PageScanner(site.routes(), true, ObservationRegistry.NOOP)
                .scan(driver, new Origin.Entered());

        assertThat(scan.untested()).isEmpty();
        assertThat(scan.links()).extracting(link -> link.url().getPath())
                .contains("/go/" + KEY + "/card/install", "/app");
        assertThat(site.counted()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"CHROMIUM", "WEBKIT"})
    void marksTheLinksOfTheLanguageSwitcherAsUnwalked(BrowserEngine engine) {
        PageDriver driver = phone(worker, engine, "/languages?r=" + KEY);

        PageScan scan = new PageScanner(site.routes(), true, ObservationRegistry.NOOP)
                .scan(driver, new Origin.Entered());

        assertThat(scan.links()).filteredOn(link -> link.url().getPath().equals("/plain"))
                .extracting(FoundLink::selector, FoundLink::unwalked)
                .containsExactlyInAnyOrder(tuple("#read-on", false), tuple("#modal-en", true),
                        tuple("#footer-en", true));
        assertThat(scan.links()).filteredOn(link -> link.url().getPath().equals("/languages"))
                .extracting(FoundLink::selector, FoundLink::unwalked)
                .containsExactlyInAnyOrder(tuple("#modal-uk", true), tuple("#footer-ru", true));
        assertThat(scan.links()).filteredOn(link -> "/app".equals(link.url().getPath()))
                .isNotEmpty().noneMatch(FoundLink::unwalked);
    }

    @ParameterizedTest
    @CsvSource({"CHROMIUM", "WEBKIT"})
    void neverStandsInALinkOfTheLanguageSwitcherForALinkThatIsGone(BrowserEngine engine) {
        PageDriver driver = phone(worker, engine, "/languages?r=" + KEY);
        FoundLink gone = new FoundLink(URI.create(site.url("/languages")), "main > a.gone", "UK Українська",
                How.HREF, true, false);

        Navigation navigation = driver.follow(gone);

        assertThat(navigation).isInstanceOfSatisfying(Navigation.NotFollowed.class, notFollowed -> assertThat(
                notFollowed.untested().reason()).isEqualTo(UntestedReason.LINK_NOT_FOUND));
    }

    private PageDriver phone(PlaywrightBrowserWorker from, BrowserEngine engine, String path) {
        BrowserSession session = from.openSession(BrowserFixture.phone(engine));
        sessions.add(session);
        PageDriver driver = session.page();
        driver.open(URI.create(site.url(path)));
        return driver;
    }
}
