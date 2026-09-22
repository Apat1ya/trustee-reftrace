package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;

import java.net.URI;

public sealed interface Untested {

    UntestedReason reason();

    @Nullable String selector();

    String detail();

    record Issue(UntestedReason reason, @Nullable String selector, String detail) implements Untested {

        public Issue {
            if (reason == UntestedReason.HTTP_STATUS) {
                throw new IllegalArgumentException("an error status is untested with its status: " + detail);
            }
        }
    }

    record HttpResponse(int statusCode, URI url) implements Untested {

        public HttpResponse {
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("no HTTP status: " + statusCode);
            }
        }

        @Override
        public UntestedReason reason() {
            return UntestedReason.HTTP_STATUS;
        }

        @Override
        public @Nullable String selector() {
            return null;
        }

        @Override
        public String detail() {
            return "the site answered " + statusCode + " for " + url;
        }
    }
}
