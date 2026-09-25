package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Navigation;
import dev.reftrace.browse.ObservedUrl;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class PageNavigation {

    private static final Logger log = LoggerFactory.getLogger(PageNavigation.class);

    private static final String BLOCKER = "reftrace.click.blocker";
    private static final String CLICKABLE = "none";
    private static final String CLICK = "reftrace.click";

    private final DriverThread thread;
    private final BrowserContext context;
    private final Page page;
    private final GuardObservations guard;
    private final BrowserTimeouts timeouts;
    private final PageProbe probe;
    private final PageStabilizer stabilizer;
    private final LinkClick linkClick;

    private final AtomicInteger mainFrameNavigations;

    private final ObservationRegistry observations;

    private volatile @Nullable Response lastDocument;

    private @Nullable PageLoad lastLoad;

    PageNavigation(DriverThread thread, BrowserContext context, Page page, GuardObservations guard,
                   BrowserTimeouts timeouts, PageProbe probe, PageStabilizer stabilizer,
                   LinkClick linkClick, AtomicInteger mainFrameNavigations,
                   ObservationRegistry observations) {
        this.thread = thread;
        this.context = context;
        this.page = page;
        this.guard = guard;
        this.timeouts = timeouts;
        this.probe = probe;
        this.stabilizer = stabilizer;
        this.linkClick = linkClick;
        this.mainFrameNavigations = mainFrameNavigations;
        this.observations = observations;
        page.onFrameNavigated(frame -> {
            if (frame.parentFrame() == null) {
                mainFrameNavigations.incrementAndGet();
            }
        });
        page.onResponse(response -> {
            if (isMainDocument(response)) {
                lastDocument = response;
            }
        });
    }

    PageLoad open(URI url) {
        mainFrameNavigations.set(0);
        Response response = navigate(url.toString());
        requireSettledUnlessError(response, url.toString());
        return loaded(new PageLoad(url, currentUrl(), status(response)));
    }

    PageLoad lastLoad() {
        PageLoad load = lastLoad;
        if (load == null) {
            throw new IllegalStateException("nothing has been loaded in this tab yet");
        }
        return load;
    }

    PageLoad reload() {
        URI requested = currentUrl();
        mainFrameNavigations.set(0);
        Response response;
        try {
            page.evaluate(PageScripts.NEXT_TASK);
            response = load("reload", requested.toString(), () -> page.reload(new Page.ReloadOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(Timeouts.millis(timeouts.pageLoad()))));
        } catch (RuntimeException e) {
            throw navigationFailure(e, requested.toString());
        }
        requireSettledUnlessError(response, requested.toString());
        return loaded(new PageLoad(requested, currentUrl(), status(response)));
    }

    URI currentUrl() {
        try {
            return URI.create(page.url());
        } catch (RuntimeException _) {
            return URI.create("about:blank");
        }
    }

    Navigation click(FoundLink link) {
        if (link.how() == How.QR) {
            PageLoad load = open(link.url());
            return new Navigation.Landed(load);
        }
        String selector = relocate(link);
        if (selector == null) {
            return notFollowed(UntestedReason.LINK_NOT_FOUND, link.selector(),
                    "nothing on " + page.url() + " is " + link.selector() + " or leads to " + link.url());
        }
        mainFrameNavigations.set(0);
        lastDocument = null;
        guard.reset();
        Locator element = page.locator(selector);
        linkClick.approach(element);
        String urlBefore = page.url();
        List<Page> pagesBefore = context.pages();
        Observation clicked = Observation.createNotStarted("page.click", observations)
                .highCardinalityKeyValue("reftrace.url", link.url().toString())
                .start();
        WaitOutcome wait = new WaitOutcome(clicked);
        ClickCapture.Reaction reaction;
        @Nullable ClickFailure clickError;
        try (Observation.Scope _ = clicked.openScope()) {
            Attempt attempt = clickIfReachable(element, wait);
            if (attempt instanceof Attempt.Unreachable(ClickFailure unreachable)) {
                tell(clicked, unreachable);
                clicked.lowCardinalityKeyValue(CLICK, "not-reachable");
                return notFollowed(UntestedReason.CLICK_FAILED, selector,
                        unreachable.detail(selector, timeouts.operation()));
            }
            clickError = attempt instanceof Attempt.Clicked(ClickFailure error) ? error : null;
            reaction = Observation.createNotStarted("page.click.reaction", observations).observe(() ->
                    ClickCapture.awaitFollowClick(page, context, guard, urlBefore, pagesBefore,
                            timeouts.clickNavigation(), () -> mainFrameNavigations.get() > 0));
            wait.ended("reaction", timeouts.clickNavigation(), reaction.timedOut());
            boolean went = reaction.hit() != null || !reaction.newPages().isEmpty() || reaction.urlChanged()
                    || mainFrameNavigations.get() > 0;
            if (went) {
                clicked.lowCardinalityKeyValue(BLOCKER, CLICKABLE).lowCardinalityKeyValue(CLICK, "navigated");
            } else if (clickError == null) {
                clicked.lowCardinalityKeyValue(BLOCKER, CLICKABLE).lowCardinalityKeyValue(CLICK, "no-navigation");
            } else {
                tell(clicked, clickError);
                clicked.lowCardinalityKeyValue(CLICK, "failed");
            }
        } catch (RuntimeException e) {
            clicked.error(e);
            throw e;
        } finally {
            clicked.stop();
        }
        String hit = reaction.hit();
        if (hit != null) {
            ClickCapture.close(reaction.newPages());
            return notFollowed(UntestedReason.CLICK_FAILED, selector, "the click was stopped on its way to " + hit);
        }
        if (!reaction.newPages().isEmpty()) {
            return followIntoThisTab(link, selector, reaction.newPages());
        }
        if (reaction.urlChanged() || mainFrameNavigations.get() > 0) {
            Response document = lastDocument;
            awaitDocument(link.url());
            HttpStatusCode status = status(document);
            if (status == null || !status.isError()) {
                stabilizer.requireSettled(page.url(),
                        document == null ? 1 : SettleWaiter.NAVIGATIONS_FOR_PHASE_TWO);
            }
            return new Navigation.Landed(loaded(new PageLoad(link.url(), currentUrl(), status(document))));
        }
        if (clickError != null) {
            return notFollowed(UntestedReason.CLICK_FAILED, selector,
                    clickError.detail(selector, timeouts.operation()));
        }
        return notFollowed(UntestedReason.CLICK_NO_NAVIGATION, selector,
                "nothing happened within " + reaction.waited().toMillis() + " ms of the click");
    }

    private sealed interface Attempt {

        record Unreachable(ClickFailure failure) implements Attempt {
        }

        record Clicked(@Nullable ClickFailure error) implements Attempt {
        }
    }

    private Attempt clickIfReachable(Locator element, WaitOutcome wait) {
        Observation actionable = Observation.createNotStarted("page.click.actionable", observations);
        return actionable.observe(() -> {
            Optional<ClickFailure> unreachable = linkClick.unreachable(element);
            if (unreachable.isPresent()) {
                actionable.lowCardinalityKeyValue(BLOCKER, unreachable.get().blocker().word());
                return new Attempt.Unreachable(unreachable.get());
            }
            actionable.lowCardinalityKeyValue(BLOCKER, CLICKABLE);
            RuntimeException error = linkClick.click(element, wait, "actionable");
            return new Attempt.Clicked(error == null ? null : ClickFailure.of(error));
        });
    }

    private static void tell(Observation clicked, ClickFailure failure) {
        clicked.lowCardinalityKeyValue(BLOCKER, failure.blocker().word());
        String interceptor = failure.interceptor();
        if (interceptor != null) {
            clicked.highCardinalityKeyValue("reftrace.click.interceptor", interceptor);
        }
        if (!failure.callLog().isEmpty()) {
            clicked.event(Observation.Event.of("call-log",
                    "call log:\n" + String.join("\n", failure.callLog())));
        }
    }

    private Navigation followIntoThisTab(FoundLink link, String selector, List<Page> opened) {
        Page popup = opened.getFirst();
        try {
            popup.waitForURL(url -> !url.isBlank() && !"about:blank".equals(url), new Page.WaitForURLOptions()
                    .setWaitUntil(WaitUntilState.COMMIT).setTimeout(Timeouts.millis(timeouts.pageLoad())));
        } catch (RuntimeException e) {
            log.debug("the tab {} opened never said where it was going: {}", selector,
                    PlaywrightErrors.message(e));
        }
        Optional<URI> destination = ClickCapture.destinationOf(popup).flatMap(ObservedUrl::parse);
        ClickCapture.close(opened);
        if (destination.isEmpty()) {
            return notFollowed(UntestedReason.CLICK_FAILED, selector,
                    "a new tab opened but never said where it was going");
        }
        PageLoad load = open(destination.get());
        return new Navigation.Landed(loaded(new PageLoad(link.url(), load.finalUrl(), load.httpStatus())));
    }

    private @Nullable String relocate(FoundLink link) {
        try {
            probe.ensureInstalled();
            return probe.relocate(link.selector(), link.how() == How.HREF ? link.url().toString() : "",
                    link.text());
        } catch (RuntimeException e) {
            throw thread.translate(e, UntestedReason.INTERNAL);
        }
    }

    private void awaitDocument(URI requested) {
        try {
            load("click", requested.toString(), () -> {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(Timeouts.millis(timeouts.pageLoad())));
                return page;
            });
        } catch (RuntimeException e) {
            throw navigationFailure(e, requested.toString());
        }
    }

    private static Navigation notFollowed(UntestedReason reason, String selector, String detail) {
        return new Navigation.NotFollowed(new Untested.Issue(reason, selector, detail));
    }

    private PageLoad loaded(PageLoad load) {
        lastLoad = load;
        return load;
    }

    private static boolean isMainDocument(Response response) {
        try {
            return response.request().isNavigationRequest() && response.frame().parentFrame() == null;
        } catch (RuntimeException _) {
            return false;
        }
    }

    private @Nullable Response navigate(String url) {
        try {
            return load("navigate", url, () -> page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(Timeouts.millis(timeouts.pageLoad()))));
        } catch (RuntimeException e) {
            throw navigationFailure(e, url);
        }
    }

    private <T extends @Nullable Object> T load(String how, String url, Supplier<T> document) {
        Observation load = Observation.createNotStarted("page.load", observations)
                .lowCardinalityKeyValue("reftrace.load", how)
                .highCardinalityKeyValue("reftrace.url", url);
        WaitOutcome wait = new WaitOutcome(load);
        return load.observe(() -> {
            try {
                T loaded = document.get();
                wait.ended("document", timeouts.pageLoad(), false);
                return loaded;
            } catch (TimeoutError e) {
                wait.ended("document", timeouts.pageLoad(), true);
                throw e;
            }
        });
    }

    private RuntimeException navigationFailure(RuntimeException error, String url) {
        boolean timedOut = error instanceof TimeoutError;
        log.debug("could not open {}: {}", url, PlaywrightErrors.message(error));
        return thread.translate(error, timedOut
                ? UntestedReason.PAGE_LOAD_TIMEOUT : UntestedReason.NAVIGATION_ERROR);
    }

    private void requireSettledUnlessError(@Nullable Response response, String url) {
        HttpStatusCode status = status(response);
        if (status == null || !status.isError()) {
            stabilizer.requireSettled(url, SettleWaiter.NAVIGATIONS_FOR_PHASE_TWO);
        }
    }

    private static @Nullable HttpStatusCode status(@Nullable Response response) {
        return response == null ? null : HttpStatusCode.valueOf(response.status());
    }
}
