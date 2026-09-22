package dev.reftrace.browse;

public record TechnicalError(UntestedReason kind, String message) {

    public TechnicalError {
        if (kind == UntestedReason.HTTP_STATUS) {
            throw new IllegalArgumentException("an error status is untested with its status: " + message);
        }
    }
}
