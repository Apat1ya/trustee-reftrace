package dev.reftrace.browse.page;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;

final class WaitOutcome {

    private final Observation stage;
    private boolean timedOut;

    WaitOutcome(Observation stage) {
        this.stage = stage;
    }

    static WaitOutcome ofCurrent(ObservationRegistry observations) {
        Observation current = observations.getCurrentObservation();
        return new WaitOutcome(current == null ? Observation.NOOP : current);
    }

    void ended(String waitedFor, Duration timeout, boolean ranOut) {
        if (timedOut) {
            return;
        }
        timedOut = ranOut;
        stage.lowCardinalityKeyValue("reftrace.wait.outcome", ranOut ? "timeout" : "condition")
                .lowCardinalityKeyValue("reftrace.wait.for", waitedFor)
                .lowCardinalityKeyValue("reftrace.wait.timeout", String.valueOf(timeout.toMillis()));
    }
}
