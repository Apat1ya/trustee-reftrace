package dev.reftrace.browse.launch;

import dev.reftrace.browse.page.BrowserTimeouts;
import dev.reftrace.config.CoreConfiguration;
import dev.reftrace.config.ReftraceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserSettingsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CoreConfiguration.class, PlaywrightWiring.class);

    @Test
    void mapsTheBrowserSettings() {
        runner.run(context -> {
            BrowserSettings settings = BrowserSettings.of(context.getBean(ReftraceProperties.class),
                    context.getBean(RevealProperties.class));

            assertThat(settings.timeouts()).isEqualTo(new BrowserTimeouts(Duration.ofSeconds(15),
                    Duration.ofMillis(600), Duration.ofSeconds(8), Duration.ofSeconds(5), Duration.ofSeconds(2)));
            assertThat(settings.urls().isExit("https://trustee.io/download/app.apk")).isTrue();
        });
    }
}
