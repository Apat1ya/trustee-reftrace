package dev.reftrace.config;

import java.net.URI;

public sealed interface Start {

    record Page(URI url) implements Start {
    }

    record Sitemap(URI url) implements Start {
    }
}
