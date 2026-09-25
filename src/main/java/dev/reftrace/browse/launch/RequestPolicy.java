package dev.reftrace.browse.launch;

import dev.reftrace.browse.UrlPatterns;

final class RequestPolicy {

    private final UrlPatterns patterns;

    RequestPolicy(UrlPatterns patterns) {
        this.patterns = patterns;
    }

    sealed interface Outcome {
    }

    record Pass() implements Outcome {
    }

    record Fulfill204() implements Outcome {
    }

    record AbortAnalytics() implements Outcome {
    }

    Outcome decide(String url) {
        if (patterns.isExit(url) || UrlPatterns.unreadableWebUrl(url)) {
            return new Fulfill204();
        }
        if (patterns.isAnalytics(url)) {
            return new AbortAnalytics();
        }
        return new Pass();
    }
}
