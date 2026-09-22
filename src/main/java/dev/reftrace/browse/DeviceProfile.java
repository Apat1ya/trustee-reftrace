package dev.reftrace.browse;

import dev.reftrace.config.BrowserEngine;

public record DeviceProfile(String name, BrowserEngine engine, BrowserIdentity identity, DeviceEmulation emulation) {

    public DeviceProfile {
        if (name.isBlank()) {
            throw new IllegalArgumentException("device profile needs a name");
        }
        if (emulation.deviceScaleFactor() <= 0) {
            throw new IllegalArgumentException("device profile " + name + " needs a positive scale factor but had "
                    + emulation.deviceScaleFactor());
        }
    }
}
