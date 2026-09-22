package dev.reftrace.judge;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.Untested;
import dev.reftrace.config.Expectation;
import org.jspecify.annotations.Nullable;

import java.util.List;

public sealed interface Check {

    record Pass(FoundLink link) implements Check {
    }

    record Mismatch(FoundLink link, String route, ReferralKey key, List<Unmet> failed) implements Check {

        public Mismatch {
            failed = List.copyOf(failed);
            if (failed.isEmpty()) {
                throw new IllegalArgumentException("a mismatch fails at least one expectation: " + link);
            }
            boolean onArrival = failed.stream().anyMatch(unmet -> unmet.expectation() instanceof Expectation.OnArrival);
            if (onArrival && failed.size() != 1) {
                throw new IllegalArgumentException("a mismatch on arrival fails one expectation: " + failed);
            }
        }

        static Mismatch onLink(FoundLink link, String route, List<? extends Expectation.OnLink> failed,
                               ReferralKey key) {
            return new Mismatch(link, route, key, failed.stream()
                    .map(expectation -> new Unmet(expectation, Judge.found(expectation, link.url())))
                    .toList());
        }

        static Mismatch onArrival(FoundLink link, String route, Expectation.OnArrival failed, ReferralKey key,
                                  @Nullable String actual) {
            return new Mismatch(link, route, key, List.of(new Unmet(failed, actual)));
        }

        public boolean isOnArrival() {
            return failed.getFirst().expectation() instanceof Expectation.OnArrival;
        }

        public record Unmet(Expectation expectation, @Nullable String actual) {
        }
    }

    record NotTested(Untested untested) implements Check {
    }

    record Failed() implements Check {
    }

    default Outcome outcome() {
        return switch (this) {
            case Pass _ -> Outcome.PASS;
            case Mismatch _ -> Outcome.MISMATCH;
            case NotTested(Untested untested) -> untested.reason().bySite() ? Outcome.UNTESTED : Outcome.FAILED;
            case Failed _ -> Outcome.FAILED;
        };
    }
}
