package dev.reftrace.sitemap;

import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.Start;
import dev.reftrace.testsupport.Fixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StartPagesTest {

    private static final String INDEX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <sitemap><loc>%1$s/sitemaps/en.xml</loc></sitemap>
              <sitemap><loc>%1$s/sitemaps/ru.xml</loc></sitemap>
              <sitemap><loc>%1$s/main-sitemap.xml</loc></sitemap>
            </sitemapindex>
            """;
    private static final String EN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>%1$s/</loc></url>
              <url><loc>%1$s/wallet/btc/</loc><lastmod>2026-09-21T19:49:31.985Z</lastmod></url>
              <url><loc>%1$s/wiki/trustee-plus/Pin-codes/</loc></url>
              <url><loc>https://elsewhere.test/page/</loc></url>
            </urlset>
            """;

    private final LocalSite site = new LocalSite();

    @AfterEach
    void stopSite() {
        site.close();
    }

    @Test
    void joinsPagesAndSitemapPagesUnderOneAddressEach() {
        serveSitemaps();
        String authority = site.baseUrl().getRawAuthority();

        StartPages.Resolved resolved = startPages(followed(site.baseUrl().getHost())).resolve(List.of(
                new Start.Page(URI.create("HTTP://" + authority + "?r=K1#top")),
                new Start.Page(site.url("/academy/?page=2&r=K1")),
                new Start.Page(site.url("/academy/?page=2")),
                new Start.Sitemap(site.url("/main-sitemap.xml"))));

        assertThat(resolved.pages()).containsExactly(
                site.url("/"),
                site.url("/academy/?page=2"),
                site.url("/wallet/btc/"),
                site.url("/wiki/trustee-plus/Pin-codes/"),
                site.url("/ru/"),
                site.url("/ru/wallet/btc/"));
        assertThat(resolved.unread()).isEmpty();
    }

    @Test
    void leavesOutWhatALaterRouteSwitchesOff() {
        serveSitemaps();
        String host = site.baseUrl().getHost();
        Routes routes = new Routes(List.of(
                new Route(host, List.of(Fixtures.match(host)), true, List.of()),
                new Route(host + "/ru/**", List.of(Fixtures.match(host + "/ru/**")), false, List.of())));

        StartPages.Resolved resolved = startPages(routes).resolve(List.of(
                new Start.Page(site.url("/ru/")),
                new Start.Sitemap(site.url("/main-sitemap.xml"))));

        assertThat(resolved.pages()).containsExactly(
                site.url("/"), site.url("/wallet/btc/"), site.url("/wiki/trustee-plus/Pin-codes/"));
    }

    @Test
    void keepsThePagesWhenASitemapCannotBeRead() {
        site.serve("/main-sitemap.xml", "application/xml", INDEX.formatted(site.baseUrl()));
        site.serve("/sitemaps/en.xml", "application/xml", EN.formatted(site.baseUrl()));
        site.answerStatus("/sitemaps/ru.xml", 404);
        site.serve("/sitemap.xml", "text/html", "<!DOCTYPE html><html><body>home</body></html>");

        StartPages.Resolved resolved = startPages(followed(site.baseUrl().getHost())).resolve(List.of(
                new Start.Page(site.url("/academy/")),
                new Start.Sitemap(site.url("/sitemap.xml")),
                new Start.Sitemap(site.url("/main-sitemap.xml"))));

        assertThat(resolved.pages()).containsExactly(
                site.url("/academy/"), site.url("/"), site.url("/wallet/btc/"),
                site.url("/wiki/trustee-plus/Pin-codes/"));
        assertThat(resolved.unread()).extracting(StartPages.UnreadSitemap::sitemap)
                .containsExactly(site.url("/sitemap.xml"), site.url("/sitemaps/ru.xml"));
        assertThat(resolved.unread().get(1).reason()).contains("answered 404");
    }

    @Test
    void readsEachSitemapFileOnce() {
        serveSitemaps();

        startPages(followed(site.baseUrl().getHost())).resolve(List.of(
                new Start.Sitemap(site.url("/main-sitemap.xml"))));

        assertThat(site.requestLog()).containsExactly(
                "GET /main-sitemap.xml", "GET /sitemaps/en.xml", "GET /sitemaps/ru.xml");
    }

    private void serveSitemaps() {
        site.serve("/main-sitemap.xml", "application/xml", INDEX.formatted(site.baseUrl()));
        site.serve("/sitemaps/en.xml", "application/xml", EN.formatted(site.baseUrl()));
        site.serve("/sitemaps/ru.xml", "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>%1$s/ru/</loc></url>
                  <url><loc>%1$s/ru/wallet/btc/</loc></url>
                </urlset>
                """.formatted(site.baseUrl()));
    }

    private static Routes followed(String host) {
        return new Routes(List.of(new Route(host, List.of(Fixtures.match(host)), true, List.of())));
    }

    private static StartPages startPages(Routes routes) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new StartPages(RestClient.builder().requestFactory(factory).build(), routes, "r");
    }
}
