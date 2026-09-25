package dev.reftrace.browse.page;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

final class SettleWaiter {

    private static final int POLL_INTERVAL_MS = 100;

    static final int NAVIGATIONS_FOR_PHASE_TWO = 2;

    private final BrowserTimeouts timeouts;

    SettleWaiter(BrowserTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    record Settled(boolean marked, boolean cappedOut, Duration waited) {

        String describe() {
            if (cappedOut) {
                return "the page never stopped changing within " + waited.toMillis() + " ms";
            }
            return marked ? "settled after " + waited.toMillis() + " ms"
                    : "settled after " + waited.toMillis() + " ms without showing an exit";
        }
    }

    Settled await(Page page, PageProbe probe, IntSupplier mainFrameNavigations, int navigations) {
        probe.ensureInstalled();
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeouts.settleMax().toNanos();
        boolean routed = awaitCondition(page, () -> mainFrameNavigations.getAsInt() >= navigations, deadline);
        if (routed) {
            restartQuiet(probe);
        }
        boolean cappedOut = !routed || !(awaitQuiet(probe, deadline) || quietNow(probe));
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
        return new Settled(markerSeen(probe), cappedOut, waited);
    }

    private int quietMs() {
        return Math.toIntExact(timeouts.settleQuiet().toMillis());
    }

    private boolean awaitCondition(Page page, BooleanSupplier condition, long deadline) {
        double timeout = remainingMillis(deadline);
        if (timeout <= 0) {
            return false;
        }
        try {
            page.waitForCondition(condition, new Page.WaitForConditionOptions().setTimeout(timeout));
            return true;
        } catch (TimeoutError _) {
            return false;
        }
    }

    private boolean awaitQuiet(PageProbe probe, long deadline) {
        double timeout = remainingMillis(deadline);
        if (timeout <= 0) {
            return false;
        }
        try {
            probe.awaitSettled(quietMs(), true, POLL_INTERVAL_MS, timeout);
            return true;
        } catch (TimeoutError _) {
            return false;
        }
    }

    @SuppressWarnings("EmptyCatch")
    private static void restartQuiet(PageProbe probe) {
        try {
            probe.restartQuiet();
        } catch (RuntimeException _) {
        }
    }

    private boolean quietNow(PageProbe probe) {
        try {
            return probe.settled(quietMs(), false);
        } catch (RuntimeException _) {
            return false;
        }
    }

    private static double remainingMillis(long deadline) {
        return (double) Duration.ofNanos(deadline - System.nanoTime()).toMillis();
    }

    private static boolean markerSeen(PageProbe probe) {
        try {
            return probe.markerShown();
        } catch (RuntimeException _) {
            return false;
        }
    }
}
