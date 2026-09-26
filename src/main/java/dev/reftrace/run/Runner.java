package dev.reftrace.run;

import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Routes;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.Start;
import dev.reftrace.crawl.Coverage;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.crawl.RunKeys;
import dev.reftrace.crawl.StepTree;
import dev.reftrace.crawl.WalkWorkers;
import dev.reftrace.crawl.Walker;
import dev.reftrace.judge.Check;
import dev.reftrace.report.Report;
import dev.reftrace.report.ReportWriter;
import dev.reftrace.report.RunRetention;
import dev.reftrace.sitemap.StartPages;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

class Runner {

    private static final Logger log = LoggerFactory.getLogger(Runner.class);
    private static final DateTimeFormatter RUN_ID_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ReftraceProperties properties;
    private final List<DeviceProfile> profiles;
    private final StartPages startPages;
    private final WalkWorkers workers;
    private final ReportWriter reports;
    private final RunRetention retention;
    private final Clock clock;
    private final ObservationRegistry observations;
    private final RunMetrics metrics;
    private final SecureRandom random = new SecureRandom();

    Runner(ReftraceProperties properties, List<DeviceProfile> profiles, StartPages startPages,
           WalkWorkers workers, ReportWriter reports, RunRetention retention, Clock clock,
           ObservationRegistry observations, RunMetrics metrics) {
        this.properties = properties;
        this.profiles = List.copyOf(profiles);
        this.startPages = startPages;
        this.workers = workers;
        this.reports = reports;
        this.retention = retention;
        this.clock = clock;
        this.observations = observations;
        this.metrics = metrics;
    }

    Result run(Trigger trigger) {
        Instant startedAt = clock.instant();
        metrics.started(startedAt);
        try {
            return walk(info(startedAt, trigger));
        } catch (RuntimeException | Error e) {
            metrics.failed(clock.instant());
            throw e;
        }
    }

    private RunInfo info(Instant startedAt, Trigger trigger) {
        return new RunInfo(
            new RunId(
                RUN_ID_STAMP.format(startedAt)
                    + "-"
                    + HexFormat.of().toHexDigits((short) random.nextInt())
            ),
            startedAt,
            trigger,
            site(),
            profiles.stream().map(DeviceProfile::name).toList()
        );
    }

    private Result walk(RunInfo info) {
        retention.clean(info.id().value(), info.startedAt());
        StartPages.Resolved starts = phase("run.start-pages", info)
                .observe(() -> startPages.resolve(properties.starts()));
        starts.unread().forEach(unread -> log.warn("run {}: sitemap {} was not read: {}", info.id(),
                unread.sitemap(), unread.reason()));
        Walker walker = new Walker(Coverage.of(properties.coverage()), RunKeys.draw(random),
                new Routes(properties.routes()), properties.keyParam(), properties.limits().run().depth());
        StepTree tree = StepTree.of(properties.scenarios());
        List<Walker.Walk> walks = profiles.stream()
                .map(profile -> walker.walk(profile, tree, starts.pages()))
                .toList();
        List<String> scenarios = properties.scenarios().stream().map(Scenario::name).toList();
        log.info("run {} ({}): {} start pages, scenarios {}, one walk per profile {}, depth {}, "
                        + "{} parallel browsers, coverage {}",
                info.id(), info.trigger(), starts.pages().size(), scenarios, info.profiles(),
                properties.limits().run().depth(), properties.parallelBrowsers(),
                properties.coverage().name().toLowerCase(Locale.ROOT));

        WalkWorkers.Walked walked = workers.walk(walks, info.id().value(), reports.directory(info.id().value()),
                metrics::visited);

        Walker.Counters counters = merged(walked.counters(), walked.visits());
        Report.Coverage coverage = Report.Coverage.of(counters, scenarios, walked.visits());
        Instant finishedAt = clock.instant();
        Path file = phase("run.report", info).observe(() -> reports.write(new Report.Run(info.id().value(),
                info.baseUrl(), info.startedAt(), finishedAt, info.profiles()), coverage, walked.visits()));
        metrics.finished(info.startedAt(), finishedAt, coverage, walked.visits());
        long failed = walked.visits().stream().flatMap(visit -> visit.checks().stream())
                .filter(Check.Failed.class::isInstance).count() + walked.abandoned();
        log.info("run {} done in {}: pages {}, scenarios {}, links {}, checks {}, visits {}, failed {}; report at {}",
                info.id(), Duration.between(info.startedAt(), finishedAt), coverage.pages(), coverage.scenarios(),
                coverage.links(), coverage.checks(), coverage.visits(), failed, file);
        return new Result(info.id(), file, coverage, failed);
    }

    private Observation phase(String name, RunInfo info) {
        return Observation.createNotStarted(name, observations)
                .lowCardinalityKeyValue("reftrace.trigger", info.trigger().name().toLowerCase(Locale.ROOT))
                .highCardinalityKeyValue("reftrace.run.id", info.id().value());
    }

    private static Walker.Counters merged(List<Walker.Counters> walks, List<PageVisit> visits) {
        int unique = visits.stream().map(visit -> visit.landing().address()).collect(Collectors.toSet()).size();
        return new Walker.Counters(
                new Walker.Pages(walks.stream().mapToInt(walk -> walk.pages().start()).max().orElse(0), unique,
                        walks.stream().mapToInt(walk -> walk.pages().visits()).sum(),
                        walks.stream().mapToInt(walk -> walk.pages().repeats()).sum(),
                        walks.stream().mapToInt(walk -> walk.pages().maxDepth()).max().orElse(0)),
                new Walker.Links(walks.stream().mapToInt(walk -> walk.links().followed()).sum(),
                        walks.stream().mapToInt(walk -> walk.links().checked()).sum(),
                        walks.stream().mapToInt(walk -> walk.links().ignored()).sum()));
    }

    private URI site() {
        URI first = switch (properties.starts().getFirst()) {
            case Start.Page(URI url) -> url;
            case Start.Sitemap(URI url) -> url;
        };
        return UriComponentsBuilder.fromUri(first).replacePath(null).replaceQuery(null).fragment(null).build()
                .toUri();
    }

    record Result(RunId id, Path report, Report.Coverage coverage, long failed) {

        long mismatches() {
            return coverage.checks().mismatch();
        }

        long judged() {
            return coverage.checks().judged();
        }
    }
}
