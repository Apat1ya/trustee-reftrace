package dev.reftrace.browse.launch;

import com.microsoft.playwright.Playwright;

public final class PlaywrightFixture {

    private PlaywrightFixture() {
    }

    public static Playwright create() {
        return DriverProcessKiller.create().playwright();
    }
}
