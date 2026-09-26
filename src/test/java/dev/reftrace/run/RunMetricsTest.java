package dev.reftrace.run;

import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Expectation;
import dev.reftrace.crawl.Arrival;
import dev.reftrace.crawl.Landing;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.crawl.Step;
import dev.reftrace.crawl.Walker;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.ReferralKey;
import dev.reftrace.report.Report;
import dev.reftrace.report.ReportWriter;
import dev.reftrace.testsupport.Fixtures;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.entry;

class RunMetricsTest {

    private static final String DESKTOP = "desktop-chrome";
    private static final String IPHONE = "iphone-safari";
    private static final List<String> PROFILES = List.of(DESKTOP, IPHONE);
    private static final List<String> OUTCOMES = List.of("pass", "mismatch", "untested", "failed");
    private static final List<String> VISIT_OUTCOMES =
            List.of("pass", "mismatch", "untested", "failed", "nothing_to_check");
    private static final List<String> REASONS = List.of("qr_unreadable", "qr_hidden", "click_no_navigation",
            "click_failed", "link_not_found", "dom_changed", "page_load_timeout", "navigation_error", "http_status",
            "browser_crash", "internal");

    private static final Instant STARTED = Instant.parse("2026-09-27T03:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-27T03:05:30.250Z");
    private static final ReferralKey KEY = ReferralKey.of("K1aaaaaaaaa");
    private static final URI HOME = URI.create("https://trustee.io/");
    private static final FoundLink APP = new FoundLink(URI.create("https://trusteeplus.app.link/K1aaaaaaaaa"),
            "a.hero__btn", "Download", How.HREF, true, false);
    private static final FoundLink STORE = new FoundLink(URI.create("https://apps.apple.com/app/id1634455978"),
            "footer a.store", "", How.HREF, true, false);
    private static final Check PASS = new Check.Pass(APP);
    private static final Check MISMATCH = new Check.Mismatch(STORE, "direct-store-link", KEY,
            List.of(new Check.Mismatch.Unmet(new Expectation.Fail(), null)));
    private static final Check QR_HIDDEN = new Check.NotTested(
            new Untested.Issue(UntestedReason.QR_HIDDEN, "div.qr svg", "not shown to this profile"));
    private static final Check GONE = new Check.NotTested(new Untested.HttpResponse(404, HOME.resolve("old/")));
    private static final Check NOT_CLICKED = new Check.NotTested(
            new Untested.Issue(UntestedReason.CLICK_FAILED, "a.nav__cards", "not clickable"));
    private static final Check CRASHED = new Check.Failed();

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final RunMetrics metrics = new RunMetrics(registry, PROFILES);

    @TempDir
    private Path runs;

    @Test
    void nothingOfTheLastRunIsPublishedBeforeARunOfTheProcessHasEnded() {
        assertThat(names()).containsExactly("reftrace.visits");

        metrics.started(STARTED);

        assertThat(names()).containsExactly("reftrace.run.last.start.timestamp", "reftrace.visits");
        assertThat(value("reftrace.run.last.start.timestamp")).isEqualTo(1_790_478_000.0);
        assertThat(registry.get("reftrace.run.last.start.timestamp").meter().getId().getBaseUnit())
                .isEqualTo("seconds");
    }

    @Test
    void theVisitsAreCountedForEveryOutcomeAndProfileFromZero() {
        assertThat(series("reftrace.visits")).isEqualTo(everyCombination(0.0));
        assertThat(registry.get("reftrace.visits").counters()).hasSize(10);
    }

    @Test
    void aVisitIsCountedByTheWorstOfItsChecks() {
        List<PageVisit> visits = visits();

        visits.forEach(metrics::visited);

        Map<String, Double> expected = everyCombination(0.0);
        expected.put("outcome=pass,profile=desktop-chrome", 1.0);
        expected.put("outcome=mismatch,profile=desktop-chrome", 1.0);
        expected.put("outcome=untested,profile=iphone-safari", 1.0);
        expected.put("outcome=failed,profile=iphone-safari", 2.0);
        expected.put("outcome=nothing_to_check,profile=iphone-safari", 1.0);
        assertThat(series("reftrace.visits")).isEqualTo(expected);
        Report.Visits counted = coverage(visits).visits();
        assertThat(registry.get("reftrace.visits").tag("outcome", "failed").counters().stream()
                .mapToDouble(counter -> counter.count()).sum()).isEqualTo((double) counted.failed());
        assertThat(registry.get("reftrace.visits").tag("outcome", "nothing_to_check").counters().stream()
                .mapToDouble(counter -> counter.count()).sum()).isEqualTo((double) counted.nothingToCheck());
    }

    @Test
    void aFinishedRunPublishesEveryResultUnderItsName() {
        List<PageVisit> visits = visits();
        Report.Coverage coverage = coverage(visits);

        metrics.started(STARTED);
        metrics.finished(STARTED, FINISHED, coverage, visits);

        assertThat(names()).containsExactly(
                "reftrace.run.last.checks",
                "reftrace.run.last.duration",
                "reftrace.run.last.finish.timestamp",
                "reftrace.run.last.links.checked",
                "reftrace.run.last.pages",
                "reftrace.run.last.start.timestamp",
                "reftrace.run.last.status",
                "reftrace.run.last.untested",
                "reftrace.run.last.visits",
                "reftrace.visits");
        assertThat(series("reftrace.run.last.status")).containsExactly(entry("status=issues_found", 1.0),
                entry("status=no_report", 0.0), entry("status=ok", 0.0));
        assertThat(value("reftrace.run.last.finish.timestamp")).isEqualTo(1_790_478_330.25);
        assertThat(value("reftrace.run.last.duration")).isEqualTo(330.25);
        assertThat(value("reftrace.run.last.pages")).isEqualTo(coverage.pages().unique()).isEqualTo(3);
        assertThat(value("reftrace.run.last.visits")).isEqualTo(coverage.pages().visits()).isEqualTo(6);
        assertThat(value("reftrace.run.last.links.checked")).isEqualTo(coverage.links().checked()).isEqualTo(3);
        assertThat(series("reftrace.run.last.checks")).containsExactly(
                entry("outcome=failed,profile=desktop-chrome", 0.0),
                entry("outcome=failed,profile=iphone-safari", 2.0),
                entry("outcome=mismatch,profile=desktop-chrome", 1.0),
                entry("outcome=mismatch,profile=iphone-safari", 0.0),
                entry("outcome=pass,profile=desktop-chrome", 2.0),
                entry("outcome=pass,profile=iphone-safari", 0.0),
                entry("outcome=untested,profile=desktop-chrome", 0.0),
                entry("outcome=untested,profile=iphone-safari", 2.0));
        Map<String, Double> untested = REASONS.stream()
                .collect(Collectors.toMap(reason -> "reason=" + reason, _ -> 0.0, (a, _) -> a, TreeMap::new));
        untested.put("reason=qr_hidden", 1.0);
        untested.put("reason=http_status", 1.0);
        untested.put("reason=click_failed", 1.0);
        untested.put("reason=internal", 1.0);
        assertThat(series("reftrace.run.last.untested")).isEqualTo(untested);
        for (String seconds : List.of("reftrace.run.last.finish.timestamp", "reftrace.run.last.duration")) {
            assertThat(registry.get(seconds).meter().getId().getBaseUnit()).as(seconds).isEqualTo("seconds");
        }
        for (String count : List.of("reftrace.run.last.pages", "reftrace.run.last.visits",
                "reftrace.run.last.links.checked", "reftrace.run.last.checks", "reftrace.run.last.untested",
                "reftrace.run.last.status", "reftrace.visits")) {
            assertThat(registry.get(count).meters()).as(count)
                    .allSatisfy(meter -> assertThat(meter.getId().getBaseUnit()).isNull());
        }
    }

    @Test
    void theResultsAreTheNumbersOfTheReport() throws IOException {
        List<PageVisit> visits = visits();
        Report.Coverage coverage = coverage(visits);
        ObjectMapper json = JsonMapper.builder().build();
        Path file = new ReportWriter(runs, "r", json).write(new Report.Run("20260927T030000Z-1a2b", HOME, STARTED,
                FINISHED, PROFILES), coverage, visits);

        metrics.finished(STARTED, FINISHED, coverage, visits);

        JsonNode report = json.readTree(Files.readString(file));
        Map<String, Long> listed = report.get("pages").valueStream()
                .flatMap(page -> page.get("untested").valueStream())
                .collect(Collectors.groupingBy(item -> item.get("reason").asString(), Collectors.counting()));
        for (UntestedReason reason : UntestedReason.values()) {
            String inReport = json.writeValueAsString(reason).replace("\"", "");
            assertThat(registry.get("reftrace.run.last.untested").tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .gauge().value()).as(reason.name()).isEqualTo((double) listed.getOrDefault(inReport, 0L));
        }
        JsonNode checks = report.get("coverage").get("checks");
        for (String outcome : OUTCOMES) {
            assertThat(registry.get("reftrace.run.last.checks").tag("outcome", outcome).gauges().stream()
                    .mapToDouble(gauge -> gauge.value()).sum()).as(outcome).isEqualTo(checks.get(outcome).asDouble());
        }
        JsonNode pages = report.get("coverage").get("pages");
        assertThat(value("reftrace.run.last.pages")).isEqualTo(pages.get("unique").asDouble());
        assertThat(value("reftrace.run.last.visits")).isEqualTo(pages.get("visits").asDouble());
        assertThat(value("reftrace.run.last.links.checked"))
                .isEqualTo(report.get("coverage").get("links").get("checked").asDouble());
    }

    @Test
    void aRunWithoutAMismatchIsOk() {
        List<PageVisit> visits = List.of(visit(DESKTOP, PASS, QR_HIDDEN));

        metrics.finished(STARTED, FINISHED, coverage(visits), visits);

        assertThat(series("reftrace.run.last.status")).containsExactly(entry("status=issues_found", 0.0),
                entry("status=no_report", 0.0), entry("status=ok", 1.0));
    }

    @Test
    void aRunWithoutAReportKeepsTheResultsOfTheLastRun() {
        List<PageVisit> visits = visits();
        metrics.finished(STARTED, FINISHED, coverage(visits), visits);
        Map<String, Map<String, Double>> before = results();
        Instant crashed = FINISHED.plusSeconds(3600);

        metrics.started(FINISHED.plusSeconds(60));
        metrics.failed(crashed);

        assertThat(series("reftrace.run.last.status")).containsExactly(entry("status=issues_found", 0.0),
                entry("status=no_report", 1.0), entry("status=ok", 0.0));
        assertThat(value("reftrace.run.last.finish.timestamp")).isEqualTo(crashed.toEpochMilli() / 1000.0);
        assertThat(value("reftrace.run.last.start.timestamp"))
                .isEqualTo(FINISHED.plusSeconds(60).toEpochMilli() / 1000.0);
        assertThat(results()).isEqualTo(before);
    }

    @Test
    void aFirstRunWithoutAReportPublishesNoResults() {
        metrics.started(STARTED);
        metrics.failed(FINISHED);

        assertThat(names()).containsExactly("reftrace.run.last.finish.timestamp",
                "reftrace.run.last.start.timestamp", "reftrace.run.last.status", "reftrace.visits");
        assertThat(series("reftrace.run.last.status")).containsExactly(entry("status=issues_found", 0.0),
                entry("status=no_report", 1.0), entry("status=ok", 0.0));
    }

    @Test
    void aMeterTheRegistryRefusesDoesNotReachTheRun() {
        registry.counter("reftrace.run.last.pages");
        List<PageVisit> visits = visits();

        assertThatNoException().isThrownBy(() -> {
            metrics.started(STARTED);
            visits.forEach(metrics::visited);
            metrics.finished(STARTED, FINISHED, coverage(visits), visits);
            metrics.failed(FINISHED);
        });
    }

    private static List<PageVisit> visits() {
        return List.of(
                visit(DESKTOP, PASS),
                visit(DESKTOP, PASS, MISMATCH),
                visit(IPHONE, QR_HIDDEN),
                visit(IPHONE, GONE, NOT_CLICKED),
                visit(IPHONE, CRASHED),
                visit(IPHONE));
    }

    private static PageVisit visit(String profile, Check... checks) {
        DeviceProfile device = Fixtures.profile(profile);
        return new PageVisit(device, List.of("entry"), List.of(new Step.Enter(HOME, KEY)),
                new Landing(HOME, Arrival.ENTERED), List.of(checks), Map.of());
    }

    private static Report.Coverage coverage(List<PageVisit> visits) {
        long judged = visits.stream().flatMap(visit -> visit.checks().stream())
                .filter(check -> check instanceof Check.Pass || check instanceof Check.Mismatch).count();
        return Report.Coverage.of(new Walker.Counters(new Walker.Pages(1, 3, visits.size(), 0, 2),
                new Walker.Links(4, (int) judged, 7)), List.of("entry"), visits);
    }

    private Set<String> names() {
        return registry.getMeters().stream().map(meter -> meter.getId().getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private double value(String name) {
        return registry.get(name).gauge().value();
    }

    private Map<String, Double> series(String name) {
        return registry.get(name).meters().stream().collect(Collectors.toMap(
                meter -> meter.getId().getTags().stream().map(tag -> tag.getKey() + "=" + tag.getValue())
                        .collect(Collectors.joining(",")),
                RunMetricsTest::valueOf, (a, _) -> a, TreeMap::new));
    }

    private Map<String, Map<String, Double>> results() {
        return List.of("reftrace.run.last.duration", "reftrace.run.last.pages", "reftrace.run.last.visits",
                        "reftrace.run.last.links.checked", "reftrace.run.last.checks", "reftrace.run.last.untested")
                .stream().collect(Collectors.toMap(name -> name, this::series));
    }

    private static double valueOf(Meter meter) {
        return meter.measure().iterator().next().getValue();
    }

    private static Map<String, Double> everyCombination(double value) {
        Map<String, Double> all = new TreeMap<>();
        for (String outcome : VISIT_OUTCOMES) {
            for (String profile : PROFILES) {
                all.put("outcome=" + outcome + ",profile=" + profile, value);
            }
        }
        return all;
    }
}
