package dev.reftrace.browse;

import dev.reftrace.config.ReftraceProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class PropertiesMapping {

    private final ReftraceProperties properties;

    public PropertiesMapping(ReftraceProperties properties) {
        this.properties = properties;
    }

    public List<DeviceProfile> deviceProfiles() {
        return properties.profiles().stream()
                .map(PropertiesMapping::toDeviceProfile)
                .toList();
    }

    private static DeviceProfile toDeviceProfile(ReftraceProperties.Profile profile) {
        ReftraceProperties.Profile.ViewportSize viewport = profile.viewport();
        return new DeviceProfile(profile.name(),
                profile.engine(),
                new BrowserIdentity(profile.userAgent(), locale(profile.locale())),
                new DeviceEmulation(new Viewport(viewport.width(), viewport.height()),
                        profile.deviceScaleFactor(),
                        profile.mobile(),
                        profile.touch()));
    }

    private static @Nullable Locale locale(@Nullable String languageTag) {
        return languageTag == null || languageTag.isBlank() ? null : Locale.forLanguageTag(languageTag.trim());
    }
}
