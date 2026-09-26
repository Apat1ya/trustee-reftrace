package dev.reftrace.crawl;

import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.CoverageMode;
import dev.reftrace.config.ReftraceProperties;
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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class WalkWorkersTest {

    private static final Routes ROUTES = new Routes(List.of(
            new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of())));
    private static final RunKeys KEYS = new RunKeys(ReferralKey.of("K1aaaaaaaaa"), ReferralKey.of("K2bbbbbbbbb"));
    private static final StepTree ENTRY = StepTree.of(List.of(new Scenario("entry", List.of(StepWord.ENTER))));
    private static final StepTree BROWSE = StepTree.of(List.of(new Scenario("browse",
            List.of(StepWord.ENTER, StepWord.CLICK_ANY))));

    private static final String RUN_ID = "20260926T000000Z-0000";

    @TempDir
    private Path run;

    @Test
    void whyAPageCouldNotBeTestedIsLoggedOnceWithTheWayThere(CapturedOutput output) {
        ScriptedSite site = new ScriptedSite().page("/")
                .failNextOpens("/", 2, UntestedReason.PAGE_LOAD_TIMEOUT, "nothing arrived within 30 s");

        WalkWorkers.Walked walked = walkWithAttempts(new ScriptedBrowserWorkerFactory(site), 2);

        assertThat(walked.visits()).singleElement().satisfies(visit -> assertThat(visit.checks())
                .singleElement().isInstanceOf(Check.NotTested.class));
        assertThat(site.opens("/")).isEqualTo(2);
        assertThat(output.getOut().lines().filter(line -> line.contains("untested PAGE_LOAD_TIMEOUT")))
                .singleElement().asString()
                .contains("WARN", "https://site.test/", "enter https://site.test/?r=K1aaaaaaaaa",
                        "nothing arrived within 30 s");
    }

    @Test
    void aBrowserThatCannotStartFailsTheVisitWithoutRetrying(CapturedOutput output) {
        ScriptedSite site = new ScriptedSite().page("/");

        WalkWorkers.Walked walked = walkWithAttempts(new ScriptedBrowserWorkerFactory(site).failNextSessions(1), 3);

        assertThat(walked.visits()).singleElement().satisfies(visit -> assertThat(visit.checks())
                .singleElement().isInstanceOf(Check.Failed.class));
        assertThat(site.opens("/")).isZero();
        assertThat(output.getOut().lines().filter(line -> line.contains("found no browser")))
                .singleElement().asString()
                .contains("WARN", "could not launch chromium for profile desktop", "Executable doesn't exist");
    }

    @Test
    void aVisitWhoseBrowserCrashedOnceIsMadeAgainInANewBrowser() {
        ScriptedSite site = new ScriptedSite().page("/")
                .failNextOpens("/", 1, UntestedReason.BROWSER_CRASH, "Target crashed");
        ScriptedBrowserWorkerFactory browsers = new ScriptedBrowserWorkerFactory(site);

        WalkWorkers.Walked walked = walkWithAttempts(browsers, 3);

        assertThat(walked.visits()).singleElement().satisfies(visit -> assertThat(visit.checks())
                .noneMatch(check -> check instanceof Check.Failed || check instanceof Check.NotTested));
        assertThat(site.opens("/")).isEqualTo(2);
        assertThat(browsers.created()).hasSize(2);
    }

    @Test
    void aVisitWhoseBrowserCrashedOnEveryAttemptFails(CapturedOutput output) {
        ScriptedSite site = new ScriptedSite().page("/")
                .failNextOpens("/", 3, UntestedReason.BROWSER_CRASH, "Target crashed");
        ScriptedBrowserWorkerFactory browsers = new ScriptedBrowserWorkerFactory(site);

        WalkWorkers.Walked walked = walkWithAttempts(browsers, 3);

        assertThat(walked.visits()).singleElement().satisfies(visit -> assertThat(visit.checks())
                .singleElement().isInstanceOf(Check.Failed.class));
        assertThat(site.opens("/")).isEqualTo(3);
        assertThat(browsers.created()).hasSize(3);
        assertThat(output.getOut().lines().filter(line -> line.contains("crashed the browser on the last of 3")))
                .singleElement().asString().contains("WARN", "Target crashed");
    }

    @Test
    void aVisitIsTheRootOfATraceOfItsAttemptsAndTheirStages() {
        ScriptedSite site = new ScriptedSite().page("/")
                .failNextOpens("/", 1, UntestedReason.PAGE_LOAD_TIMEOUT, "nothing arrived within 30 s");
        TestObservationRegistry observations = TestObservationRegistry.create();
        Walker.Walk walk = new Walker(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL), KEYS, ROUTES, "r", 0)
                .walk(Fixtures.profile("desktop"), ENTRY, List.of(URI.create("https://site.test/")));

        new WalkWorkers(new ScriptedBrowserWorkerFactory(site), new Judge(ROUTES), ROUTES, "r", true,
                new WalkWorkers.Settings(1, new ReftraceProperties.Limits.Visit(Duration.ofMinutes(1), 2,
                        Duration.ofMillis(1), 2), run(0, 0), Duration.ofMinutes(1), true, true),
                Clock.systemUTC(), observations).walk(List.of(walk), RUN_ID, run, _ -> { });

        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("visit", 1)
                .hasNumberOfObservationsWithNameEqualTo("attempt", 2)
                .hasNumberOfObservationsWithNameEqualTo("step.enter", 2)
                .hasNumberOfObservationsWithNameEqualTo("scan", 1)
                .forAllObservationsWithNameEqualTo("visit", visit -> visit.doesNotHaveParentObservation()
                        .hasLowCardinalityKeyValue("reftrace.profile", "desktop")
                        .hasLowCardinalityKeyValue("reftrace.scenarios", "entry")
                        .hasLowCardinalityKeyValue("reftrace.outcome", "nothing-to-check")
                        .hasHighCardinalityKeyValue("reftrace.run.id", RUN_ID)
                        .hasHighCardinalityKeyValue("reftrace.page.url", "https://site.test/"))
                .forAllObservationsWithNameEqualTo("attempt", attempt -> attempt
                        .hasParentObservationContextMatching(named("visit")))
                .hasAnObservation(attempt -> attempt.hasNameEqualTo("attempt")
                        .hasLowCardinalityKeyValue("reftrace.attempt", "1")
                        .hasLowCardinalityKeyValue("reftrace.retried", "true")
                        .hasLowCardinalityKeyValue("reftrace.retry.reason", "PAGE_LOAD_TIMEOUT"))
                .hasAnObservation(attempt -> attempt.hasNameEqualTo("attempt")
                        .hasLowCardinalityKeyValue("reftrace.attempt", "2")
                        .hasLowCardinalityKeyValue("reftrace.retried", "false")
                        .doesNotHaveLowCardinalityKeyValueWithKey("reftrace.retry.reason"));
        for (String stage : List.of("context.open", "step.enter", "scan", "trace.discard")) {
            TestObservationRegistryAssert.assertThat(observations).forAllObservationsWithNameEqualTo(stage,
                    inAttempt -> inAttempt
                            .hasParentObservationContextMatching(named("attempt")));
        }
    }

    @Test
    void theTracesShowIdleLanesAndVisitsThatReadAPageAgain() {
        ScriptedSite site = new ScriptedSite().page("/").internalLink("/", "/old/").redirect("/old/", "/");
        TestObservationRegistry observations = TestObservationRegistry.create();
        Walker.Walk walk = new Walker(Coverage.of(CoverageMode.ONCE_PER_PAGE), KEYS, ROUTES, "r", 0)
                .walk(Fixtures.profile("desktop"), BROWSE, List.of(URI.create("https://site.test/")));

        new WalkWorkers(new ScriptedBrowserWorkerFactory(site), new Judge(ROUTES), ROUTES, "r", true,
                new WalkWorkers.Settings(2, new ReftraceProperties.Limits.Visit(Duration.ofMinutes(1), 1,
                        Duration.ofMillis(1), 2), run(0, 0), Duration.ofMinutes(1), true, true),
                Clock.systemUTC(), observations).walk(List.of(walk), RUN_ID, run, _ -> { });

        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("visit", 2)
                .hasAnObservation(visit -> visit.hasNameEqualTo("visit")
                        .hasHighCardinalityKeyValue("reftrace.page.url", "https://site.test/")
                        .hasLowCardinalityKeyValue("reftrace.lane", "0")
                        .hasLowCardinalityKeyValue("reftrace.repeat", "false"))
                .hasAnObservation(visit -> visit.hasNameEqualTo("visit")
                        .hasHighCardinalityKeyValue("reftrace.page.url", "https://site.test/old/")
                        .hasLowCardinalityKeyValue("reftrace.repeat", "true"))
                .hasNumberOfObservationsWithNameEqualTo("lane.idle", 1)
                .forAllObservationsWithNameEqualTo("lane.idle", idle -> idle.doesNotHaveParentObservation()
                        .hasLowCardinalityKeyValue("reftrace.lane", "1")
                        .hasLowCardinalityKeyValue("reftrace.idle.ended", "run-end")
                        .hasHighCardinalityKeyValue("reftrace.run.id", RUN_ID))
                .hasObservationWithNameEqualTo("lane.idle").that().hasBeenStopped();
    }

    @Test
    void theVisitsLimitCountsEveryVisitOfTheRun(CapturedOutput output) {
        ScriptedSite site = twoPages();

        WalkWorkers.Walked walked = walk(site, run(0, 3), Fixtures.profile("desktop"), Fixtures.profile("phone"));

        assertThat(walked.visits())
                .extracting(visit -> visit.profile().name() + " " + visit.landing().address().getPath())
                .containsExactly("desktop /", "phone /", "desktop /cards/");
        assertNothingFailed(walked);
        assertThat(output.getOut().lines().filter(line -> line.contains("limits.run.visits of 3")))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("INFO", "is reached"))
                .anySatisfy(line -> assertThat(line).contains("INFO", "left out 1 visits"));
    }

    @Test
    void thePagesLimitCountsDifferentPagesAndLetsKnownPagesBeVisitedAgain(CapturedOutput output) {
        ScriptedSite site = twoPages().internalLink("/cards/", "/fees/").page("/fees/");

        WalkWorkers.Walked walked = walk(site, run(2, 0), Fixtures.profile("desktop"), Fixtures.profile("phone"));

        assertThat(walked.visits()).extracting(visit -> visit.landing().address().getPath())
                .containsExactlyInAnyOrder("/", "/", "/cards/", "/cards/");
        assertThat(site.opens("/fees/")).isZero();
        assertNothingFailed(walked);
        assertThat(output.getOut().lines().filter(line -> line.contains("limits.run.pages of 2")))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("INFO", "is reached"))
                .anySatisfy(line -> assertThat(line).contains("INFO", "left out 2 visits to other pages"));
    }

    @Test
    void theDepthLimitIsLoggedOnceWhenReachedAndWithWhatItLeftOutAtTheEnd(CapturedOutput output) {
        ScriptedSite site = twoPages().internalLink("/cards/", "/fees/").page("/fees/");

        WalkWorkers.Walked walked = walk(site, new ReftraceProperties.Limits.Run(Duration.ofMinutes(1), 2, 0, 0),
                Fixtures.profile("desktop"), Fixtures.profile("phone"));

        assertThat(walked.visits()).hasSize(4);
        assertThat(site.opens("/fees/")).isZero();
        assertNothingFailed(walked);
        assertThat(output.getOut().lines().filter(line -> line.contains("limits.run.depth of 2")))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("INFO", "is reached"))
                .anySatisfy(line -> assertThat(line).contains("INFO", "left out 2 clicks past it"));
    }

    @Test
    void withoutRunLimitsEveryPageIsVisited(CapturedOutput output) {
        ScriptedSite site = twoPages().internalLink("/cards/", "/fees/").page("/fees/");

        WalkWorkers.Walked walked = walk(site, run(0, 0), Fixtures.profile("desktop"));

        assertThat(walked.visits()).hasSize(3);
        assertThat(output.getOut()).doesNotContain("limits.run");
    }

    @Test
    void theRunTimeStillAbandonsWhatIsLeft() {
        ScriptedSite site = twoPages().hangOnOpen("/");
        ScriptedBrowserWorkerFactory browsers = new ScriptedBrowserWorkerFactory(site);
        Walker.Walk walk = new Walker(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL), KEYS, ROUTES, "r", 0)
                .walk(Fixtures.profile("desktop"), BROWSE, List.of(URI.create("https://site.test/")));

        WalkWorkers.Walked walked = workers(browsers, 1,
                new ReftraceProperties.Limits.Run(Duration.ofMillis(200), 0, 5, 5))
                .walk(List.of(walk), RUN_ID, run, _ -> { });

        assertThat(walked.abandoned()).isEqualTo(1);
        assertThat(walked.visits()).isEmpty();
    }

    @Test
    void aVisitDeadlineAndARunTimeOfZeroCutNothingOff() {
        ScriptedSite site = twoPages().internalLink("/cards/", "/fees/").page("/fees/");
        Walker.Walk walk = new Walker(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL), KEYS, ROUTES, "r", 0)
                .walk(Fixtures.profile("desktop"), BROWSE, List.of(URI.create("https://site.test/")));
        WalkWorkers workers = new WalkWorkers(new ScriptedBrowserWorkerFactory(site), new Judge(ROUTES),
                ROUTES, "r", true,
                new WalkWorkers.Settings(1, new ReftraceProperties.Limits.Visit(Duration.ZERO, 1, Duration.ofMillis(1),
                        2), new ReftraceProperties.Limits.Run(Duration.ZERO, 0, 0, 0), Duration.ofMinutes(1), true, true),
                Clock.systemUTC(), ObservationRegistry.NOOP);

        WalkWorkers.Walked walked = workers.walk(List.of(walk), RUN_ID, run, _ -> { });

        assertThat(walked.visits()).hasSize(3);
        assertNothingFailed(walked);
    }

    private static Predicate<Observation.ContextView> named(String name) {
        return observation -> name.equals(observation.getName());
    }

    private static ScriptedSite twoPages() {
        return new ScriptedSite().page("/").internalLink("/", "/cards/").page("/cards/");
    }

    private static ReftraceProperties.Limits.Run run(int pages, int visits) {
        return new ReftraceProperties.Limits.Run(Duration.ofMinutes(1), 0, pages, visits);
    }

    private static void assertNothingFailed(WalkWorkers.Walked walked) {
        assertThat(walked.abandoned()).isZero();
        assertThat(walked.visits()).flatExtracting(PageVisit::checks)
                .noneMatch(check -> check instanceof Check.Failed || check instanceof Check.NotTested);
    }

    private WalkWorkers.Walked walk(ScriptedSite site, ReftraceProperties.Limits.Run limits,
                                    DeviceProfile... profiles) {
        Walker walker = new Walker(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL), KEYS, ROUTES, "r", limits.depth());
        List<Walker.Walk> walks = Stream.of(profiles)
                .map(profile -> walker.walk(profile, BROWSE, List.of(URI.create("https://site.test/"))))
                .toList();
        return workers(new ScriptedBrowserWorkerFactory(site), 1, limits).walk(walks, RUN_ID, run, _ -> { });
    }

    private WalkWorkers.Walked walkWithAttempts(ScriptedBrowserWorkerFactory browsers, int maxAttempts) {
        Walker.Walk walk = new Walker(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL), KEYS, ROUTES, "r", 0)
                .walk(Fixtures.profile("desktop"), ENTRY, List.of(URI.create("https://site.test/")));
        return workers(browsers, maxAttempts, run(0, 0)).walk(List.of(walk), RUN_ID, run, _ -> { });
    }

    private static WalkWorkers workers(ScriptedBrowserWorkerFactory browsers, int maxAttempts,
                                       ReftraceProperties.Limits.Run limits) {
        return new WalkWorkers(browsers, new Judge(ROUTES), ROUTES, "r", true,
                new WalkWorkers.Settings(1, new ReftraceProperties.Limits.Visit(Duration.ofMinutes(1), maxAttempts,
                        Duration.ofMillis(1), 2), limits, Duration.ofMinutes(1), true, true),
                Clock.systemUTC(), ObservationRegistry.NOOP);
    }
}
