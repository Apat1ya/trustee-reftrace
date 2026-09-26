package dev.reftrace.report;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.crawl.PageVisit;
import dev.reftrace.crawl.Step;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.ReferralKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ReportWriter {

    static final String FILE_NAME = "report.json";

    static final String MONITOR_FAILED = "the monitor broke down; what happened is in the log";

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    private final Path runsDir;
    private final String keyParam;
    private final ObjectWriter json;

    public ReportWriter(Path runsDir, String keyParam, ObjectMapper mapper) {
        this.runsDir = runsDir.toAbsolutePath();
        this.keyParam = keyParam;
        this.json = mapper.writer().with(SerializationFeature.INDENT_OUTPUT);
    }

    public Path write(Report.Run run, Report.Coverage coverage, List<PageVisit> visits) {
        Path file = directory(run.id()).resolve(FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(report(run, coverage, visits)));
        } catch (IOException e) {
            throw new UncheckedIOException("the report could not be written to " + file, e);
        }
        return file;
    }

    private Report report(Report.Run run, Report.Coverage coverage, List<PageVisit> visits) {
        Path directory = directory(run.id());
        return new Report(
                run,
                coverage,
                visits.stream().map(visit -> page(directory, visit))
                        .filter(page -> !page.problems().isEmpty() || !page.untested().isEmpty())
                        .toList());
    }

    private Report.Page page(Path directory, PageVisit visit) {
        List<Report.Problem> problems = new ArrayList<>();
        List<Report.UntestedItem> untested = new ArrayList<>();
        for (Check check : visit.checks()) {
            switch (check) {
                case Check.Pass _ -> { }
                case Check.Mismatch(FoundLink link, String route, ReferralKey key, var failed) -> failed.forEach(unmet ->
                        problems.add(new Report.Problem(route, link.url(), link.selector(),
                                screenshot(directory, visit, check), Report.Checked.of(unmet.expectation()),
                                key.value(), unmet.actual())));
                case Check.NotTested(var item) -> untested.add(new Report.UntestedItem(item.reason(),
                        status(item), item.selector(), item.detail(), screenshot(directory, visit, check)));
                case Check.Failed _ -> untested.add(new Report.UntestedItem(UntestedReason.INTERNAL, null, null,
                        MONITOR_FAILED, null));
            }
        }
        return new Report.Page(visit.landing().address(), visit.profile().name(), visit.scenarios(),
                Step.spelled(visit.path(), keyParam), problems, untested);
    }

    private static @Nullable Integer status(Untested item) {
        return item instanceof Untested.HttpResponse(int statusCode, _) ? statusCode : null;
    }

    private static @Nullable Path screenshot(Path directory, PageVisit visit, Check check) {
        @Nullable Path screenshot = visit.screenshots().get(check);
        if (screenshot == null) {
            return null;
        }
        try {
            return directory.relativize(screenshot.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            log.warn("the screenshot {} is left out of the report: no path leads to it from {}: {}", screenshot,
                    directory, e.toString());
            return null;
        }
    }

    public Path directory(String runId) {
        return runsDir.resolve(runId);
    }
}
