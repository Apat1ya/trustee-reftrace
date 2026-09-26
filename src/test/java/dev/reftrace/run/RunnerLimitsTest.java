package dev.reftrace.run;

import dev.reftrace.config.ExpectEntry;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import dev.reftrace.crawl.WalkWorkers;
import dev.reftrace.judge.Judge;
import dev.reftrace.report.ReportWriter;
import dev.reftrace.report.RunRetention;
import dev.reftrace.sitemap.StartPages;
import dev.reftrace.testsupport.Fixtures;
import dev.reftrace.testsupport.PropertiesFixture;
import dev.reftrace.testsupport.fakebrowser.ScriptedBrowserWorkerFactory;
import dev.reftrace.testsupport.fakebrowser.ScriptedSite;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerLimitsTest {

    private static final String EXIT = "https://trusteeplus.app.link/" + ScriptedSite.KEY;
    private static final List<Route> LINKS = List.of(
            new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of()),
            new Route("trusteeplus.app.link", List.of(Fixtures.match("trusteeplus.app.link")), false,
                    List.of(new ExpectEntry(1, null, null, null, null, false))));
    private static final Scenario BROWSE = new Scenario("browse", List.of(StepWord.ENTER, StepWord.CLICK_ANY));

    @TempDir
    private Path runs;

    private final ScriptedSite site = new ScriptedSite()
            .page("/", EXIT).internalLink("/", "/cards/")
            .page("/cards/").internalLink("/cards/", "/fees/")
            .page("/fees/");

    @Test
    void aRunCutShortByItsTimeFails() {
        site.hangOnOpen("/cards/");

        Runner.Result result = run(new ReftraceProperties.Limits.Run(Duration.ofMillis(500), 0, 5, 5));

        assertThat(result.report()).as("the report is written all the same").exists();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(exitCode(result)).isEqualTo(CliTriggerTest.FAILED);
    }

    private static int exitCode(Runner.Result result) {
        CliTrigger trigger = new CliTrigger(CliTriggerTest.runner(_ -> result));
        trigger.run(new DefaultApplicationArguments());
        return trigger.getExitCode();
    }

    private Runner.Result run(ReftraceProperties.Limits.Run limits) {
        ReftraceProperties p = PropertiesFixture.defaults().build();
        ReftraceProperties properties = new ReftraceProperties(p.browser(), p.profiles(), 1,
                new ReftraceProperties.Limits(p.limits().action(), p.limits().visit(), limits), p.report(),
                p.keyParam(), p.qrCheck(), p.start(), LINKS, List.of(BROWSE), p.coverage(), p.schedule());
        Routes routes = new Routes(properties.routes());
        WalkWorkers workers = new WalkWorkers(new ScriptedBrowserWorkerFactory(site), new Judge(routes), routes,
                properties.keyParam(), true, WalkWorkers.Settings.of(properties), Clock.systemUTC(),
                ObservationRegistry.NOOP);
        return new Runner(properties, List.of(Fixtures.profile("desktop")),
                new StartPages(RestClient.create(), routes, properties.keyParam()), workers,
                new ReportWriter(runs, properties.keyParam(), JsonMapper.builder().build()),
                new RunRetention(runs, runs, properties.report().retention(), JsonMapper.builder().build()),
                Clock.systemUTC(),
                ObservationRegistry.NOOP, new RunMetrics(new SimpleMeterRegistry(), List.of("desktop")))
                .run(Trigger.CLI);
    }
}
