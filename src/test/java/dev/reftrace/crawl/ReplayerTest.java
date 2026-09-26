package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.Expectation;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.Judge;
import dev.reftrace.judge.ReferralKey;
import dev.reftrace.testsupport.Fixtures;
import dev.reftrace.testsupport.fakebrowser.ScriptedBrowserWorkerFactory;
import dev.reftrace.testsupport.fakebrowser.ScriptedSite;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayerTest {

    private static final String ORIGIN = "https://site.test";
    private static final ReferralKey KEY = ReferralKey.of("K1aaaaaaaaa");
    private static final StepTree.Node ENTERED = StepTree.of(List.of(new Scenario("browse",
            List.of(StepWord.ENTER, StepWord.CLICK)))).entered();
    private static final StepTree.Node CLICKED = Objects.requireNonNull(ENTERED.children().get(StepWord.CLICK));
    private static final Routes ROUTES = new Routes(List.of(
            new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of()),
            new Route("*.app.link", List.of(Fixtures.match("*.app.link")), false,
                    List.of(new ExpectEntry(1, null, null, null, null, false)))));
    private static final Replayer REPLAYER = new Replayer(ROUTES, new Judge(ROUTES), "r", true, true,
            ObservationRegistry.NOOP);

    private static final Expectation.Cookie COOKIE = new Expectation.Cookie("ref");
    private static final Expectation.LocalStorage LOCAL = new Expectation.LocalStorage("ref");
    private static final Routes STORING_ROUTES = new Routes(List.of(
            new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of(
                    new ExpectEntry(null, null, null, "ref", null, false),
                    new ExpectEntry(null, null, null, null, "ref", false)))));
    private static final Replayer STORING = new Replayer(STORING_ROUTES, new Judge(STORING_ROUTES), "r", true, true,
            ObservationRegistry.NOOP);
    private static final FoundLink CARDS = new FoundLink(URI.create(ORIGIN + "/cards/?r=" + KEY.value()),
            "a:nth-of-type(1)", "go to /cards/", How.HREF, true, false);

    @ParameterizedTest
    @CsvSource({"404, false", "410, false", "429, false", "500, true", "503, true"})
    void anErrorPageIsNotReadAndOnlyAServerErrorIsAskedAgain(int status, boolean again) {
        ScriptedSite site = new ScriptedSite().page("/", "https://trusteeplus.app.link/{key}").status("/", status);

        Replayer.Replayed replayed = REPLAYER.replay(page(site), enter("/"), false, true);

        assertThat(replayed).isInstanceOfSatisfying(Replayer.Replayed.Unreached.class, unreached -> {
            assertThat(unreached.untested()).isInstanceOfSatisfying(Untested.HttpResponse.class,
                    response -> assertThat(response.statusCode()).isEqualTo(status));
            assertThat(unreached.untested().detail())
                    .isEqualTo("the site answered " + status + " for " + ORIGIN + "/?r=" + KEY.value());
        });
        assertThat(replayed.worthAnotherAttempt()).isEqualTo(again);
    }

    @Test
    void aClickOntoAnErrorPageEndsTheReplay() {
        ScriptedSite site = new ScriptedSite().page("/").internalLink("/", "/gone/").status("/gone/", 404);
        FoundLink gone = new FoundLink(URI.create(ORIGIN + "/gone/"), "a:nth-of-type(1)", "/gone/", How.HREF, true,
                false);
        WalkTask task = new WalkTask(Fixtures.profile("desktop"), CLICKED,
                List.of(new Step.Enter(URI.create(ORIGIN + "/"), KEY), new Step.Click(gone)),
                new Landing(URI.create(ORIGIN + "/"), Arrival.ENTERED));

        Replayer.Replayed replayed = REPLAYER.replay(page(site), task, false, true);

        assertThat(replayed).isInstanceOfSatisfying(Replayer.Replayed.Unreached.class, unreached ->
                assertThat(unreached.untested()).isInstanceOfSatisfying(Untested.HttpResponse.class, response -> {
                    assertThat(response.reason()).isEqualTo(UntestedReason.HTTP_STATUS);
                    assertThat(response.statusCode()).isEqualTo(404);
                }));
        assertThat(replayed.worthAnotherAttempt()).isFalse();
    }

    @Test
    void aPageThatAnsweredIsReadAndJudged() {
        ScriptedSite site = new ScriptedSite().page("/", "https://trusteeplus.app.link/{key}");

        Replayer.Replayed replayed = REPLAYER.replay(page(site), enter("/"), false, true);

        assertThat(replayed).isInstanceOfSatisfying(Replayer.Replayed.Scanned.class, scanned ->
                assertThat(scanned.scan().links()).isNotEmpty());
        assertThat(replayed.worthAnotherAttempt()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("photographs")
    void whatIsPhotographedIsWhatDidNotPass(String what, Replayer replayer, ScriptedSite site, WalkTask task,
                                            boolean lastAttempt, List<Optional<String>> taken) {
        Replayer.Replayed replayed = replayer.replay(page(site), task, false, lastAttempt);

        Map<Check, byte[]> kept = switch (replayed) {
            case Replayer.Replayed.Scanned scanned -> scanned.screenshots();
            case Replayer.Replayed.Unreached unreached -> unreached.screenshots();
            case Replayer.Replayed.Crashed _ -> Map.of();
        };
        assertThat(site.screenshots()).containsExactlyElementsOf(taken);
        assertThat(kept).hasSize(taken.size());
        assertThat(kept.keySet()).noneMatch(Check.Pass.class::isInstance);
    }

    static Stream<Arguments> photographs() {
        return Stream.of(
                Arguments.of("a link that loses the key", REPLAYER, new ScriptedSite().page("/",
                        "https://trusteeplus.app.link/{key}", "https://trusteeplus.app.link/lost"), enter("/"), true,
                        List.of(Optional.of("a:nth-of-type(2)"))),
                Arguments.of("an error page", REPLAYER, new ScriptedSite().page("/").status("/", 404), enter("/"),
                        true, List.of(Optional.empty())),
                Arguments.of("an error page not asked again, on any attempt", REPLAYER,
                        new ScriptedSite().page("/").status("/", 404), enter("/"), false, List.of(Optional.empty())),
                Arguments.of("a server error asked again", REPLAYER, new ScriptedSite().page("/").status("/", 503),
                        enter("/"), false, List.of()),
                Arguments.of("a server error on the last attempt", REPLAYER,
                        new ScriptedSite().page("/").status("/", 503), enter("/"), true, List.of(Optional.empty())),
                Arguments.of("a link and a QR code out of sight", REPLAYER, new ScriptedSite()
                        .page("/", "https://trusteeplus.app.link/lost#hidden").hiddenQrCode("/"), enter("/"), true,
                        List.of(Optional.empty(), Optional.empty())),
                Arguments.of("a crashed browser", REPLAYER, new ScriptedSite().page("/")
                        .failNextOpens("/", 1, UntestedReason.BROWSER_CRASH, "it went away"), enter("/"), true,
                        List.of()),
                Arguments.of("a broken monitor", REPLAYER, new ScriptedSite().page("/")
                        .failNextOpens("/", 1, UntestedReason.INTERNAL, "it went away"), enter("/"), true, List.of()),
                Arguments.of("a key the landed page does not store", STORING, storingSite()
                        .stores("/cards/", COOKIE, "K0stale0000").stores("/cards/", LOCAL, "{key}"), clickCards(),
                        true, List.of(Optional.empty())));
    }

    @Test
    void aPageAClickLedToIsJudgedOnWhatItStores() {
        ScriptedSite site = storingSite().stores("/cards/", COOKIE, "{key}").stores("/cards/", LOCAL, "K0stale0000");

        Replayer.Replayed replayed = STORING.replay(page(site), clickCards(), false, true);

        assertThat(replayed).isInstanceOfSatisfying(Replayer.Replayed.Scanned.class, scanned -> {
            assertThat(scanned.scan().stored()).extracting(StoredValue::followed).containsOnly(CARDS);
            assertThat(scanned.checks()).containsExactly(new Check.Pass(CARDS),
                    new Check.Mismatch(CARDS, "site.test", KEY, List.of(new Check.Mismatch.Unmet(LOCAL, "K0stale0000"))));
        });
    }

    @Test
    void aPageEnteredOrReloadedIsNotReadForWhatItStores() {
        ScriptedSite site = storingSite().stores("/cards/", COOKIE, "{key}");
        Step.Enter cards = new Step.Enter(URI.create(ORIGIN + "/cards/"), KEY);
        WalkTask reload = new WalkTask(Fixtures.profile("desktop"), CLICKED, List.of(cards, new Step.Reload()),
                new Landing(URI.create(ORIGIN + "/cards/"), Arrival.ENTERED));

        for (WalkTask task : List.of(enter("/cards/"), reload)) {
            assertThat(STORING.replay(page(site), task, false, true)).isInstanceOfSatisfying(Replayer.Replayed.Scanned.class,
                    scanned -> assertThat(scanned.scan().stored()).as("%s", task.path()).isEmpty());
        }
    }

    @Test
    void aClickThatReachedNoPageReadsNothingStored() {
        ScriptedSite failing = storingSite().stores("/cards/", COOKIE, "{key}")
                .failNextOpens("/cards/", 1, UntestedReason.NAVIGATION_ERROR, "net::ERR_ABORTED");
        ScriptedSite erring = storingSite().stores("/cards/", COOKIE, "{key}").status("/cards/", 404);

        for (ScriptedSite site : List.of(failing, erring)) {
            assertThat(STORING.replay(page(site), clickCards(), false, true))
                    .isInstanceOf(Replayer.Replayed.Unreached.class);
        }
    }

    private static ScriptedSite storingSite() {
        return new ScriptedSite().page("/").internalLink("/", "/cards/?r={key}").page("/cards/");
    }

    private static WalkTask clickCards() {
        return new WalkTask(Fixtures.profile("desktop"), CLICKED,
                List.of(new Step.Enter(URI.create(ORIGIN + "/"), KEY), new Step.Click(CARDS)),
                new Landing(URI.create(ORIGIN + "/"), Arrival.ENTERED));
    }

    private static PageDriver page(ScriptedSite site) {
        return new ScriptedBrowserWorkerFactory(site).create().openSession(Fixtures.profile("desktop")).page();
    }

    private static WalkTask enter(String path) {
        return new WalkTask(Fixtures.profile("desktop"), ENTERED,
                List.of(new Step.Enter(URI.create(ORIGIN + path), KEY)), null);
    }
}
