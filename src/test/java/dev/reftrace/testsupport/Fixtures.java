package dev.reftrace.testsupport;

import dev.reftrace.browse.BrowserIdentity;
import dev.reftrace.browse.DeviceEmulation;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.Viewport;
import dev.reftrace.config.BrowserEngine;
import dev.reftrace.config.HostPattern;
import dev.reftrace.config.LinkMatch;

import java.util.Locale;

public final class Fixtures {

    private Fixtures() {
    }

    public static DeviceProfile profile(String name) {
        return new DeviceProfile(name, BrowserEngine.CHROMIUM,
                new BrowserIdentity("Mozilla/5.0 (" + name + ")", Locale.US),
                new DeviceEmulation(new Viewport(1440, 900), 1, false, false));
    }

    public static LinkMatch match(String spelled) {
        int slash = spelled.indexOf('/');
        return slash < 0
                ? new LinkMatch(HostPattern.of(spelled), "/**")
                : new LinkMatch(HostPattern.of(spelled.substring(0, slash)), spelled.substring(slash));
    }
}
