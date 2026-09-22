package dev.reftrace.testsupport.fakebrowser;

import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.HiddenQrCode;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.Untested;
import dev.reftrace.config.Expectation;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScriptedPageDriver implements PageDriver {

    private static final Pattern KEY_PARAMETER = Pattern.compile("[?&](?:r|refplus|referrals)=([A-Za-z0-9]+)");

    private final ScriptedSite site;
    private final ScriptedBrowserWorker worker;

    private @Nullable URI currentUrl;
    private @Nullable String currentPath;
    private @Nullable String currentKey;
    private @Nullable PageLoad lastLoad;
    private List<DomAnchor> anchors = List.of();

    ScriptedPageDriver(ScriptedSite site, ScriptedBrowserWorker worker) {
        this.site = site;
        this.worker = worker;
    }

    @Override
    public PageLoad open(URI url) {
        worker.requireOwnThread();
        worker.requireAlive();
        return arrive(url, url);
    }

    private PageLoad arrive(URI requested, URI url) {
        String path = ScriptedSite.pathOf(url);
        site.recordOpen(path);
        if (site.hangs(path)) {
            worker.awaitKill();
            throw PageCheckException.of(new TechnicalError(UntestedReason.BROWSER_CRASH,
                    "the browser was killed while it was loading " + url));
        }
        TechnicalError scripted = site.nextFailure(path);
        if (scripted != null) {
            throw PageCheckException.of(scripted);
        }
        URI landed = site.redirected(url).orElse(url);
        String landedPath = ScriptedSite.pathOf(landed);
        if (!site.knows(landedPath)) {
            throw PageCheckException.of(new TechnicalError(UntestedReason.NAVIGATION_ERROR,
                    "there is no page at " + landed));
        }
        show(landed, landedPath);
        PageLoad load = new PageLoad(requested, landed, site.statusOf(landedPath));
        lastLoad = load;
        return load;
    }

    private void show(URI url, String path) {
        currentUrl = url;
        currentPath = path;
        currentKey = keyOf(url);
        anchors = render(path);
    }

    @Override
    public PageLoad reload() {
        worker.requireOwnThread();
        worker.requireAlive();
        URI url = openedUrl();
        return arrive(url, url);
    }

    @Override
    public PageLoad lastLoad() {
        PageLoad load = lastLoad;
        if (load == null) {
            throw new IllegalStateException("no page has been loaded in this tab yet");
        }
        return load;
    }

    @Override
    public URI currentUrl() {
        return openedUrl();
    }

    private URI openedUrl() {
        URI url = currentUrl;
        if (url == null) {
            throw new IllegalStateException("no page has been opened in this tab yet");
        }
        return url;
    }

    @Override
    public List<DomAnchor> anchors() {
        worker.requireOwnThread();
        worker.requireAlive();
        return anchors;
    }

    @Override
    public List<QrReading> qrCodes() {
        worker.requireOwnThread();
        worker.requireAlive();
        return List.of();
    }

    @Override
    public @Nullable String stored(Expectation.OnArrival expectation) {
        worker.requireOwnThread();
        worker.requireAlive();
        String value = site.stored(Objects.requireNonNull(currentPath), expectation);
        return value == null ? null : substitute(value, currentKey);
    }

    @Override
    public List<HiddenQrCode> hiddenQrCodes() {
        worker.requireOwnThread();
        worker.requireAlive();
        List<HiddenQrCode> hidden = new ArrayList<>();
        for (int index = 0; index < site.hiddenQrCodes(Objects.requireNonNull(currentPath)); index++) {
            hidden.add(new HiddenQrCode(qrSelector(index), "qr code"));
        }
        return List.copyOf(hidden);
    }

    @Override
    public byte @Nullable [] screenshot(@Nullable String selector) {
        worker.requireOwnThread();
        site.recordScreenshot(selector);
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    }

    @Override
    public ClickObservation clickExit(String locator, String expectedHref) {
        worker.requireOwnThread();
        worker.requireAlive();
        ClickObservation observation = anchors.stream()
                .filter(anchor -> anchor.locator().equals(locator))
                .findFirst()
                .map(anchor -> expectedHref.equals(String.valueOf(anchor.href()))
                        ? ClickObservation.navigated(expectedHref, true)
                        : new ClickObservation.Failed(new TechnicalError(UntestedReason.DOM_CHANGED,
                                "the link at " + locator + " now points to " + anchor.href()), true))
                .orElseGet(() -> new ClickObservation.Failed(new TechnicalError(UntestedReason.LINK_NOT_FOUND,
                        "there is no link at " + locator), true));
        followIfInternal(observation);
        return observation;
    }

    @Override
    public Navigation follow(FoundLink link) {
        worker.requireOwnThread();
        worker.requireAlive();
        if (link.how() == How.QR) {
            return new Navigation.Landed(open(link.url()));
        }
        Optional<DomAnchor> anchor = relocate(link);
        if (anchor.isEmpty()) {
            return notFollowed(UntestedReason.LINK_NOT_FOUND, link, "nothing on " + openedUrl() + " is "
                    + link.selector() + " or leads to " + link.url());
        }
        URI href = Objects.requireNonNull(anchor.get().href());
        return internal(href)
                ? new Navigation.Landed(arrive(link.url(), href))
                : notFollowed(UntestedReason.CLICK_FAILED, link, "the click was stopped on its way to " + href);
    }

    private Optional<DomAnchor> relocate(FoundLink link) {
        Optional<DomAnchor> bySelector = anchors.stream()
                .filter(anchor -> anchor.selector().equals(link.selector()))
                .filter(anchor -> link.how() != How.HREF || link.url().equals(anchor.href()))
                .findFirst();
        if (bySelector.isPresent() || link.how() != How.HREF) {
            return bySelector;
        }
        return anchors.stream()
                .filter(anchor -> anchor.visible() && link.url().equals(anchor.href())
                        && link.text().equals(anchor.label()))
                .findFirst();
    }

    private static Navigation notFollowed(UntestedReason reason, FoundLink link, String detail) {
        return new Navigation.NotFollowed(new Untested.Issue(reason, link.selector(), detail));
    }

    private boolean internal(URI target) {
        return target.getHost() != null && target.getHost().equals(openedUrl().getHost());
    }

    private void followIfInternal(ClickObservation observation) {
        if (!(observation instanceof ClickObservation.Navigated(URI target, _)) || !internal(target)) {
            return;
        }
        String path = ScriptedSite.pathOf(target);
        if (site.knows(path)) {
            site.recordOpen(path);
            show(target, path);
        }
    }

    private List<DomAnchor> render(String path) {
        @Nullable String key = currentKey;
        List<DomAnchor> rendered = new ArrayList<>();
        int index = 0;
        for (String link : site.internalLinks(path)) {
            String href = openedUrl().resolve(substitute(link, key)).toString();
            rendered.add(DomAnchor.of("a#" + index, href, "go to " + link, true, anchorSelector(index), false));
            index++;
        }
        for (String exit : site.exitHrefs(path)) {
            String href = substitute(exit, key);
            boolean visible = !href.contains("#hidden");
            rendered.add(DomAnchor.of("a#" + index, href.replace("#hidden", ""), "Get the app", visible,
                    anchorSelector(index), false));
            index++;
        }
        return List.copyOf(rendered);
    }

    private static String anchorSelector(int index) {
        return "a:nth-of-type(" + (index + 1) + ")";
    }

    private static String qrSelector(int index) {
        return "svg.qr:nth-of-type(" + (index + 1) + ")";
    }

    private static String substitute(String template, @Nullable String key) {
        return template.replace(ScriptedSite.KEY, key == null ? "" : key);
    }

    private static @Nullable String keyOf(URI url) {
        Matcher matcher = KEY_PARAMETER.matcher(url.toString());
        return matcher.find() ? matcher.group(1) : null;
    }
}
