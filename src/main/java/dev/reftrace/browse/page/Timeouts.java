package dev.reftrace.browse.page;

import java.time.Duration;

public final class Timeouts {

    private Timeouts() {
    }

    public static double millis(Duration duration) {
        return (double) duration.toMillis();
    }
}
