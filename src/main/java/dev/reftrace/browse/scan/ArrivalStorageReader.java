package dev.reftrace.browse.scan;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ArrivalStorageReader {

    private final Routes routes;

    ArrivalStorageReader(Routes routes) {
        this.routes = routes;
    }

    List<StoredValue> read(PageDriver page, FoundLink followed, List<Untested> untested) {
        Optional<Route> route = routes.routeFor(followed.url());
        if (route.isEmpty()) {
            return List.of();
        }
        List<StoredValue> stored = new ArrayList<>();
        for (Expectation.OnArrival expectation : route.get().onArrival()) {
            try {
                stored.add(new StoredValue(followed, route.get().name(), expectation, page.stored(expectation)));
            } catch (PageCheckException failed) {
                if (failed.error().kind() == UntestedReason.BROWSER_CRASH) {
                    throw failed;
                }
                untested.add(new Untested.Issue(failed.error().kind(), null, "the " + name(expectation)
                        + " could not be read: " + failed.error().message()));
            }
        }
        return List.copyOf(stored);
    }

    private static String name(Expectation.OnArrival expectation) {
        return switch (expectation) {
            case Expectation.Cookie(String name) -> "cookie " + name;
            case Expectation.LocalStorage(String name) -> "local-storage item " + name;
        };
    }
}
