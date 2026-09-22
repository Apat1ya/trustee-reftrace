package dev.reftrace.testsupport;

import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Clicks;
import dev.reftrace.config.CoverageMode;
import dev.reftrace.config.Route;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.StartEntry;
import dev.reftrace.config.StepWord;
import org.springframework.scheduling.support.CronExpression;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

public final class PropertiesFixture {

    private static final URI SITE = URI.create("https://site.test/");

    private CronExpression cron = CronExpression.parse("0 0 3 * * *");
    private ZoneId zone = ZoneOffset.UTC;

    public static PropertiesFixture defaults() {
        return new PropertiesFixture();
    }

    public PropertiesFixture schedule(CronExpression cronValue, ZoneId zoneValue) {
        this.cron = cronValue;
        this.zone = zoneValue;
        return this;
    }

    public ReftraceProperties build() {
        return new ReftraceProperties(
                new ReftraceProperties.Browser(true, List.of("www.googletagmanager.com"), Clicks.ALL, List.of()),
                List.of(new ReftraceProperties.Profile("desktop", BrowserEngine.CHROMIUM, "Mozilla/5.0 (desktop)",
                        new ReftraceProperties.Profile.ViewportSize(1440, 900), 1, false, false, "en-US")),
                2,
                new ReftraceProperties.Limits(
                        new ReftraceProperties.Limits.Action(Duration.ofSeconds(30), Duration.ofSeconds(1),
                                Duration.ofSeconds(8), Duration.ofSeconds(5), Duration.ofSeconds(3)),
                        new ReftraceProperties.Limits.Visit(Duration.ofMinutes(2), 3, Duration.ofMillis(1), 2),
                        new ReftraceProperties.Limits.Run(Duration.ofMinutes(5), 0, 0, 0)),
                new ReftraceProperties.Report("runs", true, true, new ReftraceProperties.Report.Retention(
                        Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(7))),
                "r",
                true,
                List.of(new StartEntry(SITE, null)),
                List.of(new Route("site.test", List.of(Fixtures.match("site.test")), true, List.of())),
                List.of(new Scenario("entry", List.of(StepWord.ENTER))),
                CoverageMode.ONCE_PER_ARRIVAL,
                new ReftraceProperties.Schedule(false, cron, zone));
    }
}
