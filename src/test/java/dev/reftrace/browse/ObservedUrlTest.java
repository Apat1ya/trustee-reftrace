package dev.reftrace.browse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedUrlTest {

    private static final String KEY = "Sp1keKey9Zx";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://trusteeplus.app.link/Sp1keKey9Zx?r=Sp1keKey9Zx",
            "https://trusteeplus.app.link/%53p1keKey9Zx",
            "http://trusteeplus.app.link",
            "https://play.google.com/store/apps/details?id=com.trusteeplus&hl=en",
            "https://trusteeglobal.com/download/trusteeplus.apk",
            "market://details?id=com.trusteeplus",
            "market:details?id=com.trusteeplus",
            "itms-apps://itunes.apple.com/app/id1634455978",
            "intent://trusteeplus.app.link/Sp1keKey9Zx#Intent;scheme=https;package=com.trusteeplus;end",
            "javascript:void(0)",
            "mailto:support@trustee.io",
            "tel:+380000000000",
            "#download",
            "/referral-program/?r=Sp1keKey9Zx",
            "../wallet/btc/",
            "",
            "https://trustee.io/uk/гаманець/"})
    void readsEveryUrlAPageCanOffer(String href) {
        assertThat(ObservedUrl.parse(href)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://trusteeplus.app.link/Sp1keKey 9Zx",
            "https://exa mple.com/broken",
            "https://trusteeplus.app.link/{key}",
            "https://",
            "://",
            "%%%",
            "https://trusteeplus.app.link/%zz",
            "https://trustee.io/?r=%zz",
            "\\\\server\\share",
            "h t t p",
            "data:text/html,<a href=x>go</a>",
            "http://[bad"})
    void refusesTextThatIsNotAUrl(String text) {
        assertThat(ObservedUrl.parse(text)).isEmpty();
    }

    @Test
    @SuppressWarnings("NullAway")
    void treatsAMissingHrefAsUnreadableRatherThanCrashing() {
        assertThat(ObservedUrl.parse(null)).isEmpty();
    }

    @Test
    void comparesTwoUrlsWithoutTheirFragments() {
        URI href = URI.create("https://trusteeplus.app.link/" + KEY + "?r=a%20b");

        assertThat(ObservedUrl.sameIgnoringFragment(href, URI.create(href + "#top"))).isTrue();
        assertThat(ObservedUrl.sameIgnoringFragment(URI.create(href + "#one"), URI.create(href + "#two"))).isTrue();
        assertThat(ObservedUrl.sameIgnoringFragment(href, URI.create(href + "#"))).isTrue();
        assertThat(ObservedUrl.sameIgnoringFragment(href, URI.create("https://trusteeplus.app.link/" + KEY)))
                .isFalse();
        assertThat(ObservedUrl.sameIgnoringFragment(URI.create("https://trusteeplus.app.link/K3"),
                URI.create("https://trusteeplus.app.link/%4B3"))).isFalse();
    }

    @Test
    void dropsWhitespaceAroundAnHrefBeforeReadingIt() {
        assertThat(ObservedUrl.parse("  https://trusteeplus.app.link/" + KEY + "  \n"))
                .contains(URI.create("https://trusteeplus.app.link/" + KEY));
    }
}
