package dev.reftrace.browse.page;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import dev.reftrace.browse.page.ClickFailure.Blocker;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ClickFailureTest {

    private static final Duration OPERATION = Duration.ofSeconds(5);
    private static final String SELECTOR = "a.style_wallets__item__rMYND:nth-of-type(20)";

    private static final String IMAGE = "<img alt=\"coin\" srcset=\"" + "x".repeat(400) + "\"/>";
    private static final String IMAGE_CUT_SHORT = IMAGE.substring(0, 200) + "…";

    private static final String MODAL = "<div class=\"style_general-modal__il7hM\">…</div> from <main>…</main> subtree";

    @ParameterizedTest(name = "{0}")
    @MethodSource("timedOut")
    void readsWhatKeptTheClickFromHappening(String name, List<String> log, Blocker blocker,
                                            @Nullable String interceptor, String detail) {
        ClickFailure failure = ClickFailure.of(timeout(log));

        assertThat(failure.blocker()).isEqualTo(blocker);
        assertThat(failure.interceptor()).isEqualTo(interceptor);
        assertThat(failure.timedOut()).isTrue();
        assertThat(failure.detail(SELECTOR, OPERATION)).isEqualTo(detail);
    }

    static Stream<Arguments> timedOut() {
        return Stream.of(
                Arguments.of("the cookie modal takes the pointer", List.of(
                        "  - waiting for locator(\"a.style_wallets__item__rMYND:nth-of-type(20)\")",
                        "    - locator resolved to <a class=\"style_wallets__item__rMYND\" href=\"https://trustee.io/wallet/dot/\">…</a>",
                        "  - attempting click action",
                        "    2 × waiting for element to be visible, enabled and stable",
                        "      - element is visible, enabled and stable",
                        "      - " + MODAL + " intercepts pointer events",
                        "    - retrying click action",
                        "      - waiting 500ms"),
                        Blocker.INTERCEPTED, MODAL, "not clickable: covered by " + MODAL),
                Arguments.of("the flat entries of a saved trace", List.of(
                        "attempting click action",
                        "  element is visible, enabled and stable",
                        "  " + MODAL + " intercepts pointer events",
                        "retrying click action"),
                        Blocker.INTERCEPTED, MODAL, "not clickable: covered by " + MODAL),
                Arguments.of("a folded run of attempts", List.of(
                        "  - attempting click action",
                        "    - " + MODAL + " intercepts pointer events",
                        "    3 × element is not stable",
                        "    - retrying click action"),
                        Blocker.NOT_STABLE, null, "not clickable: kept moving"),
                Arguments.of("the last of several causes", List.of(
                        "  - attempting click action",
                        "      - element is not stable",
                        "    - retrying click action",
                        "      - " + MODAL + " intercepts pointer events",
                        "    - retrying click action",
                        "      - element is outside of the viewport",
                        "    - retrying click action",
                        "    - waiting for element to be visible, enabled and stable"),
                        Blocker.OUTSIDE_VIEWPORT, null, "not clickable: outside of the viewport"),
                Arguments.of("an element not shown", List.of(
                        "  - attempting click action",
                        "      - element is not visible",
                        "    - retrying click action"),
                        Blocker.NOT_VISIBLE, null, "not clickable: not visible"),
                Arguments.of("no answer before the timeout, or none it knows", List.of(
                        "  - attempting click action",
                        "    - waiting for element to be visible, enabled and stable",
                        "    - something Playwright will say one day"),
                        Blocker.NOT_READY, null, "not clickable: never became visible/ready within 5000 ms"),
                Arguments.of("a click made, whose navigation did not finish", List.of(
                        "  - attempting click action",
                        "    - " + MODAL + " intercepts pointer events",
                        "    - performing click action",
                        "    - click action done",
                        "    - waiting for scheduled navigations to finish"),
                        Blocker.CLICKED, null, "clicked, but the click did not finish within 5000 ms"),
                Arguments.of("a whole image with its srcset", List.of(
                        "      - " + IMAGE + " intercepts pointer events"),
                        Blocker.INTERCEPTED, IMAGE_CUT_SHORT, "not clickable: covered by " + IMAGE_CUT_SHORT));
    }

    @Test
    void anythingElseIsOtherAndSaysWhatWentWrong() {
        ClickFailure failure = ClickFailure.of(new PlaywrightException("""
                Error {
                  message='Element is not attached to the DOM
                  name='Error
                  stack='Error: Element is not attached to the DOM
                    at Frame.click
                }"""));

        assertThat(failure.blocker()).isEqualTo(Blocker.OTHER);
        assertThat(failure.timedOut()).isFalse();
        assertThat(failure.detail(SELECTOR, OPERATION))
                .isEqualTo("clicking " + SELECTOR + " failed: Element is not attached to the DOM");
        assertThat(ClickFailure.of(new IllegalStateException()).blocker()).isEqualTo(Blocker.OTHER);
    }

    @Test
    void aLinkAVisitorCouldNotPressSaysWhatKeptTheVisitorOut() {
        ClickFailure covered = ClickFailure.unreachable("intercepted", MODAL);

        assertThat(covered.blocker()).isEqualTo(Blocker.INTERCEPTED);
        assertThat(covered.timedOut()).isFalse();
        assertThat(covered.detail(SELECTOR, OPERATION)).isEqualTo("not clickable: covered by " + MODAL);
        assertThat(ClickFailure.unreachable("outside-viewport", null).detail(SELECTOR, OPERATION))
                .isEqualTo("not clickable: outside of the viewport");
        assertThat(ClickFailure.unreachable("not-visible", "<div>").interceptor()).isNull();
        assertThat(ClickFailure.unreachable("elsewhere", null).blocker()).isEqualTo(Blocker.OTHER);
    }

    private static TimeoutError timeout(List<String> log) {
        StringBuilder message = new StringBuilder("""
                Error {
                  message='Timeout 5000ms exceeded.
                  name='TimeoutError
                  stack='TimeoutError: Timeout 5000ms exceeded.
                    at _ProgressController.run (coreBundle.js:12360:32)
                }
                Call log:
                """);
        for (String entry : log) {
            message.append("- ").append(entry).append('\n');
        }
        return new TimeoutError(message.toString());
    }
}
