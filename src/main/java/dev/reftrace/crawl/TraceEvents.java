package dev.reftrace.crawl;

import dev.reftrace.browse.Untested;
import dev.reftrace.judge.Check;
import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

final class TraceEvents {

    private TraceEvents() {
    }

    static Observation.@Nullable Event of(Check check) {
        String outcome = check.outcome().name().toLowerCase(Locale.ROOT);
        return switch (check) {
            case Check.Pass _ -> null;
            case Check.Mismatch mismatch -> Observation.Event.of(outcome, outcome + " " + mismatch.route() + " at "
                    + mismatch.link().selector() + ": " + mismatch.link().url());
            case Check.NotTested(Untested untested) ->
                    Observation.Event.of(outcome, outcome + " " + untested(untested));
            case Check.Failed _ -> Observation.Event.of(outcome, outcome + ": the monitor broke down");
        };
    }

    static String untested(Untested untested) {
        return untested.reason() + (untested.selector() == null ? "" : " at " + untested.selector()) + ": "
                + untested.detail();
    }

    static final class MonitorFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MonitorFailure(String message) {
            super(message, null, false, false);
        }
    }
}
