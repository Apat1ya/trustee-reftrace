package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.config.CoverageMode;
import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.ReferralKey;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static dev.reftrace.testsupport.Fixtures.profile;
import static org.assertj.core.api.Assertions.assertThat;

class WalkVocabularyTest {

    private static final URI HOME = URI.create("https://trustee.io/");
    private static final URI CARDS = URI.create("https://trustee.io/ua/cards/");
    private static final ReferralKey K1 = ReferralKey.of("K1aaaaaaaaa");
    private static final ReferralKey K2 = ReferralKey.of("K2bbbbbbbbb");
    private static final Step.Enter ENTER = new Step.Enter(HOME, K1);
    private static final Step.Click CLICK =
            new Step.Click(new FoundLink(CARDS, "a.nav", "Cards", How.HREF, true, false));
    private static final StepTree.Node BROWSE = StepTree.of(List.of(new Scenario("browse",
            List.of(StepWord.ENTER, StepWord.CLICK_ANY)))).entered();

    @Test
    void theAddressDropsTheKeyAndTheFragmentButKeepsOtherParameters() {
        Landing landing = Landing.of(List.of(ENTER), false, URI.create("https://trustee.io/?utm=a&r=K1#top"), "r");

        assertThat(landing).isEqualTo(new Landing(URI.create("https://trustee.io/?utm=a"), Arrival.ENTERED));
    }

    @Test
    void theArrivalFollowsTheLastStepUnlessARedirectIntervened() {
        assertThat(Landing.of(List.of(ENTER, new Step.Enter(HOME, K2)), false, HOME, "r").arrival())
                .isEqualTo(Arrival.ENTERED_NEW_KEY);
        assertThat(Landing.of(List.of(ENTER, CLICK), false, CARDS, "r").arrival())
                .isEqualTo(Arrival.CLICKED_WITHOUT_KEY);
        Step.Click withKey =
                new Step.Click(new FoundLink(URI.create(CARDS + "?r=K1"), "a", "Cards", How.HREF, true, false));
        assertThat(Landing.of(List.of(ENTER, withKey), false, CARDS, "r").arrival())
                .isEqualTo(Arrival.CLICKED_WITH_KEY);
        assertThat(Landing.of(List.of(ENTER, new Step.Reload()), false, HOME, "r").arrival())
                .isEqualTo(Arrival.RELOADED);
        assertThat(Landing.of(List.of(ENTER, CLICK), true, CARDS, "r").arrival()).isEqualTo(Arrival.REDIRECTED);
    }

    @Test
    void eachCoverageModeFilesAVisitUnderItsOwnKey() {
        WalkTask clicked = new WalkTask(profile("desktop"), BROWSE, List.of(ENTER, CLICK),
                new Landing(HOME, Arrival.ENTERED));
        Landing landing = new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY);

        assertThat(Coverage.of(CoverageMode.ONCE_PER_PAGE).seenKey(clicked, landing))
                .isEqualTo(new SeenKey.Page(CARDS));
        assertThat(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL).seenKey(clicked, landing))
                .isEqualTo(new SeenKey.Arrived(CARDS, Arrival.CLICKED_WITHOUT_KEY));
        assertThat(Coverage.of(CoverageMode.ONCE_PER_LINK).seenKey(clicked, landing))
                .isEqualTo(new SeenKey.Traversed(HOME, CARDS, CARDS));
    }

    @Test
    void aPageEnteredRatherThanClickedIsFiledByItsArrivalUnderOncePerLink() {
        WalkTask entered = new WalkTask(profile("desktop"), BROWSE, List.of(ENTER), null);

        assertThat(Coverage.of(CoverageMode.ONCE_PER_LINK).seenKey(entered, new Landing(HOME, Arrival.ENTERED)))
                .isEqualTo(new SeenKey.Arrived(HOME, Arrival.ENTERED));
    }

    @Test
    void aReloadIsFiledUnderWhatItReloaded() {
        Landing clicked = new Landing(CARDS, Arrival.CLICKED_WITHOUT_KEY);
        WalkTask reload = new WalkTask(profile("desktop"), BROWSE, List.of(ENTER, CLICK, new Step.Reload()),
                clicked);

        for (CoverageMode mode : CoverageMode.values()) {
            assertThat(Coverage.of(mode).seenKey(reload, new Landing(CARDS, Arrival.RELOADED))).as("%s", mode)
                    .isEqualTo(new SeenKey.Reloaded(CARDS, Arrival.CLICKED_WITHOUT_KEY));
        }
        assertThat(Coverage.of(CoverageMode.ONCE_PER_ARRIVAL).seenKey(reload, new Landing(HOME, Arrival.REDIRECTED)))
                .isEqualTo(new SeenKey.Arrived(HOME, Arrival.REDIRECTED));
    }
}
