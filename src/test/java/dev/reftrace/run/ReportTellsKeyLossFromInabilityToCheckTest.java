package dev.reftrace.run;

import dev.reftrace.browse.UntestedReason;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTellsKeyLossFromInabilityToCheckTest {

    private static final String DEEP_LINK = "https://trusteeplus.app.link/";
    private static final String FOREIGN = DEEP_LINK + "K0foreign00";
    private static final List<Route> ROUTES = List.of(
            new Route("site", List.of(Fixtures.match("site.test")), true, List.of()),
            new Route("referral-link", List.of(Fixtures.match("trusteeplus.app.link")), false,
                    List.of(new ExpectEntry(1, null, null, null, null, false))));

    @TempDir
    private Path runs;

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void theReportSaysWhatBecameOfTheKey(String what, UnaryOperator<ScriptedSite> page, String checks,
                                         List<String> problems, List<String> untested) throws IOException {
        Runner.Result result = runner(page.apply(new ScriptedSite())).run(Trigger.CLI);

        JsonNode report = JsonMapper.builder().build().readTree(Files.readString(result.report()));
        JsonNode counted = report.get("coverage").get("checks");
        assertThat(Stream.of("pass", "mismatch", "untested", "failed").map(name -> counted.get(name).asString()))
                .containsExactly(checks.split(" "));
        List<JsonNode> pages = nodes(report.get("pages")).toList();
        assertThat(pages.stream().flatMap(listed -> nodes(listed.get("problems")))
                .map(problem -> problem.get("href").asString())).containsExactlyInAnyOrderElementsOf(problems);
        assertThat(pages.stream().flatMap(listed -> nodes(listed.get("untested")))
                .map(item -> item.get("reason").asString())).containsExactlyElementsOf(untested);
    }

    static Stream<Arguments> sites() {
        return Stream.of(
                Arguments.of("the link carries our key",
                        (UnaryOperator<ScriptedSite>) site -> site.page("/", DEEP_LINK + ScriptedSite.KEY),
                        "1 0 0 0", List.of(), List.of()),
                Arguments.of("one link lost the key and one carries another",
                        (UnaryOperator<ScriptedSite>) site -> site.page("/", DEEP_LINK + ScriptedSite.KEY, DEEP_LINK,
                                FOREIGN),
                        "1 2 0 0", List.of(DEEP_LINK, FOREIGN), List.of()),
                Arguments.of("the site answers 500 on every attempt",
                        (UnaryOperator<ScriptedSite>) site -> site.page("/", DEEP_LINK).status("/", 500),
                        "0 0 1 0", List.of(), List.of("httpStatus")),
                Arguments.of("the page never loads on any attempt",
                        (UnaryOperator<ScriptedSite>) site -> site.page("/", DEEP_LINK)
                                .failNextOpens("/", 3, UntestedReason.PAGE_LOAD_TIMEOUT, "nothing within 30 s"),
                        "0 0 0 1", List.of(), List.of("pageLoadTimeout")));
    }

    private static Stream<JsonNode> nodes(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    private Runner runner(ScriptedSite site) {
        ReftraceProperties p = PropertiesFixture.defaults().build();
        ReftraceProperties properties = new ReftraceProperties(p.browser(), p.profiles(), 1, p.limits(), p.report(),
                p.keyParam(), p.qrCheck(), p.start(), ROUTES,
                List.of(new Scenario("entry", List.of(StepWord.ENTER))), p.coverage(), p.schedule());
        Routes routes = new Routes(properties.routes());
        WalkWorkers workers = new WalkWorkers(new ScriptedBrowserWorkerFactory(site), new Judge(routes), routes,
                properties.keyParam(), true, WalkWorkers.Settings.of(properties), Clock.systemUTC(),
                ObservationRegistry.NOOP);
        return new Runner(properties, List.of(Fixtures.profile("desktop")),
                new StartPages(RestClient.create(), routes, properties.keyParam()), workers,
                new ReportWriter(runs, properties.keyParam(), JsonMapper.builder().build()),
                new RunRetention(runs, runs, properties.report().retention(), JsonMapper.builder().build()),
                Clock.systemUTC(), ObservationRegistry.NOOP, new RunMetrics(new SimpleMeterRegistry(),
                List.of("desktop")));
    }
}
