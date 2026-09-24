package dev.reftrace.browse.page;

import com.microsoft.playwright.TimeoutError;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record ClickFailure(Blocker blocker, @Nullable String interceptor, boolean timedOut, String message,
                    List<String> callLog) {

    private static final int INTERCEPTOR_LENGTH = 200;

    private static final String CALL_LOG = "Call log:";
    private static final String ENTRY = "-";
    private static final Pattern ENTRY_MARKS = Pattern.compile("^(?:-\\s*)+(?:\\d+ × )?");
    private static final String INTERCEPTS = " intercepts pointer events";

    ClickFailure {
        callLog = List.copyOf(callLog);
    }

    enum Blocker {
        INTERCEPTED,
        OUTSIDE_VIEWPORT,
        NOT_STABLE,
        NOT_VISIBLE,
        NOT_READY,
        CLICKED,
        OTHER;

        String word() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    static ClickFailure unreachable(String word, @Nullable String interceptor) {
        Blocker blocker = Arrays.stream(Blocker.values())
                .filter(candidate -> candidate.word().equals(word))
                .findFirst()
                .orElse(Blocker.OTHER);
        return new ClickFailure(blocker, blocker == Blocker.INTERCEPTED && interceptor != null
                ? cut(interceptor) : null, false, "a visitor could not press it", List.of());
    }

    static ClickFailure of(RuntimeException error) {
        List<String> callLog = callLogIn(error.getMessage());
        Blocker blocker = null;
        String interceptor = null;
        for (String line : callLog) {
            String entry = entry(line);
            Blocker cause = cause(entry);
            if (cause != null) {
                blocker = cause;
                interceptor = cause == Blocker.INTERCEPTED ? interceptorIn(entry) : null;
            }
        }
        boolean timedOut = error instanceof TimeoutError;
        if (blocker == null) {
            blocker = timedOut && !callLog.isEmpty() ? Blocker.NOT_READY : Blocker.OTHER;
        }
        return new ClickFailure(blocker, interceptor, timedOut, PlaywrightErrors.message(error), callLog);
    }

    String detail(String selector, Duration timeout) {
        return switch (blocker) {
            case INTERCEPTED -> "not clickable: covered by " + interceptor;
            case OUTSIDE_VIEWPORT -> "not clickable: outside of the viewport";
            case NOT_STABLE -> "not clickable: kept moving";
            case NOT_VISIBLE -> "not clickable: not visible";
            case NOT_READY -> "not clickable: never became visible/ready within " + timeout.toMillis() + " ms";
            case CLICKED -> "clicked, but the click did not finish within " + timeout.toMillis() + " ms";
            case OTHER -> "clicking " + selector + " failed: " + message;
        };
    }

    private static List<String> callLogIn(@Nullable String message) {
        List<String> lines = new ArrayList<>();
        if (message == null) {
            return lines;
        }
        boolean inLog = false;
        for (String line : message.lines().toList()) {
            if (!inLog) {
                inLog = line.strip().equals(CALL_LOG);
            } else if (line.strip().startsWith(ENTRY)) {
                lines.add(line.strip());
            } else if (line.isBlank()) {
                break;
            }
        }
        return lines;
    }

    private static String entry(String line) {
        return ENTRY_MARKS.matcher(line).replaceFirst("").strip();
    }

    private static @Nullable Blocker cause(String entry) {
        if (entry.endsWith(INTERCEPTS)) {
            return Blocker.INTERCEPTED;
        }
        if (entry.startsWith("element is outside of the viewport")) {
            return Blocker.OUTSIDE_VIEWPORT;
        }
        if (entry.startsWith("element is not stable")) {
            return Blocker.NOT_STABLE;
        }
        if (entry.startsWith("element is not visible")) {
            return Blocker.NOT_VISIBLE;
        }
        if (entry.startsWith("performing click action") || entry.startsWith("click action done")
                || entry.startsWith("waiting for scheduled navigations to finish")) {
            return Blocker.CLICKED;
        }
        return null;
    }

    private static String interceptorIn(String entry) {
        return cut(entry.substring(0, entry.length() - INTERCEPTS.length()).strip());
    }

    private static String cut(String element) {
        return element.length() <= INTERCEPTOR_LENGTH ? element : element.substring(0, INTERCEPTOR_LENGTH) + "…";
    }
}
