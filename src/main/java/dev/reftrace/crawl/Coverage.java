package dev.reftrace.crawl;

import dev.reftrace.config.CoverageMode;

public sealed interface Coverage {

    record OncePerPage() implements Coverage {
    }

    record OncePerArrival() implements Coverage {
    }

    record OncePerLink() implements Coverage {
    }

    static Coverage of(CoverageMode mode) {
        return switch (mode) {
            case ONCE_PER_PAGE -> new OncePerPage();
            case ONCE_PER_ARRIVAL -> new OncePerArrival();
            case ONCE_PER_LINK -> new OncePerLink();
        };
    }

    default SeenKey seenKey(WalkTask task, Landing landing) {
        Landing from = task.from();
        if (from != null && landing.arrival() == Arrival.RELOADED) {
            return new SeenKey.Reloaded(landing.address(), from.arrival());
        }
        return switch (this) {
            case OncePerPage _ -> new SeenKey.Page(landing.address());
            case OncePerArrival _ -> new SeenKey.Arrived(landing.address(), landing.arrival());
            case OncePerLink _ -> from != null && task.path().getLast() instanceof Step.Click click
                    ? new SeenKey.Traversed(from.address(), click.link().url(), landing.address())
                    : new SeenKey.Arrived(landing.address(), landing.arrival());
        };
    }
}
