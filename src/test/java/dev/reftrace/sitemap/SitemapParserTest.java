package dev.reftrace.sitemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SitemapParserTest {

    @Test
    void readsTheIndexAndTheFilesItNames() {
        Sitemap sitemap = parse("main-sitemap.xml");

        assertThat(sitemap).isInstanceOf(SitemapIndex.class);
        assertThat(((SitemapIndex) sitemap).files())
                .containsExactly(URI.create("https://trustee.io/sitemaps/static/en.xml"),
                        URI.create("https://trustee.io/sitemaps/static/uk.xml"),
                        URI.create("https://trustee.io/sitemaps/dynamic/en.xml"));
    }

    @Test
    void readsPagesAndKeepsSlugsAsWritten() {
        UrlSet urlset = urlSet("static-en.xml");

        assertThat(urlset.pages()).extracting(URI::toString)
                .containsExactly("https://trustee.io/", "https://trustee.io/wallet/btc/",
                        "https://trustee.io/wiki/trustee-plus/Pin-codes/",
                        "https://trustee.io/academy/old-article/");
    }

    @Test
    void doesNotDeriveTheLocaleFromTheFileName() {
        assertThat(urlSet("static-uk.xml").pages()).extracting(URI::toString)
                .allMatch(url -> url.startsWith("https://trustee.io/ua/"))
                .contains("https://trustee.io/ua/academy/shho-take-def%D1%96-prostimi-slovami/");
    }

    @Test
    void readsAPageWhateverItsTimestampLooksLike() {
        UrlSet urlset = urlSet("dynamic-en.xml");

        assertThat(urlset.pages()).hasSize(3);
        assertThat(urlset.pages().get(2)).hasToString("https://trustee.io/rates/top/gainers/?sort=day&view=table");
    }

    @Test
    void readsAFileThatStartsWithAByteOrderMark() {
        byte[] body = fixture("static-uk.xml");
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(((UrlSet) SitemapParser.parse("static-uk.xml", withBom)).pages()).hasSize(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFiles")
    void aBadFileIsRefused(String what, String name, byte[] body, String message) {
        assertThatExceptionOfType(SitemapFormatException.class)
                .isThrownBy(() -> SitemapParser.parse(name, body))
                .withMessageStartingWith(message);
    }

    static Stream<Arguments> badFiles() {
        return Stream.of(
                Arguments.of("the home page served instead", "home.html", fixture("home.html"),
                        "home.html is not"),
                Arguments.of("another root element", "/sitemap.xml", bytes("<html><body>home</body></html>"),
                        "/sitemap.xml is not a sitemap: its root element is <html>"),
                Arguments.of("an empty body", "/main-sitemap.xml", new byte[0],
                        "/main-sitemap.xml is not readable xml"),
                Arguments.of("a file that breaks off half way", "/sitemaps/static/en.xml", bytes("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                        <url><loc>https://trustee.io/wallet/btc/</loc></url>
                        <url><loc>https://trustee.io/wallet/e"""), "/sitemaps/static/en.xml is not readable xml"),
                Arguments.of("a location that is not a url", "broken-entry.xml", fixture("broken-entry.xml"),
                        "broken-entry.xml lists a location that is not a url: not a url at all"),
                Arguments.of("an entry without a location", "/sitemaps/static/en.xml", bytes("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                          <url><lastmod>2026-05-04</lastmod></url>
                        </urlset>
                        """), "/sitemaps/static/en.xml has an entry without a location"),
                Arguments.of("a relative location", "/sitemaps/static/en.xml", bytes("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                          <url><loc>/wallet/btc/</loc></url>
                        </urlset>
                        """), "/sitemaps/static/en.xml lists a location that is not absolute: /wallet/btc/"),
                Arguments.of("a document type declaration", "/main-sitemap.xml", bytes("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE urlset [<!ENTITY secret SYSTEM "file:///etc/hosts">]>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                          <url><loc>&secret;</loc></url>
                        </urlset>
                        """), "/main-sitemap.xml is not readable xml"));
    }

    private static UrlSet urlSet(String fixture) {
        return (UrlSet) parse(fixture);
    }

    private static Sitemap parse(String fixture) {
        return SitemapParser.parse(fixture, fixture(fixture));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixture(String name) {
        try (InputStream stream = SitemapParserTest.class.getResourceAsStream("/sitemaps/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture /sitemaps/" + name);
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
