package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.HiddenQrCode;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Clicks;
import dev.reftrace.config.Expectation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class PlaywrightPageDriver implements PageDriver {

    private final DriverThread thread;
    private final PageProbe probe;
    private final PageNavigation navigation;
    private final ExitClicker exits;
    private final QrScanner qrScanner;
    private final PageStorageReader storageReader;
    private final ScreenshotCapturer screenshots;
    private final ObservationRegistry observations;

    private Map<String, String> selectors = Map.of();

    private PlaywrightPageDriver(DriverThread thread, PageProbe probe, PageNavigation navigation,
                                 ExitClicker exits, QrScanner qrScanner, PageStorageReader storageReader,
                                 ScreenshotCapturer screenshots, ObservationRegistry observations) {
        this.thread = thread;
        this.probe = probe;
        this.navigation = navigation;
        this.exits = exits;
        this.qrScanner = qrScanner;
        this.storageReader = storageReader;
        this.screenshots = screenshots;
        this.observations = observations;
    }

    public static PlaywrightPageDriver attach(DriverThread thread, BrowserContext context, Page page,
                                              GuardObservations guard, PageScripts scripts,
                                              BrowserTimeouts timeouts, RevealPlan reveal, Clicks clicks,
                                              ObservationRegistry observations) {
        PageProbe probe = new PageProbe(page, scripts);
        ExitRevealer revealer = new ExitRevealer(reveal, observations);
        AtomicInteger mainFrameNavigations = new AtomicInteger();
        PageStabilizer stabilizer = new PageStabilizer(thread, page, context, probe, timeouts, revealer,
                mainFrameNavigations::get, observations);
        HiddenLinkUncoverer uncoverer = new HiddenLinkUncoverer(thread, page, probe, revealer, timeouts,
                observations);
        LinkClick linkClick = new LinkClick(thread, probe, uncoverer, timeouts, clicks);
        PageNavigation navigation = new PageNavigation(thread, context, page, guard, timeouts, probe, stabilizer,
                linkClick, mainFrameNavigations, observations);
        ExitClicker exits = new ExitClicker(thread, context, page, guard, timeouts, stabilizer, linkClick);
        return new PlaywrightPageDriver(thread, probe, navigation, exits,
                new QrScanner(thread, page, probe, timeouts),
                new PageStorageReader(thread, context, page),
                new ScreenshotCapturer(page, probe, timeouts), observations);
    }

    @Override
    public PageLoad open(URI url) {
        thread.requireOwnerThread();
        return navigation.open(url);
    }

    @Override
    public PageLoad lastLoad() {
        return navigation.lastLoad();
    }

    @Override
    public Navigation follow(FoundLink link) {
        thread.requireOwnerThread();
        return navigation.click(link);
    }

    @Override
    public PageLoad reload() {
        thread.requireOwnerThread();
        return navigation.reload();
    }

    @Override
    public URI currentUrl() {
        return navigation.currentUrl();
    }

    @Override
    public List<DomAnchor> anchors() {
        thread.requireOwnerThread();
        return stage("page.anchors").observe(() -> {
            try {
                probe.ensureInstalled();
                List<DomAnchor> anchors = probe.anchors();
                selectors = anchors.stream().collect(Collectors.toMap(DomAnchor::locator, DomAnchor::selector,
                        (first, _) -> first));
                return anchors;
            } catch (RuntimeException e) {
                throw thread.translate(e, UntestedReason.INTERNAL);
            }
        });
    }

    @Override
    public @Nullable String stored(Expectation.OnArrival expectation) {
        thread.requireOwnerThread();
        return stage("page.storage").observe(() -> storageReader.stored(expectation));
    }

    @Override
    public ClickObservation clickExit(String locator, String expectedHref) {
        thread.requireOwnerThread();
        Observation stage = stage("page.exit-click").highCardinalityKeyValue("reftrace.href", expectedHref)
                .highCardinalityKeyValue("reftrace.selector", selectors.getOrDefault(locator, locator));
        WaitOutcome wait = new WaitOutcome(stage);
        return stage.observe(() -> {
            ClickObservation observed = exits.click(locator, expectedHref, wait);
            stage.lowCardinalityKeyValue("reftrace.click", switch (observed) {
                case ClickObservation.Navigated _ -> "navigated";
                case ClickObservation.NoNavigation _ -> "no-navigation";
                case ClickObservation.NotReachable _ -> "not-reachable";
                case ClickObservation.Failed _ -> "failed";
            });
            return observed;
        });
    }

    @Override
    public List<QrReading> qrCodes() {
        thread.requireOwnerThread();
        return stage("page.qr").observe(qrScanner::qrCodes);
    }

    @Override
    public List<HiddenQrCode> hiddenQrCodes() {
        thread.requireOwnerThread();
        return stage("page.qr-hidden").observe(qrScanner::hiddenQrCodes);
    }

    @Override
    public byte @Nullable [] screenshot(@Nullable String selector) {
        thread.requireOwnerThread();
        return stage("page.screenshot").observe(() -> screenshots.screenshot(selector));
    }

    private Observation stage(String name) {
        return Observation.createNotStarted(name, observations);
    }
}
