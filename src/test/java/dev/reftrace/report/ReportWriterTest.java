package dev.reftrace.report;

import dev.reftrace.browse.BrowserIdentity;
import dev.reftrace.browse.DeviceEmulation;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.browse.Viewport;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Expectation;
import dev.reftrace.crawl.Arrival;
import dev.reftrace.crawl.Landing;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.crawl.Step;
import dev.reftrace.crawl.Walker;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.ReferralKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String RUN_ID = "20260923T043900Z-7f3a";
    private static final ReferralKey K1 = ReferralKey.of("K1aaaaaaaaa");
    private static final ReferralKey K2 = ReferralKey.of("K2bbbbbbbbb");
    private static final URI HOME = URI.create("https://trustee.io/");
    private static final URI CARDS = URI.create("https://trustee.io/ua/cards/");

    private static final DeviceProfile DESKTOP = profile("desktop-chrome", BrowserEngine.CHROMIUM);
    private static final DeviceProfile IPHONE = profile("iphone-safari", BrowserEngine.WEBKIT);
    private static final List<String> ENTRY = List.of("entry", "browse", "key-replaced");
    private static final List<String> KEY_REPLACED = List.of("key-replaced");
    private static final List<String> SCENARIOS = List.of("entry", "browse", "key-replaced", "reload");

    private static final FoundLink CARDS_LINK = link(CARDS.toString(), "a.nav__cards", "Карты");
    private static final FoundLink DOWNLOAD_K1 = link("https://trusteeplus.app.link/K1aaaaaaaaa", "a.hero__btn",
            "Скачать");
    private static final FoundLink FOOTER_K2 = link("https://trusteeplus.app.link/K2bbbbbbbbb", "footer a.app",
            "App");
    private static final FoundLink APP_K1 = link("https://trusteeplus.app.link/K1aaaaaaaaa", "a.hero__btn", "Скачать");
    private static final FoundLink STORE = link("https://apps.apple.com/app/id1634455978", "footer a.store", "");

    private static final String GOLDEN = """
            {
              "run" : {
                "id" : "20260923T043900Z-7f3a",
                "site" : "https://trustee.io",
                "startedAt" : "2026-09-23T04:39:00Z",
                "finishedAt" : "2026-09-23T05:10:00Z",
                "profiles" : [ "desktop-chrome", "iphone-safari" ]
              },
              "coverage" : {
                "pages" : { "start" : 2, "unique" : 3, "visits" : 4, "repeats" : 1, "maxDepth" : 3 },
                "scenarios" : { "entry" : 3, "browse" : 3, "key-replaced" : 4, "reload" : 0 },
                "links" : { "followed" : 5, "checked" : 5, "ignored" : 9 },
                "checks" : { "pass" : 3, "mismatch" : 2, "untested" : 2, "failed" : 0 },
                "visits" : { "pass" : 1, "mismatch" : 2, "untested" : 1, "failed" : 0, "nothingToCheck" : 0 }
              },
              "pages" : [ {
                "page" : "https://trustee.io/ua/cards/",
                "profile" : "iphone-safari",
                "scenarios" : [ "key-replaced" ],
                "path" : [ "enter https://trustee.io/?r=K1aaaaaaaaa", "enter-new-key https://trustee.io/?r=K2bbbbbbbbb",
                           "click \\"Карты\\"" ],
                "problems" : [ {
                  "route" : "referral-link",
                  "href" : "https://trusteeplus.app.link/K1aaaaaaaaa",
                  "selector" : "a.hero__btn",
                  "screenshot" : "screenshots/iphone-safari--ua-cards--skachat-1.png",
                  "checked" : { "path-segment" : 1 },
                  "expected" : "K2bbbbbbbbb",
                  "actual" : "K1aaaaaaaaa"
                } ],
                "untested" : [ { "reason" : "qrUnreadable", "selector" : "div.download svg", "detail" : "blurred",
                                 "screenshot" : null } ]
              }, {
                "page" : "https://trustee.io/old/",
                "profile" : "desktop-chrome",
                "scenarios" : [ "entry", "browse", "key-replaced" ],
                "path" : [ "enter https://trustee.io/old/?r=K1aaaaaaaaa" ],
                "problems" : [ ],
                "untested" : [ { "reason" : "httpStatus", "status" : 404,
                               "detail" : "the site answered 404 for https://trustee.io/old/?r=K1aaaaaaaaa",
                               "screenshot" : "screenshots/desktop-chrome--old--http-status-1.png" } ]
              }, {
                "page" : "https://trustee.io/",
                "profile" : "desktop-chrome",
                "scenarios" : [ "entry", "browse", "key-replaced" ],
                "path" : [ "enter https://trustee.io/?r=K1aaaaaaaaa" ],
                "problems" : [ {
                  "route" : "direct-store-link",
                  "href" : "https://apps.apple.com/app/id1634455978",
                  "selector" : "footer a.store",
                  "screenshot" : null,
                  "checked" : "fail",
                  "expected" : "K1aaaaaaaaa",
                  "actual" : null
                } ],
                "untested" : [ ]
              } ]
            }""";

    @TempDir
    private Path runs;

    @Test
    void theReportIsWrittenAsAgreed() throws IOException {
        Path file = writer().write(run(), coverage(walk(5), visits()), visits());

        assertThat(file).isEqualTo(runs.resolve(RUN_ID).resolve("report.json"));
        String written = Files.readString(file);
        assertThat(compact(written)).isEqualTo(compact(GOLDEN));
        assertThat(written.lines().skip(1).findFirst()).contains("  \"run\" : {");
    }

    @Test
    void aScreenshotIsWrittenAsAPathRelativeToTheReportWithForwardSlashes() {
        Report.UntestedItem item = new Report.UntestedItem(UntestedReason.CLICK_NO_NAVIGATION, null, "a.app",
                "nothing happened within 3000 ms of the click",
                Path.of("screenshots", "desktop", "home--click-no-navigation-1.png"));

        assertThat(JSON.readTree(JSON.writeValueAsString(item)).get("screenshot").asString())
                .isEqualTo("screenshots/desktop/home--click-no-navigation-1.png");
    }

    @Test
    void eachFailedExpectationIsAProblemOfItsOwn() {
        FoundLink partner = link("https://partner.test/a/b", "a.partner", "Partner");
        Check both = new Check.Mismatch(partner, "partner", K1, List.of(
                new Check.Mismatch.Unmet(new Expectation.PathSegment(2), "b"),
                new Check.Mismatch.Unmet(new Expectation.Query("ref"), null)));
        PageVisit visit = visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1)), HOME, List.of(both), Map.of());

        JsonNode report = written(walk(1), List.of(visit));

        assertThat(report.get("coverage").get("checks")).isEqualTo(checks(0, 1, 0, 0));
        assertThat(report.get("pages").get(0).get("problems"))
                .isEqualTo(JSON.readTree("""
                        [ { "route" : "partner", "href" : "https://partner.test/a/b", "selector" : "a.partner",
                            "screenshot" : null, "checked" : { "path-segment" : 2 }, "expected" : "K1aaaaaaaaa",
                            "actual" : "b" },
                          { "route" : "partner", "href" : "https://partner.test/a/b", "selector" : "a.partner",
                            "screenshot" : null, "checked" : { "query" : "ref" }, "expected" : "K1aaaaaaaaa",
                            "actual" : null } ]"""));
    }

    @Test
    void aKeyThePageAFollowedLinkLedToDoesNotStoreIsAProblemOfTheLink() {
        Path screenshots = runs.resolve(RUN_ID).resolve("screenshots");
        Check noCookie = new Check.Mismatch(CARDS_LINK, "site", K1,
                List.of(new Check.Mismatch.Unmet(new Expectation.Cookie("ref"), null)));
        Check otherKey = new Check.Mismatch(CARDS_LINK, "site", K1,
                List.of(new Check.Mismatch.Unmet(new Expectation.LocalStorage("referral"), "K2bbbbbbbbb")));
        PageVisit visit = visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1), new Step.Click(CARDS_LINK)),
                CARDS, List.of(noCookie, otherKey),
                Map.of(noCookie, screenshots.resolve("desktop-chrome--ua-cards--karty-1.png")));

        JsonNode report = written(walk(2), List.of(visit));

        assertThat(report.get("coverage").get("checks")).isEqualTo(checks(0, 2, 0, 0));
        assertThat(report.get("pages").get(0).get("problems"))
                .isEqualTo(JSON.readTree("""
                        [ { "route" : "site", "href" : "https://trustee.io/ua/cards/", "selector" : "a.nav__cards",
                            "screenshot" : "screenshots/desktop-chrome--ua-cards--karty-1.png",
                            "checked" : { "cookie" : "ref" }, "expected" : "K1aaaaaaaaa", "actual" : null },
                          { "route" : "site", "href" : "https://trustee.io/ua/cards/", "selector" : "a.nav__cards",
                            "screenshot" : null, "checked" : { "local-storage" : "referral" },
                            "expected" : "K1aaaaaaaaa", "actual" : "K2bbbbbbbbb" } ]"""));
    }

    @Test
    void anItemOnTheWholePageHasNoSelector() {
        assertThat(JSON.readTree(JSON.writeValueAsString(
                new Report.UntestedItem(UntestedReason.PAGE_LOAD_TIMEOUT, null, null, "30 s", null))))
                .isEqualTo(JSON.readTree("""
                        { "reason" : "pageLoadTimeout", "detail" : "30 s", "screenshot" : null }"""));
    }

    @Test
    void aScreenshotTheReportCannotPointToIsLeftOut() throws IOException {
        Untested gone = new Untested.HttpResponse(404, URI.create("https://trustee.io/old/"));
        Check check = new Check.NotTested(gone);
        try (FileSystem elsewhere = FileSystems.newFileSystem(runs.resolve("elsewhere.zip"),
                Map.of("create", "true"))) {
            PageVisit visit = visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1)), HOME, List.of(check),
                    Map.of(check, elsewhere.getPath("/old.png")));

            JsonNode report = written(walk(0), List.of(visit));

            assertThat(report.get("pages").get(0).get("untested")).isEqualTo(JSON.readTree("""
                    [ { "reason" : "httpStatus", "status" : 404,
                        "detail" : "the site answered 404 for https://trustee.io/old/", "screenshot" : null } ]"""));
        }
    }

    @Test
    void aLinkThatCouldNotBeClickedSaysWhy() {
        String covered = "not clickable: covered by <div class=\"style_general-modal__il7hM\">…</div>";
        Check notClicked = new Check.NotTested(new Untested.Issue(UntestedReason.CLICK_FAILED, "a.nav__cards",
                covered));
        PageVisit visit = visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1)), HOME, List.of(notClicked),
                Map.of());

        JsonNode report = written(walk(0), List.of(visit));

        assertThat(report.get("pages").get(0).get("untested"))
                .isEqualTo(JSON.readTree("""
                        [ { "reason" : "clickFailed", "selector" : "a.nav__cards",
                            "detail" : "not clickable: covered by <div class=\\"style_general-modal__il7hM\\">…</div>",
                            "screenshot" : null } ]"""));
    }

    @Test
    void aVisitWithNothingToReportCountsButIsNotListed() {
        PageVisit passing = visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1)), HOME,
                List.of(new Check.Pass(APP_K1)), Map.of());

        JsonNode report = written(walk(1), List.of(passing));

        assertThat(report.get("coverage").get("checks")).isEqualTo(checks(1, 0, 0, 0));
        assertThat(report.get("pages").isEmpty()).isTrue();
    }

    @Test
    void aPageTheMonitorBrokeDownOnIsListedAsUntested() {
        PageVisit broken = visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1), new Step.Reload()), HOME,
                List.of(new Check.Failed()), Map.of());

        JsonNode report = written(walk(0), List.of(broken));

        assertThat(report.get("coverage").get("checks")).isEqualTo(checks(0, 0, 0, 1));
        assertThat(report.get("pages").get(0).get("path")).isEqualTo(JSON.readTree("""
                [ "enter https://trustee.io/?r=K1aaaaaaaaa", "reload" ]"""));
        assertThat(report.get("pages").get(0).get("untested")).isEqualTo(JSON.readTree("""
                [ { "reason" : "internal", "detail" : "the monitor broke down; what happened is in the log",
                    "screenshot" : null } ]"""));
    }

    private List<PageVisit> visits() {
        Path screenshots = runs.resolve(RUN_ID).resolve("screenshots");
        Check download = new Check.Mismatch(DOWNLOAD_K1, "referral-link", K2,
                List.of(new Check.Mismatch.Unmet(new Expectation.PathSegment(1), "K1aaaaaaaaa")));
        Check gone = new Check.NotTested(new Untested.HttpResponse(404,
                URI.create("https://trustee.io/old/?r=K1aaaaaaaaa")));
        return List.of(
                visit(IPHONE, KEY_REPLACED,
                        List.of(new Step.Enter(HOME, K1), new Step.Enter(HOME, K2), new Step.Click(CARDS_LINK)), CARDS,
                        List.of(download,
                                new Check.Pass(FOOTER_K2),
                                new Check.NotTested(new Untested.Issue(UntestedReason.QR_UNREADABLE, "div.download svg",
                                        "blurred"))),
                        Map.of(download, screenshots.resolve("iphone-safari--ua-cards--skachat-1.png"))),
                visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1)), HOME,
                        List.of(new Check.Pass(APP_K1), new Check.Pass(APP_K1)), Map.of()),
                visit(DESKTOP, ENTRY, List.of(new Step.Enter(URI.create("https://trustee.io/old/"), K1)),
                        URI.create("https://trustee.io/old/"), List.of(gone),
                        Map.of(gone, screenshots.resolve("desktop-chrome--old--http-status-1.png"))),
                visit(DESKTOP, ENTRY, List.of(new Step.Enter(HOME, K1)), HOME,
                        List.of(new Check.Mismatch(STORE, "direct-store-link", K1,
                                List.of(new Check.Mismatch.Unmet(new Expectation.Fail(), null)))),
                        Map.of()));
    }

    private JsonNode written(Walker.Counters walk, List<PageVisit> visits) {
        return JSON.readTree(writer().write(run(), coverage(walk, visits), visits).toFile());
    }

    private static JsonNode checks(long pass, long mismatch, long untested, long failed) {
        return JSON.readTree(JSON.writeValueAsString(new Report.Checks(pass, mismatch, untested, failed)));
    }

    private static Report.Coverage coverage(Walker.Counters walk, List<PageVisit> visits) {
        return Report.Coverage.of(walk, SCENARIOS, visits);
    }

    private ReportWriter writer() {
        return new ReportWriter(runs, "r", JSON);
    }

    private static Report.Run run() {
        return new Report.Run(RUN_ID, URI.create("https://trustee.io"), Instant.parse("2026-09-23T04:39:00Z"),
                Instant.parse("2026-09-23T05:10:00Z"), List.of("desktop-chrome", "iphone-safari"));
    }

    private static Walker.Counters walk(int checked) {
        return new Walker.Counters(new Walker.Pages(2, 3, 4, 1, 3), new Walker.Links(5, checked, 9));
    }

    private static PageVisit visit(DeviceProfile profile, List<String> scenarios, List<Step> path, URI landed,
                                   List<Check> checks, Map<Check, Path> screenshots) {
        return new PageVisit(profile, scenarios, path, new Landing(landed, Arrival.ENTERED), checks, screenshots);
    }

    private static FoundLink link(String url, String selector, String text) {
        return new FoundLink(URI.create(url), selector, text, How.HREF, true, false);
    }

    private static DeviceProfile profile(String name, BrowserEngine engine) {
        return new DeviceProfile(name, engine,
                new BrowserIdentity(null, Locale.US),
                new DeviceEmulation(new Viewport(1000, 800), 1, false, false));
    }

    private static String compact(String json) {
        return JSON.writeValueAsString(JSON.readTree(json));
    }
}
