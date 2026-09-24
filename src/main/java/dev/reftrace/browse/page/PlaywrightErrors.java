package dev.reftrace.browse.page;

import com.microsoft.playwright.TimeoutError;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;

import java.util.Locale;

public final class PlaywrightErrors {

    private static final String[] LOST_STACK_MARKERS = {
            "target page, context or browser has been closed",
            "target closed",
            "browser has been closed",
            "browser has disconnected",
            "connection closed",
            "pipe closed",
            "playwright connection closed"
    };

    private static final String DRIVER_ERROR = "Error {";
    private static final String DRIVER_MESSAGE = "message='";

    private PlaywrightErrors() {
    }

    static boolean lostBrowser(RuntimeException error) {
        String message = message(error).toLowerCase(Locale.ROOT);
        for (String marker : LOST_STACK_MARKERS) {
            if (message.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static TechnicalError describe(RuntimeException error, UntestedReason whenNothingElseFits) {
        UntestedReason kind;
        if (lostBrowser(error)) {
            kind = UntestedReason.BROWSER_CRASH;
        } else if (error instanceof TimeoutError) {
            kind = whenNothingElseFits == UntestedReason.NAVIGATION_ERROR
                    ? UntestedReason.PAGE_LOAD_TIMEOUT : whenNothingElseFits;
        } else {
            kind = whenNothingElseFits;
        }
        return new TechnicalError(kind, message(error));
    }

    public static String message(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String text = message.strip();
        int wrapped = text.startsWith(DRIVER_ERROR) ? text.indexOf(DRIVER_MESSAGE) : -1;
        if (wrapped >= 0) {
            text = text.substring(wrapped + DRIVER_MESSAGE.length());
        }
        String first = text.lines().findFirst().orElse(text).trim();
        return first.isEmpty() ? error.getClass().getSimpleName() : first;
    }
}
