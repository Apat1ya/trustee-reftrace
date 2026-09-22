package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;

public class PageCheckException extends RuntimeException {

    private final transient TechnicalError error;

    private PageCheckException(TechnicalError error, @Nullable Throwable cause) {
        super(error.kind() + ": " + error.message(), cause);
        this.error = error;
    }

    public static PageCheckException of(TechnicalError error) {
        return new PageCheckException(error, null);
    }

    public static PageCheckException causedBy(TechnicalError error, Throwable cause) {
        return new PageCheckException(error, cause);
    }

    public TechnicalError error() {
        return error;
    }
}
