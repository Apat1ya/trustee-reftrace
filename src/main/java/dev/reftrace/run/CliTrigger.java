package dev.reftrace.run;

import dev.reftrace.ReftraceApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(ReftraceApplication.RUN_ONCE)
public class CliTrigger implements ApplicationRunner, ExitCodeGenerator {

    private enum ExitCode {
        CLEAN,
        MISMATCH,
        FAILED;

        @SuppressWarnings("EnumOrdinal")
        private int code() {
            return ordinal();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CliTrigger.class);

    private final Runner runs;
    private volatile ExitCode exitCode = ExitCode.FAILED;

    CliTrigger(Runner runs) {
        this.runs = runs;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            exitCode = exitCodeFor(runs.run(Trigger.CLI));
        } catch (RuntimeException e) {
            log.warn("the run did not produce a report", e);
            exitCode = ExitCode.FAILED;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode.code();
    }

    private static ExitCode exitCodeFor(Runner.Result result) {
        if (result.mismatches() > 0) {
            return ExitCode.MISMATCH;
        }
        return result.failed() > 0 || result.judged() == 0 ? ExitCode.FAILED : ExitCode.CLEAN;
    }
}
