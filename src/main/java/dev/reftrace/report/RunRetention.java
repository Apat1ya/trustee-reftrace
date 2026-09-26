package dev.reftrace.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.crawl.Screenshots;
import dev.reftrace.crawl.Traces;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

public class RunRetention {

    private static final Logger log = LoggerFactory.getLogger(RunRetention.class);

    private final Path runsDir;
    private final Path tempDir;
    private final ReftraceProperties.Report.Retention retention;
    private final ObjectMapper mapper;

    public RunRetention(Path runsDir, Path tempDir, ReftraceProperties.Report.Retention retention,
                        ObjectMapper mapper) {
        this.runsDir = runsDir.toAbsolutePath();
        this.tempDir = tempDir.toAbsolutePath();
        this.retention = retention;
        this.mapper = mapper;
    }

    public void clean(String inProgress, Instant now) {
        int broken = 0;
        int deleted = 0;
        int screenshots = 0;
        int traces = 0;
        for (Path run : runs(inProgress)) {
            try {
                Instant startedAt = startedAt(run);
                if (startedAt == null) {
                    FileSystemUtils.deleteRecursively(run);
                    broken++;
                    continue;
                }
                Duration age = Duration.between(startedAt, now);
                if (outlived(retention.run(), age)) {
                    FileSystemUtils.deleteRecursively(run);
                    deleted++;
                    continue;
                }
                if (outlived(retention.screenshots(), age)
                        && FileSystemUtils.deleteRecursively(run.resolve(Screenshots.DIRECTORY))) {
                    screenshots++;
                }
                if (outlived(retention.traces(), age)
                        && FileSystemUtils.deleteRecursively(run.resolve(Traces.DIRECTORY))) {
                    traces++;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("retention: {} could not be cleaned up: {}", run, e.toString());
            }
        }
        int playwright = cleanTemp(now);
        log.info("retention in {}: deleted {} broken runs, {} runs older than {}, the screenshots of {} older than {} "
                        + "and the traces of {} older than {}; {} Playwright directories in {}", runsDir, broken,
                deleted, retention.run(), screenshots, retention.screenshots(), traces, retention.traces(), playwright,
                tempDir);
    }

    private List<Path> runs(String inProgress) {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(runsDir)) {
            return entries.filter(Files::isDirectory)
                    .filter(entry -> !entry.getFileName().toString().equals(inProgress))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("retention: the runs in {} could not be listed, none was deleted: {}", runsDir, e.toString());
            return List.of();
        }
    }

    private int cleanTemp(Instant now) {
        List<Path> dirs;
        try (Stream<Path> entries = Files.list(tempDir)) {
            dirs = entries.filter(Files::isDirectory)
                    .filter(entry -> isPlaywright(entry.getFileName().toString()))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("retention: {} could not be listed, no Playwright directory was deleted: {}", tempDir,
                    e.toString());
            return 0;
        }
        int deleted = 0;
        for (Path dir : dirs) {
            try {
                Duration age = Duration.between(Files.getLastModifiedTime(dir).toInstant(), now);
                if (outlived(retention.traces(), age) && FileSystemUtils.deleteRecursively(dir)) {
                    deleted++;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("retention: {} could not be deleted: {}", dir, e.toString());
            }
        }
        return deleted;
    }

    private static boolean isPlaywright(String name) {
        return name.startsWith("playwright-artifacts-") || name.startsWith("playwright-tracing-")
                || (name.startsWith("playwright_") && name.contains("dev_profile-"));
    }

    private static boolean outlived(Duration horizon, Duration age) {
        return !horizon.isZero() && age.compareTo(horizon) > 0;
    }

    private @Nullable Instant startedAt(Path run) {
        try {
            StoredReport report = mapper.readValue(run.resolve(ReportWriter.FILE_NAME).toFile(), StoredReport.class);
            StoredReport.Run started = report.run();
            return started == null ? null : started.startedAt();
        } catch (RuntimeException e) {
            log.debug("retention: the report of {} could not be read: {}", run, e.toString());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredReport(@Nullable Run run) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Run(@Nullable Instant startedAt) {
        }
    }
}
