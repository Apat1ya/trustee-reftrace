package dev.reftrace.browse.scan;

import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.HiddenQrCode;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class PageScannerTest {

    private static final String ORIGIN = "https://site.test";
    private static final String STORE = "https://apps.apple.com/app/trustee";

    private static final Routes ROUTES = new Routes(List.of(
            route("site.test", true),
            route("*.app.link", false),
            route("apps.apple.com", false)));
    private static final Routes STORING = new Routes(List.of(
            new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of(
                    new ExpectEntry(null, null, null, "ref", null, false),
                    new ExpectEntry(null, null, null, null, "ref", false))),
            route("*.app.link", false)));
    private static final FoundLink CARDS =
            new FoundLink(URI.create(ORIGIN + "/cards/"), "a.cards", "Cards", How.HREF, true, false);

    @Test
    void asksForNoQrCodeWithQrCheckOff() {
        RecordingPage page = pageWithEveryKindOfQrCode();

        PageScan scan = new PageScanner(ROUTES, false, ObservationRegistry.NOOP).scan(page, new Origin.Entered());

        assertThat(page.calls).containsExactly("lastLoad", "anchors", "click a#0");
        assertThat(scan.links()).extracting(FoundLink::url, FoundLink::how).containsExactly(
                tuple(URI.create(STORE), How.HREF));
        assertThat(scan.untested()).isEmpty();
    }

    private static RecordingPage pageWithEveryKindOfQrCode() {
        return new RecordingPage(new PageLoad(URI.create(ORIGIN + "/?r=K1"), URI.create(ORIGIN + "/?r=K1"), null))
                .anchor(STORE, true)
                .qr(new QrReading.Decoded("qr#0", "svg 200px", "https://trusteeplus.app.link/K1", "svg.one"))
                .qr(new QrReading.Unreadable("qr#1", "svg 200px", "svg.two"))
                .hiddenQr(new HiddenQrCode("svg.three", "svg 200px"));
    }

    @Test
    void asksAnEnteredPageForNothingItStores() {
        RecordingPage page = new RecordingPage(new PageLoad(URI.create(ORIGIN + "/"), URI.create(ORIGIN + "/"), null))
                .stores(new Expectation.Cookie("ref"), "K1");

        PageScan scan = new PageScanner(STORING, true, ObservationRegistry.NOOP).scan(page, new Origin.Entered());

        assertThat(page.calls).containsExactly("lastLoad", "anchors", "qrCodes", "hiddenQrCodes");
        assertThat(scan.stored()).isEmpty();
    }

    @Test
    void aBrowserThatCrashedWhileAStoredValueWasReadFailsThePage() {
        RecordingPage page = new RecordingPage(new PageLoad(URI.create(ORIGIN + "/"), URI.create(ORIGIN + "/"), null))
                .cannotRead(new Expectation.Cookie("ref"), UntestedReason.BROWSER_CRASH, "gone");

        assertThatThrownBy(() -> new PageScanner(STORING, true, ObservationRegistry.NOOP)
                .scan(page, new Origin.Followed(CARDS)))
                .isInstanceOfSatisfying(PageCheckException.class,
                        failed -> assertThat(failed.error().kind()).isEqualTo(UntestedReason.BROWSER_CRASH));
    }

    @Test
    void asksThePageInAFixedOrderAndPutsItBackOnTheFinalAddressOfTheFirstLoad() {
        RecordingPage page = new RecordingPage(new PageLoad(URI.create(ORIGIN + "/old/?r=K1"),
                URI.create(ORIGIN + "/new/"), null))
                .anchor(ORIGIN + "/cards/", true)
                .anchor("https://trusteeplus.app.link/one", true)
                .anchor("https://trusteeplus.app.link/two", false)
                .anchor("https://trusteeplus.app.link/three", true)
                .anchor("https://unlisted.test/elsewhere", true)
                .clicks("a#1", ClickObservation.navigated("https://trusteeplus.app.link/K1", false))
                .clicks("a#3", new ClickObservation.NoNavigation(Duration.ofMillis(900)))
                .qr(new QrReading.Decoded("qr#0", "svg 200px", "https://trusteeplus.app.link/K1", "svg.one"))
                .qr(new QrReading.Unreadable("qr#1", "svg 200px", "svg.two"))
                .hiddenQr(new HiddenQrCode("svg.three", "svg 200px"))
                .stores(new Expectation.Cookie("ref"), "K1")
                .cannotRead(new Expectation.LocalStorage("ref"), UntestedReason.INTERNAL, "no storage");

        PageScan scan = new PageScanner(STORING, true, ObservationRegistry.NOOP).scan(page, new Origin.Followed(CARDS));

        assertThat(page.calls).containsExactly("lastLoad", "stored cookie ref", "stored localStorage ref",
                "anchors", "qrCodes", "hiddenQrCodes", "click a#1", "open " + ORIGIN + "/new/", "click a#2",
                "click a#3");
        assertThat(scan.landedUrl()).isEqualTo(URI.create(ORIGIN + "/new/"));
        assertThat(scan.links()).extracting(FoundLink::url, FoundLink::how, FoundLink::visible).containsExactly(
                tuple(URI.create(ORIGIN + "/cards/"), How.HREF, true),
                tuple(URI.create("https://trusteeplus.app.link/one"), How.HREF, true),
                tuple(URI.create("https://trusteeplus.app.link/two"), How.HREF, false),
                tuple(URI.create("https://trusteeplus.app.link/three"), How.HREF, true),
                tuple(URI.create("https://trusteeplus.app.link/K1"), How.QR, true),
                tuple(URI.create("https://trusteeplus.app.link/K1"), How.CLICK, true));
        assertThat(scan.stored())
                .containsExactly(new StoredValue(CARDS, "site.test", new Expectation.Cookie("ref"), "K1"));
        assertThat(scan.untested()).extracting(Untested::reason, Untested::selector).containsExactly(
                tuple(UntestedReason.INTERNAL, null),
                tuple(UntestedReason.QR_UNREADABLE, "svg.two"),
                tuple(UntestedReason.QR_HIDDEN, "svg.three"),
                tuple(UntestedReason.CLICK_NO_NAVIGATION, "a:nth-of-type(4)"));
    }

    @Test
    void takesAClickThatReachedItsOwnHrefWithoutTheFragmentForNoLinkOfItsOwn() {
        String href = "https://trusteeplus.app.link/K1?r=a%20b";
        RecordingPage page = new RecordingPage(new PageLoad(URI.create(ORIGIN + "/"), URI.create(ORIGIN + "/"), null))
                .anchor(href, true)
                .anchor(href + "#top", true)
                .anchor("https://trusteeplus.app.link/K2", true)
                .anchor("https://trusteeplus.app.link/K3#", true)
                .clicks("a#0", ClickObservation.navigated(href + "#reached", true))
                .clicks("a#1", ClickObservation.navigated(href, true))
                .clicks("a#2", ClickObservation.navigated("https://trusteeplus.app.link/K2#", true))
                .clicks("a#3", ClickObservation.navigated("https://trusteeplus.app.link/%4B3", true));

        PageScan scan = new PageScanner(ROUTES, true, ObservationRegistry.NOOP).scan(page, new Origin.Entered());

        assertThat(scan.links()).filteredOn(link -> link.how() == How.CLICK).extracting(FoundLink::url)
                .containsExactly(URI.create("https://trusteeplus.app.link/%4B3"));
    }

    private static Route route(String match, boolean follow) {
        return new Route(match, List.of(Fixtures.match(match)), follow, List.of());
    }

    private static final class RecordingPage implements PageDriver {

        private final List<String> calls = new ArrayList<>();
        private final List<DomAnchor> anchors = new ArrayList<>();
        private final Map<String, ClickObservation> clicks = new HashMap<>();
        private final List<QrReading> qrCodes = new ArrayList<>();
        private final List<HiddenQrCode> hiddenQrCodes = new ArrayList<>();
        private final Map<Expectation.OnArrival, String> stored = new HashMap<>();
        private final Map<Expectation.OnArrival, TechnicalError> unreadable = new HashMap<>();
        private final PageLoad load;

        RecordingPage(PageLoad load) {
            this.load = load;
        }

        RecordingPage anchor(String href, boolean visible) {
            int index = anchors.size();
            anchors.add(DomAnchor.of("a#" + index, href, "link " + index, visible,
                    "a:nth-of-type(" + (index + 1) + ")", false));
            return this;
        }

        RecordingPage clicks(String locator, ClickObservation observation) {
            clicks.put(locator, observation);
            return this;
        }

        RecordingPage qr(QrReading reading) {
            qrCodes.add(reading);
            return this;
        }

        RecordingPage hiddenQr(HiddenQrCode hidden) {
            hiddenQrCodes.add(hidden);
            return this;
        }

        RecordingPage stores(Expectation.OnArrival where, String value) {
            stored.put(where, value);
            return this;
        }

        RecordingPage cannotRead(Expectation.OnArrival where, UntestedReason kind, String message) {
            unreadable.put(where, new TechnicalError(kind, message));
            return this;
        }

        @Override
        public PageLoad open(URI url) {
            calls.add("open " + url);
            return new PageLoad(url, url, null);
        }

        @Override
        public PageLoad reload() {
            throw new UnsupportedOperationException("a scan never reloads");
        }

        @Override
        public Navigation follow(FoundLink link) {
            throw new UnsupportedOperationException("a scan never follows a link");
        }

        @Override
        public PageLoad lastLoad() {
            calls.add("lastLoad");
            return load;
        }

        @Override
        public URI currentUrl() {
            return load.finalUrl();
        }

        @Override
        public List<DomAnchor> anchors() {
            calls.add("anchors");
            return List.copyOf(anchors);
        }

        @Override
        public List<QrReading> qrCodes() {
            calls.add("qrCodes");
            return List.copyOf(qrCodes);
        }

        @Override
        public List<HiddenQrCode> hiddenQrCodes() {
            calls.add("hiddenQrCodes");
            return List.copyOf(hiddenQrCodes);
        }

        @Override
        public ClickObservation clickExit(String locator, String expectedHref) {
            calls.add("click " + locator);
            ClickObservation observation = clicks.get(locator);
            return observation == null ? ClickObservation.navigated(expectedHref, true) : observation;
        }

        @Override
        public byte @Nullable [] screenshot(@Nullable String selector) {
            return null;
        }

        @Override
        public @Nullable String stored(Expectation.OnArrival expectation) {
            calls.add("stored " + switch (expectation) {
                case Expectation.Cookie(String name) -> "cookie " + name;
                case Expectation.LocalStorage(String name) -> "localStorage " + name;
            });
            TechnicalError failure = unreadable.get(expectation);
            if (failure != null) {
                throw PageCheckException.of(failure);
            }
            return stored.get(expectation);
        }
    }
}
