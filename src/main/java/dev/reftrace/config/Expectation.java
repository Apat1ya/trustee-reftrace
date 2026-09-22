package dev.reftrace.config;

public sealed interface Expectation {

    sealed interface OnLink extends Expectation {
    }

    sealed interface OnArrival extends Expectation {

        String name();
    }

    record PathSegment(int index) implements OnLink {

        public PathSegment {
            if (index < 1) {
                throw new IllegalArgumentException("path segments are counted from 1: " + index);
            }
        }
    }

    record Query(String name) implements OnLink {

        public Query {
            if (name.isBlank()) {
                throw new IllegalArgumentException("query parameter name must not be blank");
            }
        }
    }

    record QueryIfPresent(String name) implements OnLink {

        public QueryIfPresent {
            if (name.isBlank()) {
                throw new IllegalArgumentException("query parameter name must not be blank");
            }
        }
    }

    record Fail() implements OnLink {
    }

    record Cookie(String name) implements OnArrival {

        public Cookie {
            if (name.isBlank()) {
                throw new IllegalArgumentException("cookie name must not be blank");
            }
        }
    }

    record LocalStorage(String name) implements OnArrival {

        public LocalStorage {
            if (name.isBlank()) {
                throw new IllegalArgumentException("local-storage item name must not be blank");
            }
        }
    }
}
