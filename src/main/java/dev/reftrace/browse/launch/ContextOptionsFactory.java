package dev.reftrace.browse.launch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import dev.reftrace.browse.BrowserIdentity;
import dev.reftrace.browse.DeviceEmulation;
import dev.reftrace.browse.DeviceProfile;

import java.util.Locale;

public final class ContextOptionsFactory {

    public Browser.NewContextOptions create(DeviceProfile profile) {
        DeviceEmulation emulation = profile.emulation();
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(emulation.viewport().width(), emulation.viewport().height())
                .setDeviceScaleFactor(emulation.deviceScaleFactor())
                .setIsMobile(emulation.mobile())
                .setHasTouch(emulation.touch())
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                .setAcceptDownloads(false);
        BrowserIdentity identity = profile.identity();
        String userAgent = identity.userAgent();
        if (userAgent != null && !userAgent.isBlank()) {
            options.setUserAgent(userAgent);
        }
        Locale locale = identity.locale();
        if (locale != null) {
            options.setLocale(locale.toLanguageTag());
        }
        return options;
    }
}
