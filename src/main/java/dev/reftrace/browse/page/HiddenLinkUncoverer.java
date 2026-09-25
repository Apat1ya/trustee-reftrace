package dev.reftrace.browse.page;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import dev.reftrace.browse.UntestedReason;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

final class HiddenLinkUncoverer {

    private static final Logger log = LoggerFactory.getLogger(HiddenLinkUncoverer.class);

    private static final int MAX_UNCOVER_DEPTH = 6;

    private final DriverThread thread;
    private final Page page;
    private final PageProbe probe;
    private final ExitRevealer revealer;
    private final BrowserTimeouts timeouts;
    private final ObservationRegistry observations;

    HiddenLinkUncoverer(DriverThread thread, Page page, PageProbe probe, ExitRevealer revealer,
                        BrowserTimeouts timeouts, ObservationRegistry observations) {
        this.thread = thread;
        this.page = page;
        this.probe = probe;
        this.revealer = revealer;
        this.timeouts = timeouts;
        this.observations = observations;
    }

    void uncover(Locator element) {
        Observation uncover = Observation.createNotStarted("page.uncover", observations);
        WaitOutcome wait = new WaitOutcome(uncover);
        uncover.observe(() -> {
            try {
                hoverAncestors(element);
            } catch (RuntimeException e) {
                notUncovered(e, wait, "hover", timeouts.operation());
            }
            try {
                revealer.openWayTo(page, probe, element, wait);
            } catch (RuntimeException e) {
                notUncovered(e, wait, "menu", ExitRevealer.ACTION_TIMEOUT);
            }
        });
    }

    private void notUncovered(RuntimeException e, WaitOutcome wait, String waitedFor, Duration timeout) {
        if (PlaywrightErrors.lostBrowser(e)) {
            throw thread.translate(e, UntestedReason.BROWSER_CRASH);
        }
        if (e instanceof TimeoutError) {
            wait.ended(waitedFor, timeout, true);
        }
        log.debug("could not bring a hidden link back on screen: {}", PlaywrightErrors.message(e));
    }

    private void hoverAncestors(Locator element) {
        if (element.isVisible()) {
            return;
        }
        probe.ensureInstalled();
        Locator ancestor = element.locator("xpath=..");
        for (int depth = 0; depth < MAX_UNCOVER_DEPTH; depth++) {
            if (ancestor.count() != 1 || "BODY".equals(ancestor.evaluate("node => node.tagName"))) {
                return;
            }
            if (probe.onScreen(ancestor)) {
                ancestor.hover(new Locator.HoverOptions().setTimeout(Timeouts.millis(timeouts.operation())));
                if (element.isVisible()) {
                    return;
                }
            }
            ancestor = ancestor.locator("xpath=..");
        }
    }
}
