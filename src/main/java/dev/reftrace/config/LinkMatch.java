package dev.reftrace.config;

import org.springframework.util.AntPathMatcher;

import java.net.URI;
import java.util.List;

public record LinkMatch(HostPattern host, String path) {

    private static final String ANY_PATH = "/**";

    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final List<String> FORBIDDEN = List.of(":", "?", "#");
    private static final List<String> UNTAKEN = List.of("//", "{", "}", "\\", "?");

    public LinkMatch {
        String hostGlob = host.pattern();
        String literal = hostGlob.startsWith("*.") ? hostGlob.substring(2) : hostGlob;
        if (literal.isEmpty() || literal.contains("*")) {
            throw new IllegalArgumentException("a host is exact or starts with '*.': " + hostGlob);
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a path glob must start with '/': " + path);
        }
        String head = path.endsWith(ANY_PATH) ? path.substring(0, path.length() - ANY_PATH.length()) : path;
        if (head.contains("**") || UNTAKEN.stream().anyMatch(path::contains)) {
            throw new IllegalArgumentException(
                    "a path glob has '*' within a segment and '/**' only at its end: " + path);
        }
    }

    static LinkMatch parse(String text) {
        String value = text.trim();
        if (FORBIDDEN.stream().anyMatch(value::contains)) {
            throw new IllegalArgumentException("a match has no scheme, port, query or fragment: " + text);
        }
        int slash = value.indexOf('/');
        return slash < 0
                ? new LinkMatch(HostPattern.of(value), ANY_PATH)
                : new LinkMatch(HostPattern.of(value.substring(0, slash)), value.substring(slash));
    }

    boolean matches(URI url) {
        String urlPath = url.getPath();
        return host.matches(url.getHost())
                && PATHS.match(path, urlPath == null || urlPath.isEmpty() ? "/" : urlPath);
    }

    @Override
    public String toString() {
        return host.pattern() + (path.equals(ANY_PATH) ? "" : path);
    }
}
