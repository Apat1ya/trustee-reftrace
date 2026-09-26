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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.RequiredSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RunnerMetricsTest {

    private static final String EXIT = "https://trusteeplus.app.link/" + ScriptedSite.KEY;
    private static final List<Route> LINKS = List.of(
            new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of()),
            new Route("trusteeplus.app.link", List.of(Fixtures.match("trusteeplus.app.link")), false,
                    List.of(new ExpectEntry(1, null, null, null, null, false))));
    private static final Scenario BROWSE = new Scenario("browse", List.of(StepWord.ENTER, StepWord.CLICK_ANY));

    @TempDir
    private Path temp;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ScriptedSite site = new ScriptedSite()
            .page("/", EXIT).internalLink("/", "/cards/")
            .page("/cards/");

    @Test
    void aRunWithAReportLeavesItsNumbers() {
        Runner.Result result = runner(temp).run(Trigger.CLI);

        assertThat(registry.get("reftrace.run.last.status").tag("status", "ok").gauge().value()).isEqualTo(1);
        assertThat(registry.get("reftrace.run.last.visits").gauge().value())
                .isEqualTo(result.coverage().pages().visits()).isEqualTo(2);
        assertThat(registry.get("reftrace.run.last.checks").tag("outcome", "pass").gauge().value())
                .isEqualTo((double) result.coverage().checks().pass()).isEqualTo(1);
        assertThat(sum(registry.get("reftrace.visits").tag("outcome", "pass"))).isEqualTo(1);
        assertThat(sum(registry.get("reftrace.visits").tag("outcome", "nothing_to_check"))).isEqualTo(1);
        assertThat(registry.get("reftrace.run.last.start.timestamp").gauge().value())
                .isLessThanOrEqualTo(registry.get("reftrace.run.last.finish.timestamp").gauge().value());
    }

    @Test
    void aRunWithoutAReportEndsWithNoReport() throws IOException {
        Path notADirectory = Files.createFile(temp.resolve("runs"));

        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> runner(notADirectory).run(Trigger.CLI));

        assertThat(registry.get("reftrace.run.last.status").tag("status", "no_report").gauge().value())
                .isEqualTo(1);
        assertThat(registry.find("reftrace.run.last.visits").gauges()).isEmpty();
        assertThat(sum(registry.get("reftrace.visits").tag("outcome", "pass"))).isEqualTo(1);
    }

    private static double sum(RequiredSearch counters) {
        return counters.counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    private Runner runner(Path runs) {
        ReftraceProperties p = PropertiesFixture.defaults().build();
        ReftraceProperties properties = new ReftraceProperties(p.browser(), p.profiles(), 1,
                new ReftraceProperties.Limits(p.limits().action(), p.limits().visit(),
                        new ReftraceProperties.Limits.Run(Duration.ofMinutes(1), 0, 0, 0)),
                p.report(), p.keyParam(), p.qrCheck(), p.start(), LINKS, List.of(BROWSE), p.coverage(),
                p.schedule());
        Routes routes = new Routes(properties.routes());
        WalkWorkers workers = new WalkWorkers(new ScriptedBrowserWorkerFactory(site), new Judge(routes), routes,
                properties.keyParam(), true, WalkWorkers.Settings.of(properties), Clock.systemUTC(),
                ObservationRegistry.NOOP);
        return new Runner(properties, List.of(Fixtures.profile("desktop")),
                new StartPages(RestClient.create(), routes, properties.keyParam()), workers,
                new ReportWriter(runs, properties.keyParam(), JsonMapper.builder().build()),
                new RunRetention(runs, temp, properties.report().retention(), JsonMapper.builder().build()),
                Clock.systemUTC(),
                ObservationRegistry.NOOP, new RunMetrics(registry, List.of("desktop")));
    }
}
