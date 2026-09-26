package dev.reftrace.report;

import dev.reftrace.config.ReftraceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RunRetentionTest {

    private static final Instant NOW = Instant.parse("2026-09-27T03:00:00Z");

    @TempDir
    Path runs;

    @TempDir
    Path temp;

    @Test
    void deletesByAgeAndHorizon() throws IOException {
        Path young = run("young", Duration.ofHours(12));
        Path middle = run("middle", Duration.ofDays(2));
        Path old = run("old", Duration.ofDays(4));
        Path inProgress = run("in-progress", Duration.ofDays(30));
        Path unreadable = run("unreadable", Duration.ZERO);
        Files.writeString(unreadable.resolve(ReportWriter.FILE_NAME), "{ broken");
        Path unfinished = Files.createDirectories(runs.resolve("unfinished"));
        Files.createDirectories(unfinished.resolve("screenshots"));

        clean(Duration.ofDays(3), Duration.ofDays(3), Duration.ofDays(1));

        assertThat(young.resolve("screenshots")).exists();
        assertThat(young.resolve("traces")).exists();
        assertThat(middle.resolve("screenshots")).as("younger than its horizon").exists();
        assertThat(middle.resolve("traces")).as("older than its horizon").doesNotExist();
        assertThat(middle.resolve(ReportWriter.FILE_NAME)).exists();
        assertThat(old).as("older than the run's horizon").doesNotExist();
        assertThat(unreadable).as("broken: the report cannot be read").doesNotExist();
        assertThat(unfinished).as("broken: no report").doesNotExist();
        assertThat(inProgress.resolve("screenshots")).as("the run in progress").exists();
        assertThat(inProgress.resolve("traces")).exists();
    }

    @Test
    void keepsForEverWithZero() throws IOException {
        Path ancient = run("ancient", Duration.ofDays(400));

        clean(Duration.ZERO, Duration.ofDays(1), Duration.ZERO);

        assertThat(ancient.resolve(ReportWriter.FILE_NAME)).exists();
        assertThat(ancient.resolve("screenshots")).doesNotExist();
        assertThat(ancient.resolve("traces")).exists();
    }

    @Test
    void deletesOldPlaywrightDirectoriesInTemp() throws IOException {
        Path old = tempDir("playwright-artifacts-a1", Duration.ofDays(2));
        Path fresh = tempDir("playwright_chromiumdev_profile-a2", Duration.ofHours(12));
        Path foreign = tempDir("other-a3", Duration.ofDays(2));

        clean(Duration.ZERO, Duration.ZERO, Duration.ofDays(1));

        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(foreign).exists();
    }

    private void clean(Duration run, Duration screenshots, Duration traces) {
        new RunRetention(runs, temp, new ReftraceProperties.Report.Retention(run, screenshots, traces),
                JsonMapper.builder().build()).clean("in-progress", NOW);
    }

    private Path run(String id, Duration age) throws IOException {
        Path run = Files.createDirectories(runs.resolve(id));
        Files.writeString(run.resolve(ReportWriter.FILE_NAME), """
                {"run": {"id": "%s", "startedAt": "%s", "finishedAt": "%s"}, "pages": []}
                """.formatted(id, NOW.minus(age), NOW));
        Files.write(Files.createDirectories(run.resolve("screenshots")).resolve("a.png"), new byte[] {1});
        Files.write(Files.createDirectories(run.resolve("traces")).resolve("001-a.zip"), new byte[] {1});
        return run;
    }

    private Path tempDir(String name, Duration age) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.write(dir.resolve("a.trace"), new byte[] {1});
        Files.setLastModifiedTime(dir, FileTime.from(NOW.minus(age)));
        return dir;
    }
}
