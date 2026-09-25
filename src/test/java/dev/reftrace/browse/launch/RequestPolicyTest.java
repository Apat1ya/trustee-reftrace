package dev.reftrace.browse.launch;

import dev.reftrace.browse.UrlPatterns;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPolicyTest {

    private final RequestPolicy policy = new RequestPolicy(new UrlPatterns(
            List.of(".apk"),
            List.of("connect.facebook.net", "*.google-analytics.com", "tracker.example.com"),
            new Routes(List.of(
                    route("trustee.io", true),
                    route("trustee.io/ru/**", false),
                    route("*.app.link", false),
                    route("trusteeplus.app.link", true),
                    route("tracker.example.com", false)))));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.app.link/K1",
            "https://trustee.io/ru/cards/",
            "https://trustee.io/download/app.apk",
            "https://cdn.example.org/files/app.APK",
            "https://trusteeplus.app.link/x?r=a|b"})
    void answersWhatMustNotBeSentLocally(String url) {
        assertThat(policy.decide(url)).isInstanceOf(RequestPolicy.Fulfill204.class);
    }

    @Test
    void abortsTracking() {
        assertThat(policy.decide("https://connect.facebook.net/en_US/fbevents.js"))
                .isInstanceOf(RequestPolicy.AbortAnalytics.class);
        assertThat(policy.decide("https://region1.google-analytics.com/g/collect"))
                .isInstanceOf(RequestPolicy.AbortAnalytics.class);
    }

    @Test
    void stopsATrackerTheRoutesStopRatherThanAbortingIt() {
        assertThat(policy.decide("https://tracker.example.com/pixel"))
                .isInstanceOf(RequestPolicy.Fulfill204.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://trusteeplus.app.link/K1",
            "https://trustee.io/wallet/btc/",
            "https://t.me/trusteeplus_support",
            "mailto:support@trustee.io",
            "market://details?id=com.trusteeplus"})
    void letsEverythingElsePass(String url) {
        assertThat(policy.decide(url)).isInstanceOf(RequestPolicy.Pass.class);
    }

    private static Route route(String match, boolean follow) {
        return new Route(match, List.of(Fixtures.match(match)), follow, List.of());
    }
}
