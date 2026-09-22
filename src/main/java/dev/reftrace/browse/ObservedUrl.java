package dev.reftrace.browse;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public final class ObservedUrl {

    private ObservedUrl() {
    }

    public static Optional<URI> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(text.trim()));
        } catch (URISyntaxException notAUrl) {
            return Optional.empty();
        }
    }

    public static boolean sameIgnoringFragment(URI one, URI other) {
        return withoutFragment(one).equals(withoutFragment(other));
    }

    private static URI withoutFragment(URI url) {
        return UriComponentsBuilder.fromUri(url).fragment(null).build().toUri();
    }
}
