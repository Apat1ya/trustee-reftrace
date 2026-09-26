package dev.reftrace.run;

import dev.reftrace.crawl.Walker;
import dev.reftrace.report.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CliTriggerTest {

    static final int CLEAN = 0;
    static final int FAILED = 2;

    @ParameterizedTest
    @CsvSource(textBlock = """
            # pass, mismatch, untested, failed, exit code: 0 clean, 1 mismatch, 2 failed
                 3,        0,        0,      0, 0
                 3,        0,        2,      0, 0
                 3,        2,        0,      0, 1
                 3,        2,        0,      1, 1
                 0,        1,        4,      2, 1
                 3,        0,        0,      1, 2
                 0,        0,        5,      0, 2
                 0,        0,        0,      0, 2
            """)
    void theShellLearnsHowTheRunWent(long pass, long mismatches, long untested, long failed, int expectedExitCode) {
        CliTrigger runner = new CliTrigger(runner(trigger -> {
            assertThat(trigger).isEqualTo(Trigger.CLI);
            return result(pass, mismatches, untested, failed);
        }));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(expectedExitCode);
    }

    @Test
    void aRunWithoutAReportLeavesNoDoubtThatSomethingFailed() {
        CliTrigger runner = new CliTrigger(runner(_ -> {
            throw new IllegalStateException("the output directory is read-only");
        }));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(FAILED);
    }

    @Test
    void theExitCodeSaysFailedUntilTheRunHasActuallyHappened() {
        assertThat(new CliTrigger(runner(_ -> result(3, 0, 0, 0))).getExitCode()).isEqualTo(FAILED);
    }

    @Test
    void askingForASingleRunAsAPropertyStillPutsSomethingThereToReportTheOutcome() {
        contextWith("run-once=true").run(context ->
                assertThat(context).hasSingleBean(ExitCodeGenerator.class).hasSingleBean(CliTrigger.class));
    }

    @Test
    void anOrdinaryStartHasNothingToReportAnOutcomeWith() {
        contextWith().run(context -> assertThat(context).doesNotHaveBean(ExitCodeGenerator.class));
    }

    private static ApplicationContextRunner contextWith(String... properties) {
        return new ApplicationContextRunner()
                .withPropertyValues(properties)
                .withBean(Runner.class, () -> runner(_ -> result(3, 0, 0, 0)))
                .withUserConfiguration(CliTrigger.class);
    }

    static Runner runner(Function<Trigger, Runner.Result> run) {
        Runner runner = mock(Runner.class);
        when(runner.run(any())).thenAnswer(call -> run.apply(call.getArgument(0)));
        return runner;
    }

    static Runner.Result result(long pass, long mismatches, long untested, long failed) {
        Report.Checks checks = new Report.Checks(pass, mismatches, untested, 0);
        return new Runner.Result(new RunId("20260923T030000Z-1a2b"), Path.of("runs", "report.json"),
                new Report.Coverage(new Walker.Pages(1, 2, 2, 0, 2), Map.of("entry", 2L),
                        new Walker.Links(4, (int) checks.judged(), 1), checks,
                        new Report.Visits(1, mismatches > 0 ? 1 : 0, 0, 0, 0)),
                failed);
    }
}
