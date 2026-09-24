package dev.reftrace.browse.page;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightErrorsTest {

    @Test
    void takesWhatWentWrongOutOfAnErrorTheDriverReported() {
        TimeoutError error = new TimeoutError("""
                Error {
                  message='Timeout 5000ms exceeded.
                Call log:
                  - waiting for locator("a.menu")
                  name='TimeoutError
                  stack='TimeoutError: Timeout 5000ms exceeded.
                }""");

        assertThat(PlaywrightErrors.describe(error, UntestedReason.CLICK_FAILED))
                .isEqualTo(new TechnicalError(UntestedReason.CLICK_FAILED, "Timeout 5000ms exceeded."));
    }

    @Test
    void knowsALostBrowserWhenTheDriverReportsIt() {
        PlaywrightException error = new PlaywrightException("""
                Error {
                  message='Target page, context or browser has been closed
                  name='TargetClosedError
                  stack='TargetClosedError: Target page, context or browser has been closed
                }""");

        assertThat(PlaywrightErrors.lostBrowser(error)).isTrue();
    }

    @Test
    void namesTheKindOfErrorWhenTheDriverReportedNoMessage() {
        PlaywrightException error = new PlaywrightException("""
                Error {
                  message='
                  name='Error
                }""");

        assertThat(PlaywrightErrors.message(error)).isEqualTo("PlaywrightException");
    }

    @Test
    void takesTheFirstLineOfAnyOtherMessage() {
        assertThat(PlaywrightErrors.message(new PlaywrightException("Playwright connection closed\nmore")))
                .isEqualTo("Playwright connection closed");
    }
}
