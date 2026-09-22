package dev.reftrace.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HostPatternTest {

    @ParameterizedTest
    @CsvSource({
            "*.app.link,        trusteeplus.app.link,      true",
            "*.app.link,        a.b.app.link,              true",
            "app.link,          trusteeplus.app.link,      false",
            "app.link,          app.link,                  true",
            "*,                 anything.test,             true",
            "*store*,           apps.store.example,        true",
            "*store*,           apps.example,              false",
            "*.App.Link,        TRUSTEEPLUS.APP.LINK,      true",
            "t.ki:443,          t.ki,                      true",
            "127.0.0.1,         127.0.0.1:52341,           true",
            "t.ki,              t.ki.,                     true",
            "t.ki.,             t.ki,                      true",
            "play.google.com,   play.google.com.evil.test, false",
            "*.branch.io,       branch.io.evil.test,       false",
            "app.link,          '',                        false"})
    void matches(String pattern, String host, boolean matches) {
        assertThat(HostPattern.of(pattern).matches(host)).isEqualTo(matches);
    }

    @Test
    void keepsTheNormalisedPatternAndRefusesABlankOne() {
        assertThat(HostPattern.of("  *.App.Link:443 ").pattern()).isEqualTo("*.app.link");
        assertThat(HostPattern.of("app.link").matches(null)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> HostPattern.of(" "));
    }
}
