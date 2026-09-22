package dev.reftrace.browse;

import java.net.URI;
import java.util.List;

public record PageScan(URI landedUrl, List<FoundLink> links, List<StoredValue> stored, List<Untested> untested) {

    public PageScan {
        links = List.copyOf(links);
        stored = List.copyOf(stored);
        untested = List.copyOf(untested);
    }
}
