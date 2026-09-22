package dev.reftrace.judge;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Judge {

    private final Routes routes;

    public Judge(Routes routes) {
        this.routes = routes;
    }

    public List<Check> judgePage(PageScan scan, ReferralKey key) {
        return Stream.of(scan.links().stream().flatMap(link -> judgeLink(link, key).stream()),
                        scan.stored().stream().map(stored -> judgeStored(stored, key)),
                        scan.untested().stream().map(Check.NotTested::new))
                .<Check>flatMap(checks -> checks)
                .toList();
    }

    private Optional<Check> judgeLink(FoundLink link, ReferralKey key) {
        return routes.routeFor(link.url())
                .map(route -> judgeOnRoute(link, route, key));
    }

    private static @Nullable Check judgeOnRoute(FoundLink link, Route route, ReferralKey key) {
        List<Expectation.OnLink> applying = applying(route, link.url());
        if (applying.isEmpty()) {
            return null;
        }
        List<Expectation.OnLink> failed = applying.stream()
                .filter(expectation -> !holds(expectation, link.url(), key))
                .toList();
        return failed.isEmpty() ? new Check.Pass(link) : Check.Mismatch.onLink(link, route.name(), failed, key);
    }

    public static List<Expectation.OnLink> applying(Route route, URI url) {
        return route.onLink().stream()
                .filter(expectation -> !(expectation instanceof Expectation.QueryIfPresent)
                        || found(expectation, url) != null)
                .toList();
    }

    private Check judgeStored(StoredValue stored, ReferralKey key) {
        return key.value().equals(stored.value())
                ? new Check.Pass(stored.followed())
                : Check.Mismatch.onArrival(stored.followed(), stored.route(), stored.expectation(), key,
                        stored.value());
    }

    public static @Nullable String found(Expectation.OnLink expectation, URI url) {
        UriComponents components = UriComponentsBuilder.fromUri(url).build(true);
        return switch (expectation) {
            case Expectation.PathSegment(int index) -> components.getPathSegments().size() >= index
                    ? components.getPathSegments().get(index - 1)
                    : null;
            case Expectation.Query(String name) -> query(components, name);
            case Expectation.QueryIfPresent(String name) -> query(components, name);
            case Expectation.Fail _ -> null;
        };
    }

    private static @Nullable String query(UriComponents components, String name) {
        @Nullable List<String> values = components.getQueryParams().get(name);
        return values == null ? null : values.stream()
                .map(value -> Objects.requireNonNullElse(value, ""))
                .collect(Collectors.joining(","));
    }

    private static boolean holds(Expectation.OnLink expectation, URI url, ReferralKey key) {
        return switch (expectation) {
            case Expectation.PathSegment _, Expectation.Query _, Expectation.QueryIfPresent _ -> key.value().equals(found(expectation, url));
            case Expectation.Fail _ -> false;
        };
    }
}
