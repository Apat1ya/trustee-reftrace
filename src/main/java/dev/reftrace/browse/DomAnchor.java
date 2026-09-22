package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;

import java.net.URI;

public record DomAnchor(String locator, @Nullable URI href, @Nullable String label, boolean visible,
                        String selector, boolean unwalked) {

    public DomAnchor {
        if (locator.isBlank()) {
            throw new IllegalArgumentException("anchor needs a locator");
        }
        if (selector.isBlank()) {
            throw new IllegalArgumentException("anchor needs a selector");
        }
    }

    public static DomAnchor of(String locator, String href, @Nullable String label, boolean visible,
                               String selector, boolean unwalked) {
        return new DomAnchor(locator, ObservedUrl.parse(href).orElse(null), label, visible, selector, unwalked);
    }
}
