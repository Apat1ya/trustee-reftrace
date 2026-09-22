package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public record BrowserIdentity(@Nullable String userAgent, @Nullable Locale locale) {
}
