package dev.reftrace.browse.page;

import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;

public final class DriverThread {

    private final Thread owner = Thread.currentThread();

    private volatile boolean killed;

    public void kill() {
        killed = true;
    }

    public boolean killed() {
        return killed;
    }

    public String ownerName() {
        return owner.getName();
    }

    PageCheckException translate(RuntimeException error, UntestedReason whenNothingElseFits) {
        if (killed) {
            return PageCheckException.causedBy(new TechnicalError(UntestedReason.BROWSER_CRASH,
                    "the browser stack was destroyed by the watchdog: " + PlaywrightErrors.message(error)), error);
        }
        return PageCheckException.causedBy(PlaywrightErrors.describe(error, whenNothingElseFits), error);
    }

    public void requireOwnerThread() {
        if (!owner.equals(Thread.currentThread())) {
            throw new IllegalStateException("this browser belongs to thread " + owner.getName()
                    + " and must not be used from " + Thread.currentThread().getName());
        }
    }
}
