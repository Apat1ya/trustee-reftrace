package dev.reftrace.judge;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

public enum Outcome {
    PASS,
    MISMATCH,
    UNTESTED,
    FAILED;

    public static Optional<Outcome> worst(Collection<Outcome> outcomes) {
        return Stream.of(MISMATCH, FAILED, UNTESTED, PASS).filter(outcomes::contains).findFirst();
    }
}
