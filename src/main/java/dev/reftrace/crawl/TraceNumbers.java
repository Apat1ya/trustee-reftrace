package dev.reftrace.crawl;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;

final class TraceNumbers {

    private TraceNumbers() {
    }

    static void tag(Observation observation, String key, long value) {
        TracingObservationHandler.TracingContext tracing =
                observation.getContext().get(TracingObservationHandler.TracingContext.class);
        Span span = tracing == null ? null : tracing.getSpan();
        if (span != null) {
            span.tag(key, value);
        }
    }
}
