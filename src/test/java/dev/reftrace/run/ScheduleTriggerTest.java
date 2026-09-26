package dev.reftrace.run;

import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.testsupport.PropertiesFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTriggerTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ScheduleTrigger.class)
            .withBean(Runner.class, () -> CliTriggerTest.runner(_ -> CliTriggerTest.result(3, 0, 0, 0)))
            .withBean(ReftraceProperties.class, () -> PropertiesFixture.defaults()
                    .schedule(CronExpression.parse("0 30 2 * * *"), ZoneId.of("Europe/Kyiv")).build());

    @Test
    void theTimerStaysOffUnlessItIsAskedFor() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(ScheduleTrigger.class));
    }

    @Test
    void theTimetableIsTheBoundCronReadInTheBoundZone() {
        contexts.withPropertyValues("reftrace.schedule.enabled=true")
                .run(context -> assertThat(scheduledTasks(context)).singleElement()
                        .extracting(CronTask::getTrigger)
                        .isEqualTo(new CronTrigger("0 30 2 * * *", ZoneId.of("Europe/Kyiv"))));
    }

    @Test
    void slotsThatComeRoundWhileARunWaitsAreMergedIntoIt() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger runs = new AtomicInteger();
        ScheduleTrigger trigger = new ScheduleTrigger(CliTriggerTest.runner(runTrigger -> {
            assertThat(runTrigger).isEqualTo(Trigger.SCHEDULE);
            runs.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
            return CliTriggerTest.result(3, 0, 0, 0);
        }), PropertiesFixture.defaults().build());

        trigger.runOnSchedule();
        trigger.runOnSchedule();
        trigger.runOnSchedule();
        trigger.runOnSchedule();
        release.countDown();

        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runs).hasValue(2);
        trigger.destroy();
    }

    @Test
    void aRunThatFailsLeavesTheTimerAloneForTheNextSlot() throws InterruptedException {
        CountDownLatch attempted = new CountDownLatch(2);
        ScheduleTrigger trigger = new ScheduleTrigger(CliTriggerTest.runner(_ -> {
            attempted.countDown();
            throw new IllegalStateException("the output directory is read-only");
        }), PropertiesFixture.defaults().build());

        trigger.runOnSchedule();
        trigger.runOnSchedule();

        assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
        trigger.destroy();
    }

    private static List<CronTask> scheduledTasks(ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
    }
}
