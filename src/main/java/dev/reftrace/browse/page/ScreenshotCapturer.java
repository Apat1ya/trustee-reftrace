package dev.reftrace.browse.page;

import com.microsoft.playwright.Page;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ScreenshotCapturer {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotCapturer.class);

    private final Page page;
    private final PageProbe probe;
    private final BrowserTimeouts timeouts;

    ScreenshotCapturer(Page page, PageProbe probe, BrowserTimeouts timeouts) {
        this.page = page;
        this.probe = probe;
        this.timeouts = timeouts;
    }

    byte @Nullable [] screenshot(@Nullable String selector) {
        boolean outlined = selector != null && outline(selector);
        try {
            return page.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(false).setTimeout(Timeouts.millis(timeouts.operation())));
        } catch (RuntimeException e) {
            log.debug("no screenshot of {}: {}", page.url(), PlaywrightErrors.message(e));
            return null;
        } finally {
            if (outlined) {
                unoutline();
            }
        }
    }

    private boolean outline(String selector) {
        try {
            return probe.outline(selector, timeouts.operation());
        } catch (RuntimeException e) {
            log.debug("could not point at {}: {}", selector, PlaywrightErrors.message(e));
            return false;
        }
    }

    private void unoutline() {
        try {
            probe.unoutline(timeouts.operation());
        } catch (RuntimeException e) {
            log.debug("could not take the outline off again: {}", PlaywrightErrors.message(e));
        }
    }
}
