package dev.reftrace.run;

import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.sitemap.LocalSite;
import dev.reftrace.testsupport.PropertiesFixture;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RunWiringTest {

    @Test
    void aPageLoadOfZeroReadsASitemapWithoutABound() {
        ReftraceProperties p = PropertiesFixture.defaults().build();
        ReftraceProperties.Limits.Action action = p.limits().action();
        ReftraceProperties unbounded = new ReftraceProperties(p.browser(), p.profiles(), p.parallelBrowsers(),
                new ReftraceProperties.Limits(new ReftraceProperties.Limits.Action(Duration.ZERO,
                        action.settleQuiet(), action.settleMax(), action.operation(), action.clickNavigation()),
                        p.limits().visit(), p.limits().run()),
                p.report(), p.keyParam(), p.qrCheck(), p.start(), p.routes(), p.scenarios(), p.coverage(),
                p.schedule());

        try (LocalSite site = new LocalSite().serve("/sitemap.xml", "application/xml", "<urlset/>")) {
            String body = new RunWiring().siteRestClient(unbounded, ObservationRegistry.NOOP).get()
                    .uri(site.url("/sitemap.xml"))
                    .retrieve().body(String.class);

            assertThat(body).isEqualTo("<urlset/>");
        }
    }
}
