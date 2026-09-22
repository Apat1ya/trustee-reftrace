package dev.reftrace.config;

import org.jspecify.annotations.Nullable;

public record ExpectEntry(@Nullable Integer pathSegment, @Nullable String query,
                          @Nullable String queryIfPresent, @Nullable String cookie,
                          @Nullable String localStorage, boolean fail) {

    public static final String FAIL = "fail";

    static ExpectEntry failing() {
        return new ExpectEntry(null, null, null, null, null, true);
    }

    public Expectation toExpectation() {
        int set = (pathSegment != null ? 1 : 0) + (query != null ? 1 : 0) + (queryIfPresent != null ? 1 : 0)
                + (cookie != null ? 1 : 0)
                + (localStorage != null ? 1 : 0) + (fail ? 1 : 0);
        if (set != 1) {
            throw new IllegalArgumentException("an expect entry names exactly one of path-segment, query, query-if-present, cookie, "
                    + "local-storage, fail: " + this);
        }
        if (pathSegment != null) {
            return new Expectation.PathSegment(pathSegment);
        }
        if (query != null) {
            return new Expectation.Query(query);
        }
        if (queryIfPresent != null) {
            return new Expectation.QueryIfPresent(queryIfPresent);
        }
        if (cookie != null) {
            return new Expectation.Cookie(cookie);
        }
        return localStorage != null ? new Expectation.LocalStorage(localStorage) : new Expectation.Fail();
    }
}
