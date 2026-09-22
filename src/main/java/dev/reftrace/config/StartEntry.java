package dev.reftrace.config;

import org.jspecify.annotations.Nullable;

import java.net.URI;

public record StartEntry(@Nullable URI page, @Nullable URI sitemap) {

    static StartEntry ofPage(URI url) {
        return new StartEntry(url, null);
    }

    public Start toStart() {
        if (page != null && sitemap == null) {
            return new Start.Page(page);
        }
        if (sitemap != null && page == null) {
            return new Start.Sitemap(sitemap);
        }
        throw new IllegalArgumentException("a start entry is either a page URL or a sitemap: " + this);
    }
}
