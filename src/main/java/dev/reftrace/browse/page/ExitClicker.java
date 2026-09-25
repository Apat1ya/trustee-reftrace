package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ExitClicker {

    private static final String ANCHOR_PREFIX = "a#";
    private static final String ANCHOR_SELECTOR = "a[href]";
    private static final String HREF = "a => ({href: a.href})";

    private final DriverThread thread;
    private final BrowserContext context;
    private final Page page;
    private final GuardObservations guard;
    private final BrowserTimeouts timeouts;
    private final PageStabilizer stabilizer;
    private final LinkClick linkClick;

    ExitClicker(DriverThread thread, BrowserContext context, Page page, GuardObservations guard,
                BrowserTimeouts timeouts, PageStabilizer stabilizer, LinkClick linkClick) {
        this.thread = thread;
        this.context = context;
        this.page = page;
        this.guard = guard;
        this.timeouts = timeouts;
        this.stabilizer = stabilizer;
        this.linkClick = linkClick;
    }

    ClickObservation click(String locator, String expectedHref, WaitOutcome wait) {
        int index = indexOf(locator);
        if (index < 0) {
            return new ClickObservation.Failed(new TechnicalError(UntestedReason.DOM_CHANGED,
                    "unknown locator " + locator), true);
        }
        Locator element = page.locator(ANCHOR_SELECTOR).nth(index);
        Map<String, Object> link = describe(element);
        if (link == null) {
            return new ClickObservation.Failed(new TechnicalError(UntestedReason.DOM_CHANGED,
                    "the link behind " + locator + " is gone"), true);
        }
        String href = String.valueOf(link.get("href"));
        if (!href.equals(expectedHref)) {
            return new ClickObservation.Failed(new TechnicalError(UntestedReason.DOM_CHANGED,
                    "the link behind " + locator + " now points at " + href
                            + " instead of " + expectedHref), true);
        }
        linkClick.approach(element);
        if (linkClick.unreachable(element).isPresent()) {
            return new ClickObservation.NotReachable();
        }
        return performClick(element, locator, wait);
    }

    private ClickObservation performClick(Locator element, String locator, WaitOutcome wait) {
        guard.reset();
        String urlBefore = page.url();
        List<Page> pagesBefore = context.pages();
        @Nullable RuntimeException clickError = linkClick.click(element, wait, "click");

        ClickCapture.Reaction reaction = ClickCapture.awaitExitClick(page, context, guard, urlBefore, pagesBefore,
                timeouts.clickNavigation());
        wait.ended("reaction", timeouts.clickNavigation(), reaction.timedOut());
        List<Page> opened = reaction.newPages();

        String hit = reaction.hit();
        if (hit != null) {
            ClickCapture.close(opened);
            return ClickObservation.navigated(hit, intact(urlBefore));
        }
        if (!opened.isEmpty()) {
            Optional<String> destination = ClickCapture.destinationOf(opened.get(0));
            ClickCapture.close(opened);
            if (destination.isPresent()) {
                return ClickObservation.navigated(destination.get(), intact(urlBefore));
            }
            return new ClickObservation.Failed(new TechnicalError(UntestedReason.CLICK_FAILED,
                    "a new tab opened but never said where it was going"), intact(urlBefore));
        }
        if (reaction.urlChanged()) {
            stabilizer.settleAndReveal(page.url(), SettleWaiter.NAVIGATIONS_FOR_PHASE_TWO);
            return ClickObservation.navigated(page.url(), false);
        }
        if (clickError != null) {
            return new ClickObservation.Failed(new TechnicalError(UntestedReason.CLICK_FAILED,
                    "clicking " + locator + " failed: " + PlaywrightErrors.message(clickError)), intact(urlBefore));
        }
        return new ClickObservation.NoNavigation(reaction.waited());
    }

    private boolean intact(String urlBefore) {
        try {
            return !page.isClosed() && page.url().equals(urlBefore);
        } catch (RuntimeException _) {
            return false;
        }
    }

    private @Nullable Map<String, Object> describe(Locator element) {
        try {
            Object result = element.evaluate(HREF);
            return result instanceof Map<?, ?> values ? cast(values) : null;
        } catch (RuntimeException e) {
            if (PlaywrightErrors.lostBrowser(e)) {
                throw thread.translate(e, UntestedReason.BROWSER_CRASH);
            }
            return null;
        }
    }

    private static int indexOf(String locator) {
        if (!locator.startsWith(ANCHOR_PREFIX)) {
            return -1;
        }
        try {
            int index = Integer.parseInt(locator.substring(ANCHOR_PREFIX.length()));
            return index < 0 ? -1 : index;
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> values) {
        return (Map<String, Object>) values;
    }
}
