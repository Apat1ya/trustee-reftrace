package dev.reftrace.browse;

import dev.reftrace.config.Expectation;
import org.jspecify.annotations.Nullable;

public record StoredValue(FoundLink followed, String route, Expectation.OnArrival expectation,
                          @Nullable String value) {
}
