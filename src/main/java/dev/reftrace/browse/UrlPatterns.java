package dev.reftrace.browse;

import dev.reftrace.config.HostPattern;
import dev.reftrace.config.Routes;
import org.springframework.web.util.InvalidUrlException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class UrlPatterns {

    private final List<String> exitPathSuffixes;
    private final List<HostPattern> analyticsHosts;
    private final Routes routes;

    public UrlPatterns(List<String> exitPathSuffixes, List<String> analyticsHostGlobs, Routes routes) {
        this.routes = routes;
        this.exitPathSuffixes = lowerCase(exitPathSuffixes);
        this.analyticsHosts = compile(analyticsHostGlobs);
    }

    public Routes routes() {
        return routes;
    }

    public List<String> exitPathSuffixes() {
        return exitPathSuffixes;
    }

    public boolean isExit(String url) {
        return isNotFollowed(url) || matchesPathSuffix(url);
    }

    private boolean isNotFollowed(String url) {
        return hostOf(url).isPresent()
                && ObservedUrl.parse(url).flatMap(routes::routeFor).map(route -> !route.follow()).orElse(false);
    }

    public boolean isAnalytics(String url) {
        return matchesHost(analyticsHosts, url);
    }

    private static Optional<String> hostOf(String url) {
        return ObservedUrl.parse(url).map(URI::getHost).map(host -> host.toLowerCase(Locale.ROOT));
    }

    private static String pathOf(String url) {
        return ObservedUrl.parse(url).map(URI::getPath).orElse("");
    }

    public static boolean unreadableWebUrl(String url) {
        return isWebScheme(url) && hostOf(url).isEmpty();
    }

    private static boolean isWebScheme(String url) {
        try {
            String scheme = UriComponentsBuilder.fromUriString(url.trim(), UriComponentsBuilder.ParserType.WHAT_WG)
                    .build().getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (InvalidUrlException notAUrl) {
            return false;
        }
    }

    private boolean matchesPathSuffix(String url) {
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        return exitPathSuffixes.stream().anyMatch(path::endsWith);
    }

    private static boolean matchesHost(List<HostPattern> patterns, String url) {
        return hostOf(url).filter(host -> patterns.stream().anyMatch(pattern -> pattern.matches(host))).isPresent();
    }

    private static List<HostPattern> compile(List<String> globs) {
        return globs.stream().filter(glob -> !glob.isBlank()).map(HostPattern::of).toList();
    }

    private static List<String> lowerCase(List<String> values) {
        return values.stream().filter(value -> !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).toList();
    }
}
