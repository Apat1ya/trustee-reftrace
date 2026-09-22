package dev.reftrace.browse;

import java.net.URI;
import java.time.Duration;

public sealed interface ClickObservation {

    boolean pageIntact();

    record Navigated(URI url, boolean pageIntact) implements ClickObservation {
    }

    record NoNavigation(Duration waited) implements ClickObservation {

        @Override
        public boolean pageIntact() {
            return true;
        }
    }

    record NotReachable() implements ClickObservation {

        @Override
        public boolean pageIntact() {
            return true;
        }
    }

    record Failed(TechnicalError error, boolean pageIntact) implements ClickObservation {
    }

    static ClickObservation navigated(String url, boolean pageIntact) {
        return ObservedUrl.parse(url)
                .<ClickObservation>map(navigatedUrl -> new Navigated(navigatedUrl, pageIntact))
                .orElseGet(() -> new Failed(new TechnicalError(UntestedReason.NAVIGATION_ERROR,
                        "the url the click reached cannot be read as a url: " + url.trim()), pageIntact));
    }
}
