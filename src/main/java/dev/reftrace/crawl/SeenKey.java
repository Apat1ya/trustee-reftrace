package dev.reftrace.crawl;

import java.net.URI;

public sealed interface SeenKey {

    record Page(URI address) implements SeenKey {
    }

    record Arrived(URI address, Arrival arrival) implements SeenKey {
    }

    record Traversed(URI from, URI link, URI address) implements SeenKey {
    }

    record Reloaded(URI address, Arrival reloaded) implements SeenKey {
    }
}
