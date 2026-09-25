package dev.reftrace.crawl;

import dev.reftrace.browse.DeviceProfile;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record WalkTask(DeviceProfile profile, StepTree.Node node, List<Step> path, @Nullable Landing from) {

    public WalkTask {
        path = List.copyOf(path);
        if (path.isEmpty() || !(path.getFirst() instanceof Step.Enter)) {
            throw new IllegalArgumentException("a walk starts with enter: " + path);
        }
        if ((from == null) != (path.size() == 1)) {
            throw new IllegalArgumentException("every step but the first is taken on a page: " + path);
        }
    }

    public int depth() {
        return 1 + (int) path.stream().filter(Step.Click.class::isInstance).count();
    }
}
