package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public final class BrowserStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Stage {
        LAUNCH,
        CONTEXT,
        SETUP
    }

    public BrowserStartException(Stage stage, DeviceProfile profile, String reason, @Nullable Throwable cause) {
        super(describe(stage, profile) + ": " + reason, cause);
    }

    private static String describe(Stage stage, DeviceProfile profile) {
        String engine = profile.engine().name().toLowerCase(Locale.ROOT);
        String what = switch (stage) {
            case LAUNCH -> "could not launch " + engine;
            case CONTEXT -> "could not create a " + engine + " context";
            case SETUP -> "could not prepare a new " + engine + " context";
        };
        return what + " for profile " + profile.name();
    }
}
