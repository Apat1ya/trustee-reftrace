package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntSupplier;

final class PageStabilizer {

    private static final Logger log = LoggerFactory.getLogger(PageStabilizer.class);

    private final DriverThread thread;
    private final Page page;
    private final BrowserContext context;
    private final PageProbe probe;
    private final SettleWaiter settleWaiter;
    private final ExitRevealer revealer;
    private final IntSupplier mainFrameNavigations;
    private final ObservationRegistry observations;

    PageStabilizer(DriverThread thread, Page page, BrowserContext context, PageProbe probe,
                   BrowserTimeouts timeouts, ExitRevealer revealer, IntSupplier mainFrameNavigations,
                   ObservationRegistry observations) {
        this.thread = thread;
        this.page = page;
        this.context = context;
        this.probe = probe;
        this.settleWaiter = new SettleWaiter(timeouts);
        this.revealer = revealer;
        this.mainFrameNavigations = mainFrameNavigations;
        this.observations = observations;
    }

    void requireSettled(String url, int navigations) {
        SettleWaiter.Settled settled = settleAndReveal(url, navigations);
        if (settled.cappedOut()) {
            log.debug("gave up on {}: {}", url, settled.describe());
            throw PageCheckException.of(
                    new TechnicalError(UntestedReason.PAGE_LOAD_TIMEOUT, settled.describe()));
        }
    }

    SettleWaiter.Settled settleAndReveal(String url, int navigations) {
        Observation settle = Observation.createNotStarted("page.settle", observations);
        SettleWaiter.Settled settled = settle.observe(() -> {
            SettleWaiter.Settled waited = settleWaiter.await(page, probe, mainFrameNavigations, navigations);
            settle.lowCardinalityKeyValue("reftrace.capped-out", String.valueOf(waited.cappedOut()))
                    .highCardinalityKeyValue("reftrace.settle", waited.describe());
            return waited;
        });
        log.debug("{}: {}", url, settled.describe());
        Observation.createNotStarted("page.reveal", observations).observe(() -> {
            try {
                revealer.reveal(page, context, probe);
            } catch (RuntimeException e) {
                if (PlaywrightErrors.lostBrowser(e)) {
                    throw thread.translate(e, UntestedReason.BROWSER_CRASH);
                }
                log.debug("could not uncover the hidden exits on {}: {}", url, PlaywrightErrors.message(e));
            }
        });
        return settled;
    }
}
