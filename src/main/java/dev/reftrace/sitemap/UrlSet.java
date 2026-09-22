package dev.reftrace.sitemap;

import java.net.URI;
import java.util.List;

public record UrlSet(List<URI> pages) implements Sitemap {

    public UrlSet {
        pages = List.copyOf(pages);
    }
}
