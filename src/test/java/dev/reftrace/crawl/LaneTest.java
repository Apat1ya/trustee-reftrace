package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.Judge;
import dev.reftrace.judge.ReferralKey;
import dev.reftrace.testsupport.Fixtures;
import dev.reftrace.testsupport.fakebrowser.ScriptedBrowserWorkerFactory;
import dev.reftrace.testsupport.fakebrowser.ScriptedSite;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class LaneTest {

    private static final String ORIGIN = "https://site.test";
    private static final ReferralKey KEY = ReferralKey.of("K1aaaaaaaaa");
    private static final ReferralKey SECOND_KEY = ReferralKey.of("K2bbbbbbbbb");
    private static final ExpectEntry QUERY = new ExpectEntry(null, "r", null, null, null, false);
    private static final FoundLink CARDS = new FoundLink(URI.create(ORIGIN + "/cards/"), "a:nth-of-type(1)",
            "/cards/", How.HREF, true, false);
    private static final StepTree.Node ENTERED = StepTree.of(List.of(new Scenario("browse",
            List.of(StepWord.ENTER, StepWord.CLICK)))).entered();
    private static final StepTree.Node CLICKED = Objects.requireNonNull(ENTERED.children().get(StepWord.CLICK));
    private static final Traces TRACES = new Traces(Path.of("target", "lane-test"));

    @Test
    void aClickWhoseLandedPageIsReadForAStoredKeyStartsFromAFreshContext() throws Exception {
        ScriptedSite site = site();

        visitHomeThen(site, new ExpectEntry(null, null, null, "ref", null, false), new Step.Click(CARDS));

        assertThat(site.opens("/")).as("home entered again by the fresh replay").isEqualTo(2);
    }

    @Test
    void anyOtherClickContinuesInTheTabItHas() throws Exception {
        ScriptedSite site = site();

        visitHomeThen(site, QUERY, new Step.Click(CARDS));

        assertThat(site.opens("/")).isEqualTo(1);
    }

    @Test
    void aReloadStartsFromAFreshContext() throws Exception {
        ScriptedSite site = site();

        visitHomeThen(site, QUERY, new Step.Reload());

        assertThat(site.opens("/")).isEqualTo(3);
    }

    @Test
    void theSecondKeyStartsFromAFreshContext() throws Exception {
        ScriptedSite site = site();

        visitHomeThen(site, QUERY, new Step.Enter(URI.create(ORIGIN + "/"), SECOND_KEY));

        assertThat(site.opens("/")).isEqualTo(3);
    }

    @Test
    void aBrowserThatCannotStartIsReplacedForTheNextTask() throws Exception {
        ScriptedSite site = site();
        ScriptedBrowserWorkerFactory browsers = new ScriptedBrowserWorkerFactory(site).failNextSessions(1);
        Routes routes = new Routes(List.of(new Route("site.test", List.of(Fixtures.match("site.test")), true,
                List.of())));
        Lane lane = new Lane(0, browsers,
                new Replayer(routes, new Judge(routes), "r", true, true, ObservationRegistry.NOOP),
                new ReftraceProperties.Limits.Visit(Duration.ofSeconds(10), 3, Duration.ofMillis(1), 1.0), TRACES,
                ObservationRegistry.NOOP);
        WalkTask home = new WalkTask(Fixtures.profile("desktop"), ENTERED,
                List.of(new Step.Enter(URI.create(ORIGIN + "/"), KEY)), null);
        try {
            assertThat(lane.thread.submit(() -> lane.visit(home)).get())
                    .isInstanceOf(Replayer.Replayed.Crashed.class);
            assertThat(lane.thread.submit(() -> lane.visit(home)).get())
                    .isInstanceOf(Replayer.Replayed.Scanned.class);
        } finally {
            lane.thread.submit(lane::close).get();
            lane.thread.shutdown();
        }
        assertThat(browsers.created()).hasSize(2);
        assertThat(browsers.created().getFirst().isClosed()).isTrue();
        assertThat(site.opens("/")).isEqualTo(1);
    }

    private static ScriptedSite site() {
        return new ScriptedSite().page("/").internalLink("/", "/cards/").page("/cards/");
    }

    private static void visitHomeThen(ScriptedSite site, ExpectEntry cardsExpect, Step next)
            throws InterruptedException, ExecutionException {
        Routes routes = new Routes(List.of(
                new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of()),
                new Route("site.test/cards/**", List.of(Fixtures.match("site.test/cards/**")), true,
                        List.of(cardsExpect))));
        Lane lane = new Lane(0, new ScriptedBrowserWorkerFactory(site),
                new Replayer(routes, new Judge(routes), "r", true, true, ObservationRegistry.NOOP),
                new ReftraceProperties.Limits.Visit(Duration.ofSeconds(10), 1, Duration.ofMillis(1), 1.0), TRACES,
                ObservationRegistry.NOOP);
        Step.Enter home = new Step.Enter(URI.create(ORIGIN + "/"), KEY);
        try {
            lane.thread.submit(() -> lane.visit(new WalkTask(Fixtures.profile("desktop"), ENTERED, List.of(home),
                    null))).get();
            lane.thread.submit(() -> lane.visit(new WalkTask(Fixtures.profile("desktop"), CLICKED,
                    List.of(home, next), new Landing(URI.create(ORIGIN + "/"), Arrival.ENTERED)))).get();
        } finally {
            lane.thread.submit(lane::close).get();
            lane.thread.shutdown();
        }
    }
}
