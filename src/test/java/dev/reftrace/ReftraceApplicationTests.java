package dev.reftrace;

import dev.reftrace.browse.BrowserWorkerFactory;
import dev.reftrace.browse.PropertiesMapping;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Routes;
import dev.reftrace.crawl.WalkWorkers;
import dev.reftrace.report.ReportWriter;
import dev.reftrace.sitemap.StartPages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReftraceApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBean(ReftraceProperties.class)).isNotNull();
        assertThat(context.getBean(PropertiesMapping.class)).isNotNull();
        assertThat(context.getBean(Routes.class)).isNotNull();
        assertThat(context.getBean(Clock.class)).isNotNull();
    }

    @Test
    void everyPortHasABean() {
        assertThat(context.getBean(StartPages.class)).isNotNull();
        assertThat(context.getBean(BrowserWorkerFactory.class)).isNotNull();
        assertThat(context.getBean(WalkWorkers.class)).isNotNull();
        assertThat(context.getBean(ReportWriter.class)).isNotNull();
    }
}
