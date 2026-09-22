package dev.reftrace.browse;

import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UrlPatternsTest {

    private final UrlPatterns patterns = new UrlPatterns(
            List.of(".apk"),
            List.of("connect.facebook.net", "*.google-analytics.com", "127.0.0.1:9999"),
            new Routes(List.of(
                    route("trustee.io", true),
                    route("*.app.link", false),
                    route("t.ki", false))));

    @ParameterizedTest
    @CsvSource({
            "https://trusteeplus.app.link/Adp7LiveChk3?r=Adp7LiveChk3,       true",
            "https://t.ki./banner_pl,                                        true",
            "https://trustee.io/wallet/btc/,                                 false",
            "https://www.facebook.com/trusteeplus,                           false",
            "https://trusteeglobal.com/download/trusteeplus.apk?r=K1,        true",
            "https://trustee.io/download/app.APK,                            true",
            "https://cdn.example.org/files/app.apk.html,                     false",
            "https://user:pw@other.test:8443/app.apk?c=d,                    true",
            "mailto:support@trustee.io,                                      false",
            "javascript:void(0),                                             false",
            "not a url at all,                                               false",
            "https://trusteeplus.app.link/x?r=a|b,                           false"})
    void isExit(String url, boolean exit) {
        assertThat(patterns.isExit(url)).isEqualTo(exit);
    }

    @ParameterizedTest
    @CsvSource({
            "https://connect.facebook.net/en_US/fbevents.js,  true",
            "https://region1.google-analytics.com/g/collect,  true",
            "https://user:pw@Connect.Facebook.NET:8443/x?c=d, true",
            "http://127.0.0.1:41234/pixel,                    true",
            "http://localhost:41234/pixel,                    false",
            "https://trustee.io/main.js,                      false",
            "nonsense,                                        false"})
    void isAnalytics(String url, boolean analytics) {
        assertThat(patterns.isAnalytics(url)).isEqualTo(analytics);
    }

    @Test
    void stopsAWebRequestNobodyCanRead() {
        assertThat(UrlPatterns.unreadableWebUrl("https://trusteeplus.app.link/x?r=a|b")).isTrue();
        assertThat(UrlPatterns.unreadableWebUrl("https://trustee.io/buy/btc/?r=Rf7xQ2mK9pL")).isFalse();
        assertThat(UrlPatterns.unreadableWebUrl("market://details?id=com.trusteeplus")).isFalse();
        assertThat(UrlPatterns.unreadableWebUrl("intent://scan#Intent;scheme=zxing;end")).isFalse();
    }

    private static Route route(String match, boolean follow) {
        return new Route(match, List.of(Fixtures.match(match)), follow, List.of());
    }
}
