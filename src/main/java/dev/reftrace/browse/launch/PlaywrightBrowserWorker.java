package dev.reftrace.browse.launch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.BrowserStartException;
import dev.reftrace.browse.BrowserWorker;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.page.DriverThread;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.browse.page.PlaywrightErrors;
import dev.reftrace.browse.page.PlaywrightPageDriver;
import dev.reftrace.browse.page.Timeouts;
import dev.reftrace.config.BrowserEngine;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlaywrightBrowserWorker implements BrowserWorker {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserWorker.class);

    private final DriverThread thread = new DriverThread();
    private final BrowserSettings settings;
    private final ContextOptionsFactory contextOptions;
    private final PageScripts scripts;
    private final ObservationRegistry observations;
    private final Playwright playwright;
    private final @Nullable ProcessHandle driverProcess;
    private final Map<BrowserEngine, Browser> browsers = new EnumMap<>(BrowserEngine.class);
    private final List<PlaywrightBrowserSession> sessions = new ArrayList<>();

    private boolean closed;

    PlaywrightBrowserWorker(BrowserSettings settings, PageScripts scripts, ObservationRegistry observations) {
        this.settings = settings;
        this.contextOptions = new ContextOptionsFactory();
        this.scripts = scripts;
        this.observations = observations;
        DriverProcessKiller.TrackedDriver tracked = DriverProcessKiller.create();
        this.playwright = tracked.playwright();
        this.driverProcess = tracked.process();
    }

    @Override
    public BrowserSession openSession(DeviceProfile profile) {
        thread.requireOwnerThread();
        requireUsable(profile);
        Browser browser;
        try {
            browser = browserFor(profile.engine());
        } catch (RuntimeException e) {
            throw startFailure(BrowserStartException.Stage.LAUNCH, profile, e);
        }
        return openIn(browser, profile);
    }

    private PlaywrightBrowserSession openIn(Browser browser, DeviceProfile profile) {
        Browser.NewContextOptions options = contextOptions.create(profile);
        BrowserContext context;
        try {
            context = browser.newContext(options);
        } catch (RuntimeException e) {
            throw startFailure(BrowserStartException.Stage.CONTEXT, profile, e);
        }
        try {
            PlaywrightBrowserSession session = startSession(context);
            sessions.add(session);
            return session;
        } catch (RuntimeException e) {
            closeQuietly(context);
            throw startFailure(BrowserStartException.Stage.SETUP, profile, e);
        }
    }

    private BrowserStartException startFailure(BrowserStartException.Stage stage, DeviceProfile profile,
                                               RuntimeException error) {
        String reason = thread.killed()
                ? "the browser stack was destroyed by the watchdog: " + PlaywrightErrors.message(error)
                : PlaywrightErrors.message(error);
        return new BrowserStartException(stage, profile, reason, error);
    }

    private Browser browserFor(BrowserEngine engine) {
        return browsers.computeIfAbsent(engine, this::launch);
    }

    private PlaywrightBrowserSession startSession(BrowserContext context) {
        context.setDefaultNavigationTimeout(Timeouts.millis(settings.timeouts().pageLoad()));
        context.setDefaultTimeout(Timeouts.millis(settings.timeouts().operation()));
        NetworkGuard guard = NetworkGuard.install(context, settings);
        context.addInitScript(scripts.initScript());
        Page page = context.newPage();
        if (settings.traces()) {
            context.tracing().start(new Tracing.StartOptions().setSnapshots(false).setScreenshots(true));
        }
        PlaywrightPageDriver driver = PlaywrightPageDriver.attach(thread, context, page, guard.observations(),
                scripts, settings.timeouts(), settings.reveal(), settings.clicks(), observations);
        return new PlaywrightBrowserSession(this, thread, context, driver);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeSessions();
        closeBrowsers();
        closeDriver();
    }

    private void closeSessions() {
        List.copyOf(sessions).forEach(PlaywrightBrowserSession::close);
        sessions.clear();
    }

    private void closeBrowsers() {
        browsers.values().forEach(browser -> {
            try {
                browser.close();
            } catch (RuntimeException e) {
                log.debug("browser did not close cleanly: {}", PlaywrightErrors.message(e));
            }
        });
        browsers.clear();
    }

    private void closeDriver() {
        try {
            playwright.close();
        } catch (RuntimeException e) {
            log.debug("driver did not close cleanly: {}", PlaywrightErrors.message(e));
        }
    }

    @Override
    public void forceKill() {
        thread.kill();
        log.warn("destroying the browser stack of worker thread {}", thread.ownerName());
        DriverProcessKiller.destroy(driverProcess);
    }

    void forget(PlaywrightBrowserSession session) {
        sessions.remove(session);
    }

    private void requireUsable(DeviceProfile profile) {
        if (thread.killed()) {
            throw new BrowserStartException(BrowserStartException.Stage.LAUNCH, profile,
                    "the browser stack was destroyed by the watchdog", null);
        }
        if (closed) {
            throw new IllegalStateException("this browser worker is closed");
        }
    }

    private Browser launch(BrowserEngine engine) {
        BrowserType type = engine == BrowserEngine.WEBKIT ? playwright.webkit() : playwright.chromium();
        return Observation.createNotStarted("browser.launch", observations)
                .lowCardinalityKeyValue("reftrace.engine", engine.name().toLowerCase(Locale.ROOT))
                .observe(() -> type.launch(new BrowserType.LaunchOptions().setHeadless(settings.headless())));
    }

    private static void closeQuietly(BrowserContext context) {
        try {
            context.close();
        } catch (RuntimeException e) {
            log.debug("context did not close cleanly: {}", PlaywrightErrors.message(e));
        }
    }
}
