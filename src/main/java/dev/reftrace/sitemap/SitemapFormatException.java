package dev.reftrace.sitemap;

import org.jspecify.annotations.Nullable;

public final class SitemapFormatException extends RuntimeException {

    private SitemapFormatException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    public static SitemapFormatException of(String message) {
        return new SitemapFormatException(message, null);
    }

    public static SitemapFormatException withCause(String message, Throwable cause) {
        return new SitemapFormatException(message, cause);
    }
}
