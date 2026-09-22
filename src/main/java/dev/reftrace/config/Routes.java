package dev.reftrace.config;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public final class Routes {

    private final List<Route> routes;

    public Routes(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    public List<Route> all() {
        return routes;
    }

    public Optional<Route> routeFor(URI url) {
        return routes.reversed().stream().filter(route -> route.matches(url)).findFirst();
    }

    public boolean followed(URI url) {
        return routeFor(url).map(Route::follow).orElse(false);
    }
}
