package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserIdentity;
import dev.reftrace.browse.DeviceEmulation;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.Viewport;
import dev.reftrace.browse.page.BrowserTimeouts;
import dev.reftrace.browse.page.RevealPlan;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.Clicks;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.testsupport.PropertiesFixture;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

final class BrowserFixture {

    static final RevealPlan REVEAL = new RevealPlan(true, Duration.ofSeconds(5), 24, List.of(), List.of());

    private BrowserFixture() {
    }

    static BrowserSettings shipped(FixtureSite site, Clicks clicks) {
        Binder shipped = shippedConfiguration();
        RevealProperties reveal = shipped.bindOrCreate("reftrace.reveal", RevealProperties.class);
        ReftraceProperties.Browser browser = shipped.bindOrCreate("reftrace.browser", ReftraceProperties.Browser.class);
        RevealPlan plan = BrowserSettings.of(PropertiesFixture.defaults().build(), reveal).reveal();
        return settingsWith(site, plan, clicks, browser.unwalkedBlocks());
    }

    private static Binder shippedConfiguration() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources));
        } catch (IOException e) {
            throw new UncheckedIOException("the shipped configuration could not be read", e);
        }
    }

    static BrowserSettings settings(FixtureSite site) {
        return settingsWith(site, REVEAL, Clicks.ALL, List.of());
    }

    static BrowserSettings settingsWith(FixtureSite site, RevealPlan reveal, Clicks clicks,
                                        List<String> unwalkedBlocks) {
        return new BrowserSettings(true, site.patterns(),
                new BrowserTimeouts(Duration.ofSeconds(3), Duration.ofMillis(300), Duration.ofSeconds(3),
                        Duration.ofSeconds(3), Duration.ofSeconds(2)),
                reveal, clicks, unwalkedBlocks, true);
    }

    static DeviceProfile desktop(BrowserEngine engine) {
        return new DeviceProfile("desktop", engine,
                new BrowserIdentity(null, Locale.US),
                new DeviceEmulation(new Viewport(1000, 800), 1, false, false));
    }

    static DeviceProfile phone(BrowserEngine engine) {
        return new DeviceProfile("phone", engine,
                new BrowserIdentity("Mozilla/5.0 (fixture phone)", Locale.US),
                new DeviceEmulation(new Viewport(390, 844), 3, true, true));
    }
}
