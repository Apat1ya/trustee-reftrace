package dev.reftrace.sitemap;

import java.net.URI;
import java.util.List;

public record SitemapIndex(List<URI> files) implements Sitemap {

    public SitemapIndex {
        files = List.copyOf(files);
    }
}
