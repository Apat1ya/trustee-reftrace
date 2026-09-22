package dev.reftrace.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RoutesTest {

    @Test
    void theLastMatchingRouteWins() {
        Routes routes = new Routes(List.of(
                route(true, "trustee.io", "*.trustee.io"),
                route(false, "trustee.io/blog/**"),
                route(true, "trustee.io/blog/open/**"),
                route(false, "pay.trustee.io"),
                route(false, "*.app.link"),
                route(true, "trusteeplus.app.link")));

        assertThat(routes.followed(URI.create("https://trustee.io/ua/cards/"))).isTrue();
        assertThat(routes.followed(URI.create("https://trustee.io/blog/2026/news"))).isFalse();
        assertThat(routes.followed(URI.create("https://trustee.io/blog/open/news"))).isTrue();
        assertThat(routes.routeFor(URI.create("https://trustee.io/blog/"))).contains(routes.all().get(1));
        assertThat(routes.followed(URI.create("https://pay.trustee.io/"))).isFalse();
        assertThat(routes.followed(URI.create("https://card.trustee.io/"))).isTrue();
        assertThat(routes.followed(URI.create("https://trusteeplus.app.link/Rf7xQ2mK9pL"))).isTrue();
        assertThat(routes.followed(URI.create("https://other.app.link/Rf7xQ2mK9pL"))).isFalse();
        assertThat(routes.routeFor(URI.create("https://example.com/"))).isEmpty();
        assertThat(routes.followed(URI.create("https://example.com/"))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "*.trustee.io,          https://pay.trustee.io/,          true",
            "*.trustee.io,          https://trustee.io/,              false",
            "trustee.io,            https://trustee.io,               true",
            "trustee.io/,           https://trustee.io/,              true",
            "trustee.io/ua/*,       https://trustee.io/ua/cards,      true",
            "trustee.io/ua/*,       https://trustee.io/ua/cards/visa, false",
            "t.ki/banner*,          https://t.ki/banner_pl,           true",
            "trustee.io/*/cards/**, https://trustee.io/ua/cards,      true"})
    void matches(String match, URI url, boolean matches) {
        assertThat(LinkMatch.parse(match).matches(url)).isEqualTo(matches);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://trustee.io", "trustee.io:443", "trustee.io/?r=1", "trustee.io/#top",
            "tru*stee.io", "*", "trustee.io/*/x#", "t.ki/banner**", "trustee.io/**/cards", "trustee.io/a/**/b/**",
            "trustee.io/{lang}/**", "trustee.io/ua//cards", "trustee.io/ua\\cards", "trustee.io/***"})
    void refusesAMatchItCannotReadLikeThePage(String match) {
        assertThatIllegalArgumentException().isThrownBy(() -> LinkMatch.parse(match));
    }

    private static Route route(boolean follow, String... match) {
        return new Route(String.join(" ", match), Arrays.stream(match).map(LinkMatch::parse).toList(), follow,
                List.of());
    }
}
