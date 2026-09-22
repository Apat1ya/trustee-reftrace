package dev.reftrace.sitemap;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    public static Optional<URI> address(URI url, String keyParam) {
        String scheme = url.getScheme();
        String host = url.getHost();
        if (!url.isAbsolute() || url.isOpaque() || host == null || !isHttp(scheme)) {
            return Optional.empty();
        }
        String path = url.getRawPath();
        int port = url.getPort();
        return Optional.of(UriComponentsBuilder.fromUri(url)
                .scheme(scheme.toLowerCase(Locale.ROOT))
                .host(host.toLowerCase(Locale.ROOT))
                .port(port == defaultPort(scheme) ? -1 : port)
                .replacePath(path == null || path.isEmpty() ? "/" : path)
                .replaceQueryParam(keyParam)
                .fragment(null)
                .build(true)
                .toUri());
    }

    private static boolean isHttp(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
