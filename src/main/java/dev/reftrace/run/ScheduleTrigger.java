package dev.reftrace.run;

import dev.reftrace.config.ReftraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "reftrace.schedule.enabled", havingValue = "true")
@EnableScheduling
public class ScheduleTrigger implements SchedulingConfigurer, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTrigger.class);

    private final Runner runs;
    private final ReftraceProperties.Schedule schedule;
    private final ThreadPoolExecutor pending;

    ScheduleTrigger(Runner runs, ReftraceProperties properties) {
        this.runs = runs;
        this.schedule = properties.schedule();
        this.pending = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), (_, _) ->
                log.info("a scheduled run is already waiting; this slot is merged into the pending run"));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addCronTask(new CronTask(this::runOnSchedule,
                new CronTrigger(schedule.cron().toString(), schedule.zone())));
    }

    public void runOnSchedule() {
        pending.execute(this::runNow);
    }

    private void runNow() {
        try {
            Runner.Result result = runs.run(Trigger.SCHEDULE);
            log.info("the report of scheduled run {} is at {}", result.id(), result.report());
        } catch (RuntimeException e) {
            log.warn("the scheduled run did not produce a report", e);
        }
    }

    @Override
    public void destroy() {
        pending.shutdownNow();
    }
}
