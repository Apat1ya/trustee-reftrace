package dev.reftrace.run;

import java.net.URI;
import java.time.Instant;
import java.util.List;

record RunInfo(RunId id, Instant startedAt, Trigger trigger, URI baseUrl, List<String> profiles) {

    RunInfo {
        profiles = List.copyOf(profiles);
    }
}
