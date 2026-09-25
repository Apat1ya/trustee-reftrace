package dev.reftrace.browse.page;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Clicks;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

final class LinkClick {

    private static final String CLICK = "a => { a.click(); }";
    private static final String SCROLL_AND_CLICK = "a => { window.__reftrace.toMiddle(a); a.click(); }";

    private final DriverThread thread;
    private final PageProbe probe;
    private final HiddenLinkUncoverer uncoverer;
    private final BrowserTimeouts timeouts;
    private final Clicks clicks;

    LinkClick(DriverThread thread, PageProbe probe, HiddenLinkUncoverer uncoverer, BrowserTimeouts timeouts,
              Clicks clicks) {
        this.thread = thread;
        this.probe = probe;
        this.uncoverer = uncoverer;
        this.timeouts = timeouts;
        this.clicks = clicks;
    }

    void approach(Locator element) {
        if (clicks == Clicks.REACHABLE) {
            uncoverer.uncover(element);
        }
    }

    Optional<ClickFailure> unreachable(Locator element) {
        if (clicks != Clicks.REACHABLE) {
            return Optional.empty();
        }
        try {
            probe.ensureInstalled();
            return probe.unreachable(element);
        } catch (RuntimeException e) {
            if (PlaywrightErrors.lostBrowser(e)) {
                throw thread.translate(e, UntestedReason.BROWSER_CRASH);
            }
            return Optional.of(ClickFailure.of(e));
        }
    }

    @Nullable RuntimeException click(Locator element, WaitOutcome wait, String waitedFor) {
        try {
            if (clicks != Clicks.REACHABLE) {
                probe.ensureInstalled();
            }
            element.evaluate(clicks == Clicks.REACHABLE ? CLICK : SCROLL_AND_CLICK, null,
                    new Locator.EvaluateOptions().setTimeout(Timeouts.millis(timeouts.operation())));
            return null;
        } catch (RuntimeException e) {
            if (PlaywrightErrors.lostBrowser(e)) {
                throw thread.translate(e, UntestedReason.BROWSER_CRASH);
            }
            if (e instanceof TimeoutError) {
                wait.ended(waitedFor, timeouts.operation(), true);
            }
            return e;
        }
    }
}
