package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Expectation;
import dev.reftrace.judge.Check;
import dev.reftrace.judge.ReferralKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.reftrace.testsupport.Fixtures.profile;
import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotsTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final URI BUY_BTC = URI.create("https://trustee.io/buy/btc/?r=Ab1cD2eF3gH");

    @TempDir
    private Path run;

    @Test
    void aMismatchIsNamedAfterTheProfileThePageAndTheLinkText() throws IOException {
        Path file = new Screenshots(run).save(profile("desktop"), BUY_BTC,
                mismatch("https://apps.apple.com/app/id1", "App Store"), PNG);

        assertThat(file).isEqualTo(run.resolve("screenshots").resolve("desktop--buy-btc--app-store-1.png"));
        assertThat(Files.readAllBytes(file)).isEqualTo(PNG);
    }

    @Test
    void aLinkWhoseTextMakesNoFileNameIsNamedAfterItsHost() {
        Path file = new Screenshots(run).save(profile("desktop"), URI.create("https://trustee.io/"),
                mismatch("https://trusteeplus.app.link/K1", "Скачать"), PNG);

        assertThat(file).hasFileName("desktop--home--trusteeplus-app-link-1.png");
    }

    @Test
    void anUntestedItemIsNamedAfterWhyAndTheSameNameIsCountedOn() {
        Screenshots screenshots = new Screenshots(run);
        Check untested = new Check.NotTested(new Untested.Issue(UntestedReason.CLICK_NO_NAVIGATION, "a.btn", "nothing"));

        screenshots.save(profile("desktop"), BUY_BTC, untested, PNG);
        Path second = screenshots.save(profile("desktop"), BUY_BTC, untested, PNG);

        assertThat(second).hasFileName("desktop--buy-btc--click-no-navigation-2.png");
    }

    @Test
    void aPictureThatCannotBeWrittenIsNoFile() throws IOException {
        Files.writeString(run.resolve("screenshots"), "a file where the directory should be");

        assertThat(new Screenshots(run).save(profile("desktop"), BUY_BTC, mismatch("https://t.ki/x", "x"), PNG))
                .isNull();
    }

    private static Check mismatch(String url, String text) {
        return new Check.Mismatch(new FoundLink(URI.create(url), "a.store", text, How.HREF, true, false),
                "direct-store-link", ReferralKey.of("K1aaaaaaaaa"),
                List.of(new Check.Mismatch.Unmet(new Expectation.Fail(), null)));
    }
}
