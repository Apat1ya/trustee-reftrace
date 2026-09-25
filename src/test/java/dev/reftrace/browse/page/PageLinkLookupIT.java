package dev.reftrace.browse.page;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.reftrace.browse.ObservedUrl;
import dev.reftrace.browse.UrlPatterns;
import dev.reftrace.browse.launch.PlaywrightFixture;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class PageLinkLookupIT {

    private static final Routes SHIPPED = routes(
            route(true, "trustee.io", "*.trustee.io"),
            route(false, "*.app.link", "app.link", "*.branch.io", "t.ki"),
            route(false, "trusteeplus.app.link"),
            route(false, "apps.apple.com", "itunes.apple.com", "play.google.com"),
            route(false, "t.ki/banner*"));

    private static final Routes OPENED = routes(
            route(false, "*.app.link"),
            route(true, "trusteeplus.app.link"),
            route(true, "trustee.io"),
            route(false, "trustee.io/partners/**"),
            route(true, "trustee.io/partners/open/**"),
            route(false, "trustee.io/*/cards"));

    private static final Routes CLOSED = routes(
            route(true, "trusteeplus.app.link"),
            route(false, "*.app.link"),
            route(false, "trustee.io/partners/**"),
            route(true, "trustee.io"));

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void startTheBrowser() {
        playwright = PlaywrightFixture.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void stopTheBrowser() {
        browser.close();
        playwright.close();
    }

    @Test
    void theShippedRoutesReadTheSameInThePage() {
        assertTheSameAnswers(SHIPPED,
                "https://trustee.io/", "https://pay.trustee.io/cards/",
                "https://trusteeplus.app.link/Rf7xQ2mK9pL", "https://other.app.link/x", "https://app.link/",
                "https://api2.branch.io/v1/open",
                "https://t.ki/Rf7xQ2mK9pL", "https://t.ki/banner", "https://t.ki/banner_pl", "http://t.ki:8080/banner_eu",
                "https://t.ki/banner/extra", "https://t.ki/Banner", "https://t.ki/%62anner", "https://t.ki//banner",
                "https://play.google.com/store/apps/details?id=com.trusteeplus", "https://apps.apple.com/pl/app/x/id1",
                "https://example.com/", "https://example.com/app.apk", "https://trustee.io/files/Trustee.APK",
                "https://example.com/app%2Eapk", "https://trustee.io/%zz",
                "https://t.ki./Rf7xQ2mK9pL", "https://t.ki./banner", "https://trusteeplus.app.link./Rf7xQ2mK9pL");
    }

    @Test
    void aLaterRouteOpensWhatAnEarlierOneStoppedInThePageToo() {
        assertTheSameAnswers(OPENED,
                "https://trusteeplus.app.link/Rf7xQ2mK9pL", "https://other.app.link/Rf7xQ2mK9pL",
                "https://trustee.io/partners/", "https://trustee.io/partners", "https://trustee.io/partnership",
                "https://trustee.io/partners/open/x", "https://trustee.io/partners/open",
                "https://trustee.io/partners%2Fopen%2Fx", "https://trustee.io//partners/x",
                "https://trustee.io/ua/cards", "https://trustee.io/ua/cards/", "https://trustee.io/ua/x/cards");
    }

    @Test
    void aLaterRouteStopsWhatAnEarlierOneOpenedInThePageToo() {
        assertTheSameAnswers(CLOSED,
                "https://trusteeplus.app.link/Rf7xQ2mK9pL", "https://other.app.link/Rf7xQ2mK9pL",
                "https://trustee.io/partners/x", "https://trustee.io/");
    }

    private static void assertTheSameAnswers(Routes routes, String... urls) {
        UrlPatterns patterns = new UrlPatterns(List.of(".apk"), List.of(), routes);
        try (Page page = browser.newPage()) {
            page.setContent("<!doctype html><title>lookup</title>");
            new PageScripts(patterns, List.of()).ensureInstalled(page);
            List<String> hrefs = strings(page.evaluate("urls => urls.map(url => new URL(url).href)",
                    Arrays.asList(urls)));
            List<String> inThePage = strings(page.evaluate("""
                    hrefs => hrefs.map(href => href + ' exit=' + window.__reftrace.isExitUrl(href)
                        + ' host=' + window.__reftrace.isExitHostUrl(href))""", hrefs));
            List<String> inJava = hrefs.stream()
                    .map(href -> href + " exit=" + patterns.isExit(href) + " host=" + decidedByAWholeHost(routes, href))
                    .toList();

            assertThat(inThePage).containsExactlyElementsOf(inJava);
        }
    }

    private static boolean decidedByAWholeHost(Routes routes, String href) {
        Routes perMatch = new Routes(routes.all().stream()
                .flatMap(route -> route.match().stream()
                        .map(match -> new Route(route.name(), List.of(match), route.follow(), List.of())))
                .toList());
        return ObservedUrl.parse(href).flatMap(perMatch::routeFor)
                .map(route -> !route.follow() && route.match().getFirst().path().equals("/**"))
                .orElse(false);
    }

    private static List<String> strings(@Nullable Object answer) {
        assertThat(answer).isInstanceOf(List.class);
        return ((List<?>) Objects.requireNonNull(answer)).stream().map(String::valueOf).toList();
    }

    private static Routes routes(Route... routes) {
        return new Routes(List.of(routes));
    }

    private static Route route(boolean follow, String... matches) {
        return new Route(String.join(" ", matches),
                Arrays.stream(matches).map(Fixtures::match).toList(), follow, List.of());
    }
}
