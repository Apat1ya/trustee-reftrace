package dev.reftrace.browse.launch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.ViewportSize;
import dev.reftrace.browse.BrowserIdentity;
import dev.reftrace.browse.DeviceEmulation;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.Viewport;
import dev.reftrace.config.BrowserEngine;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class ContextOptionsFactoryTest {

    private static final DeviceProfile PHONE = new DeviceProfile("android", BrowserEngine.CHROMIUM,
                new BrowserIdentity("Mozilla/5.0 (Linux; Android 14)", Locale.forLanguageTag("uk-UA")),
                new DeviceEmulation(new Viewport(412, 915), 2.625, true, true));

    @Test
    void carriesTheProfileIntoTheContext() {
        Browser.NewContextOptions options = new ContextOptionsFactory().create(PHONE);

        ViewportSize viewport = requireNonNull(options.viewportSize).orElseThrow();
        assertThat(viewport.width).isEqualTo(412);
        assertThat(viewport.height).isEqualTo(915);
        assertThat(options.deviceScaleFactor).isEqualTo(2.625);
        assertThat(options.isMobile).isTrue();
        assertThat(options.hasTouch).isTrue();
        assertThat(options.userAgent).isEqualTo("Mozilla/5.0 (Linux; Android 14)");
        assertThat(options.locale).isEqualTo("uk-UA");
    }

    @Test
    void refusesServiceWorkersAndDownloads() {
        Browser.NewContextOptions options = new ContextOptionsFactory().create(PHONE);

        assertThat(options.serviceWorkers).isEqualTo(ServiceWorkerPolicy.BLOCK);
        assertThat(options.acceptDownloads).isFalse();
    }

    @Test
    void leavesTheEngineDefaultUserAgentAloneWhenTheProfileHasNone() {
        DeviceProfile plain = new DeviceProfile("desktop", BrowserEngine.CHROMIUM,
                new BrowserIdentity(null, null),
                new DeviceEmulation(new Viewport(1440, 900), 1, false, false));

        Browser.NewContextOptions options = new ContextOptionsFactory().create(plain);

        assertThat(options.userAgent).isNull();
        assertThat(options.locale).isNull();
    }
}
