package dev.reftrace.crawl;

import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.judge.Check;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record PageVisit(DeviceProfile profile, List<String> scenarios, List<Step> path, Landing landing,
                        List<Check> checks, Map<Check, Path> screenshots) {

    public PageVisit {
        scenarios = List.copyOf(scenarios);
        path = List.copyOf(path);
        checks = List.copyOf(checks);
        screenshots = Map.copyOf(screenshots);
    }
}
