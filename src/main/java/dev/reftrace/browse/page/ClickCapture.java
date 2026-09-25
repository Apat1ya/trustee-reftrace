package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class ClickCapture {

    private ClickCapture() {
    }

    record Reaction(@Nullable String hit, boolean urlChanged, List<Page> newPages,
                    Duration waited, boolean timedOut) {
    }

    static Reaction awaitExitClick(Page page, BrowserContext context, GuardObservations guard,
                                   String urlBefore, List<Page> pagesBefore, Duration timeout) {
        return await(page, context, guard, urlBefore, pagesBefore, timeout, () -> false);
    }

    static Reaction awaitFollowClick(Page page, BrowserContext context, GuardObservations guard,
                                     String urlBefore, List<Page> pagesBefore, Duration timeout,
                                     BooleanSupplier navigated) {
        return await(page, context, guard, urlBefore, pagesBefore, timeout, navigated);
    }

    private static Reaction await(Page page, BrowserContext context, GuardObservations guard,
                                  String urlBefore, List<Page> pagesBefore, Duration timeout,
                                  BooleanSupplier navigated) {
        long startedAt = System.nanoTime();
        boolean timedOut = false;
        try {
            page.waitForCondition(() -> guard.hasHits() || moved(page, urlBefore) || grew(context, pagesBefore)
                            || navigated.getAsBoolean(),
                    new Page.WaitForConditionOptions().setTimeout(Timeouts.millis(timeout)));
        } catch (TimeoutError _) {
            timedOut = true;
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
        return new Reaction(guard.firstHit().orElse(null), moved(page, urlBefore),
                added(context, pagesBefore), waited, timedOut);
    }

    static Optional<String> destinationOf(Page popup) {
        try {
            String url = popup.url();
            if (url.isBlank() || "about:blank".equals(url)) {
                return Optional.empty();
            }
            return Optional.of(url);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("EmptyCatch")
    static void close(List<Page> pages) {
        for (Page page : pages) {
            try {
                page.close();
            } catch (RuntimeException _) {
            }
        }
    }

    private static boolean moved(Page page, String urlBefore) {
        try {
            return page.isClosed() || !page.url().equals(urlBefore);
        } catch (RuntimeException _) {
            return true;
        }
    }

    private static boolean grew(BrowserContext context, List<Page> pagesBefore) {
        return !added(context, pagesBefore).isEmpty();
    }

    private static List<Page> added(BrowserContext context, List<Page> pagesBefore) {
        try {
            return context.pages().stream().filter(page -> !pagesBefore.contains(page)).toList();
        } catch (RuntimeException _) {
            return List.of();
        }
    }
}
