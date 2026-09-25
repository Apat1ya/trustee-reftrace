package dev.reftrace.browse.launch;

import com.microsoft.playwright.Playwright;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class DriverProcessKiller {

    private static final Logger log = LoggerFactory.getLogger(DriverProcessKiller.class);
    private static final Object CREATION_LOCK = new Object();

    private static final Map<String, String> DRIVER_ENVIRONMENT = Map.of("PW_TEST_SCREENSHOT_NO_FONTS_READY", "1");

    private DriverProcessKiller() {
    }

    record TrackedDriver(Playwright playwright, @Nullable ProcessHandle process) {
    }

    static TrackedDriver create() {
        synchronized (CREATION_LOCK) {
            Set<Long> before = childPids();
            Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(DRIVER_ENVIRONMENT));
            ProcessHandle process = ProcessHandle.current().children()
                    .filter(child -> !before.contains(child.pid()))
                    .findFirst()
                    .orElse(null);
            if (process == null) {
                log.warn("the driver process could not be identified; a hung worker cannot be freed");
            }
            return new TrackedDriver(playwright, process);
        }
    }

    static void destroy(@Nullable ProcessHandle process) {
        if (process == null) {
            return;
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        } catch (RuntimeException e) {
            log.warn("could not destroy the driver process tree: {}", e.toString());
        }
    }

    private static Set<Long> childPids() {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
    }
}
