package dev.reftrace.browse;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UrlBoundariesTest {

    private static final String DEEP_LINK = "https://trusteeplus.app.link/Sp1keKey9Zx";
    private static final String UNREADABLE = "https://trusteeplus.app.link/Sp1keKey 9Zx";

    @Test
    void leavesAnAnchorWithoutAUrlWhenItsHrefCannotBeRead() {
        DomAnchor anchor = DomAnchor.of("a#9", UNREADABLE, "Install", true, "a#9", false);

        assertThat(anchor.href()).isNull();
        assertThat(anchor.locator()).isEqualTo("a#9");
        assertThat(DomAnchor.of("a#0", DEEP_LINK, "link", true, "a#0", false).href())
                .isEqualTo(URI.create(DEEP_LINK));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomAnchor.of("", DEEP_LINK, null, true, "a", false));
    }

    @Test
    void reportsAClickWhoseAddressCannotBeReadAsAFailureThatNamesIt() {
        ClickObservation click = ClickObservation.navigated(UNREADABLE, false);

        assertThat(click).isInstanceOfSatisfying(ClickObservation.Failed.class, failed -> {
            assertThat(failed.error().kind()).isEqualTo(UntestedReason.NAVIGATION_ERROR);
            assertThat(failed.error().message()).contains(UNREADABLE);
        });
        assertThat(click.pageIntact()).isFalse();
        assertThat(ClickObservation.navigated(DEEP_LINK, false))
                .isEqualTo(new ClickObservation.Navigated(URI.create(DEEP_LINK), false));
    }
}
