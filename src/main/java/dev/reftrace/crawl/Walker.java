package dev.reftrace.crawl;

import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.PageScan;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.Judge;
import dev.reftrace.judge.ReferralKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

public final class Walker {

    private static final Logger log = LoggerFactory.getLogger(Walker.class);

    private final Coverage coverage;
    private final RunKeys keys;
    private final Routes routes;
    private final String keyParam;
    private final int depth;
    private boolean depthReached;

    public Walker(Coverage coverage, RunKeys keys, Routes routes, String keyParam, int depth) {
        if (keyParam.isBlank()) {
            throw new IllegalArgumentException("the walker needs the name of the key parameter");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("a depth is 0 for no limit or a number of pages: " + depth);
        }
        this.coverage = coverage;
        this.keys = keys;
        this.routes = routes;
        this.keyParam = keyParam;
        this.depth = depth;
    }

    public record Counters(Pages pages, Links links) {
    }

    public record Pages(int start, int unique, int visits, int repeats, int maxDepth) {
    }

    public record Queued(WalkTask task, long queuedAt) {
    }

    public record Visited(Landing landing, boolean repeat) {
    }

    public record Links(int followed, int checked, int ignored) {
    }

    public static ReferralKey expectedKey(List<Step> path) {
        return path.reversed().stream()
                .<ReferralKey>mapMulti((step, keep) -> {
                    if (step instanceof Step.Enter enter) {
                        keep.accept(enter.key());
                    }
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("a path starts with enter: " + path));
    }

    public Walk walk(DeviceProfile profile, StepTree tree, List<URI> starts) {
        Walk walk = new Walk();
        starts.stream().filter(routes::followed).forEach(start -> {
            if (walk.offer(new WalkTask(profile, tree.entered(), List.of(new Step.Enter(start, keys.first())),
                    null))) {
                walk.start++;
            }
        });
        return walk;
    }

    public final class Walk {

        private final PriorityQueue<InQueue> queue = new PriorityQueue<>(
                Comparator.comparingInt((InQueue queued) -> queued.task().path().size())
                        .thenComparingLong(InQueue::order));
        private final Map<Seen, WalkTask> seen = new HashMap<>();
        private final Set<URI> addresses = new HashSet<>();
        private long offered;
        private int start;
        private int visits;
        private int repeats;
        private int maxDepth;
        private int followed;
        private int checked;
        private int ignored;
        private int beyondDepth;

        private Walk() {
        }

        public int beyondDepth() {
            return beyondDepth;
        }

        public Optional<Queued> take() {
            return Optional.ofNullable(queue.poll()).map(queued -> new Queued(queued.task(), queued.queuedAt()));
        }

        public int queued() {
            return queue.size();
        }

        public Visited visited(WalkTask task, PageScan scan) {
            scan.links().forEach(link -> count(routes.routeFor(link.url()).orElse(null), link.url()));
            checked += scan.stored().size();
            return visit(task, scan.landedUrl(), new Offer(scan));
        }

        public Landing failed(WalkTask task) {
            return visit(task, target(task), null).landing();
        }

        private Visited visit(WalkTask task, URI landedUrl, @Nullable Offer offer) {
            visits++;
            maxDepth = Math.max(maxDepth, task.path().size());
            Landing predicted = predictedLanding(task);
            URI landedAddress = Landing.of(task.path(), false, landedUrl, keyParam).address();
            Landing landing = Landing.of(task.path(), !landedAddress.equals(predicted.address()), landedUrl,
                    keyParam);
            addresses.add(landing.address());
            SeenKey key = coverage.seenKey(task, landing);
            boolean fresh = takes(task, task.node(), key);
            if (!fresh) {
                repeats++;
            } else if (offer != null) {
                expand(task, task.node(), landing, key, offer);
            }
            return new Visited(landing, !fresh);
        }

        public Counters counters() {
            return new Counters(new Pages(start, addresses.size(), visits, repeats, maxDepth),
                    new Links(followed, checked, ignored));
        }

        private void count(@Nullable Route route, URI url) {
            boolean follows = route != null && route.follow();
            boolean expects = route != null && !Judge.applying(route, url).isEmpty();
            if (follows) {
                followed++;
            }
            if (expects) {
                checked++;
            }
            if (!follows && !expects) {
                ignored++;
            }
        }

        private void expand(WalkTask task, StepTree.Node node, Landing landing, SeenKey key, Offer offer) {
            if (node.word() == StepWord.CLICK_ANY) {
                offer.followable().forEach(link -> child(task, node, landing, new Step.Click(link)));
            }
            node.children().forEach((word, child) -> {
                switch (word) {
                    case ENTER -> throw new IllegalStateException("enter only starts a scenario");
                    case CLICK -> offer.followable().forEach(link -> child(task, child, landing, new Step.Click(link)));
                    case CLICK_ANY -> {
                        if (takes(task, child, key)) {
                            expand(task, child, landing, key, offer);
                        }
                    }
                    case RELOAD -> child(task, child, landing, new Step.Reload());
                    case ENTER_NEW_KEY -> child(task, child, landing, new Step.Enter(landing.address(), keys.second()));
                }
            });
        }

        private void child(WalkTask task, StepTree.Node node, Landing landing, Step step) {
            offer(new WalkTask(task.profile(), node, Stream.concat(task.path().stream(), Stream.of(step)).toList(),
                    landing));
        }

        private boolean offer(WalkTask task) {
            SeenKey key = coverage.seenKey(task, predictedLanding(task));
            if (seen.containsKey(new Seen(task.node(), key))) {
                repeats++;
                return false;
            }
            if (depth > 0 && task.depth() > depth) {
                beyondDepth++;
                if (!depthReached) {
                    depthReached = true;
                    log.info("limits.run.depth of {} is reached: no walk clicks past it", depth);
                }
                return false;
            }
            for (StepTree.Node node = task.node(); node != null; node = node.children().get(StepWord.CLICK_ANY)) {
                seen.putIfAbsent(new Seen(node, key), task);
            }
            queue.add(new InQueue(task, offered++, System.nanoTime()));
            return true;
        }

        private boolean takes(WalkTask task, StepTree.Node node, SeenKey key) {
            WalkTask taken = seen.putIfAbsent(new Seen(node, key), task);
            return taken == null || taken.equals(task);
        }
    }

    private final class Offer {

        private final PageScan scan;
        private @Nullable List<FoundLink> followable;

        private Offer(PageScan scan) {
            this.scan = scan;
        }

        List<FoundLink> followable() {
            List<FoundLink> read = followable;
            if (read == null) {
                read = scan.links().stream()
                        .filter(link -> link.visible() && !link.unwalked() && link.how() == How.HREF
                                && routes.followed(link.url()))
                        .toList();
                followable = read;
            }
            return read;
        }
    }

    private Landing predictedLanding(WalkTask task) {
        return Landing.of(task.path(), false, target(task), keyParam);
    }

    static URI target(WalkTask task) {
        return switch (task.path().getLast()) {
            case Step.Enter enter -> enter.url();
            case Step.Click click -> click.link().url();
            case Step.Reload _ -> Objects.requireNonNull(task.from(), "a reload has a page to reload").address();
        };
    }

    private record InQueue(WalkTask task, long order, long queuedAt) {
    }

    private record Seen(StepTree.Node node, SeenKey key) {
    }
}
