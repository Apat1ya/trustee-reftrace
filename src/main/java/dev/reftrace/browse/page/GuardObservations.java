package dev.reftrace.browse.page;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GuardObservations {

    private final List<String> hits = new CopyOnWriteArrayList<>();

    Optional<String> firstHit() {
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    boolean hasHits() {
        return !hits.isEmpty();
    }

    void reset() {
        hits.clear();
    }

    public void hit(String url) {
        hits.add(url);
    }
}
