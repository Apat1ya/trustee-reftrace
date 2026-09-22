package dev.reftrace.sitemap;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    @Test
    void addressesAPageByFixedRulesOnly() {
        assertThat(UrlNormalizer.address(URI.create("HTTPS://Trustee.IO:443?r=K1#top"), "r"))
                .contains(URI.create("https://trustee.io/"));
        assertThat(UrlNormalizer.address(URI.create("http://trustee.io:8080/wiki/trustee-plus/Pin-codes?page=2&r=K1"),
                "r")).contains(URI.create("http://trustee.io:8080/wiki/trustee-plus/Pin-codes?page=2"));
        assertThat(UrlNormalizer.address(URI.create("https://trustee.io/%D0%BA%D0%B0%D1%80%D1%82%D1%8B/"), "r"))
                .contains(URI.create("https://trustee.io/%D0%BA%D0%B0%D1%80%D1%82%D1%8B/"));
    }

    @Test
    void addressesNothingABrowserWouldNotOpenAsAPage() {
        assertThat(UrlNormalizer.address(URI.create("/wallet/btc/"), "r")).isEmpty();
        assertThat(UrlNormalizer.address(URI.create("mailto:help@trustee.io"), "r")).isEmpty();
        assertThat(UrlNormalizer.address(URI.create("ftp://trustee.io/"), "r")).isEmpty();
    }
}
