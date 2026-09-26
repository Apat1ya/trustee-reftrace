package dev.reftrace.crawl;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class Traces {

    public static final String DIRECTORY = "traces";

    private final Path directory;
    private final AtomicInteger kept = new AtomicInteger();

    Traces(Path runDirectory) {
        this.directory = runDirectory.resolve(DIRECTORY);
    }

    Path fileFor(WalkTask task) {
        return directory.resolve(String.format("%03d-%s--%s--%s.zip", kept.incrementAndGet(),
                Screenshots.slug(task.profile().name()), Screenshots.pageSlug(headedFor(task.path())),
                Screenshots.slug(String.join(" ", task.node().scenarios()))));
    }

    static URI headedFor(List<Step> path) {
        for (Step step : path.reversed()) {
            switch (step) {
                case Step.Enter enter -> {
                    return enter.url();
                }
                case Step.Click click -> {
                    return click.link().url();
                }
                case Step.Reload _ -> {
                }
            }
        }
        throw new IllegalArgumentException("a walk starts with enter: " + path);
    }
}
