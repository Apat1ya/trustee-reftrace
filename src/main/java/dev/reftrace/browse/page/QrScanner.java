package dev.reftrace.browse.page;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Clip;
import dev.reftrace.browse.HiddenQrCode;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.UntestedReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

final class QrScanner {

    private static final Logger log = LoggerFactory.getLogger(QrScanner.class);

    private static final String QR_PREFIX = "qr#";

    private final DriverThread thread;
    private final Page page;
    private final PageProbe probe;
    private final BrowserTimeouts timeouts;

    QrScanner(DriverThread thread, Page page, PageProbe probe, BrowserTimeouts timeouts) {
        this.thread = thread;
        this.page = page;
        this.probe = probe;
        this.timeouts = timeouts;
    }

    List<QrReading> qrCodes() {
        List<QrReading> readings = new ArrayList<>();
        for (PageProbe.QrCandidate candidate : candidates()) {
            if (!candidate.visible()) {
                log.debug("QR candidate {} ({}) is hidden on {} and was not read", candidate.locator(),
                        candidate.description(), page.url());
                continue;
            }
            QrDecoder.Result result = read(candidate.locator());
            String text = result.text();
            if (text != null) {
                readings.add(new QrReading.Decoded(candidate.locator(), candidate.description(), text,
                        candidate.selector()));
            } else if (candidate.strong() || result.structureSeen()) {
                readings.add(new QrReading.Unreadable(candidate.locator(), candidate.description(),
                        candidate.selector()));
            }
        }
        return List.copyOf(readings);
    }

    List<HiddenQrCode> hiddenQrCodes() {
        return candidates().stream()
                .filter(candidate -> !candidate.visible() && candidate.strong())
                .map(candidate -> new HiddenQrCode(candidate.selector(), candidate.description()))
                .toList();
    }

    private List<PageProbe.QrCandidate> candidates() {
        try {
            probe.ensureInstalled();
            return probe.qrCandidates();
        } catch (RuntimeException e) {
            throw thread.translate(e, UntestedReason.INTERNAL);
        }
    }

    private QrDecoder.Result read(String locator) {
        int index = Integer.parseInt(locator.substring(QR_PREFIX.length()));
        double timeout = Timeouts.millis(timeouts.operation());
        try {
            Clip box = probe.inView(page.locator(PageProbe.QR_SELECTOR).nth(index), timeout);
            byte[] picture = page.screenshot(new Page.ScreenshotOptions().setClip(box).setTimeout(timeout));
            return QrDecoder.decode(picture);
        } catch (RuntimeException e) {
            if (PlaywrightErrors.lostBrowser(e)) {
                throw thread.translate(e, UntestedReason.BROWSER_CRASH);
            }
            log.debug("QR candidate {} could not be photographed: {}", locator, PlaywrightErrors.message(e));
            return new QrDecoder.Result(null, false);
        }
    }
}
