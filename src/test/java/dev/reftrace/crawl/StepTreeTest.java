package dev.reftrace.crawl;

import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static dev.reftrace.config.StepWord.CLICK_ANY;
import static dev.reftrace.config.StepWord.ENTER;
import static dev.reftrace.config.StepWord.ENTER_NEW_KEY;
import static dev.reftrace.config.StepWord.RELOAD;
import static org.assertj.core.api.Assertions.assertThat;

class StepTreeTest {

    @Test
    void scenariosThatStartAlikeShareTheirNodes() {
        StepTree tree = StepTree.of(List.of(
                scenario("entry", ENTER),
                scenario("browse", ENTER, CLICK_ANY),
                scenario("key-replaced", ENTER, CLICK_ANY, ENTER_NEW_KEY, CLICK_ANY),
                scenario("reload", ENTER, CLICK_ANY, RELOAD)));

        StepTree.Node entered = tree.entered();
        StepTree.Node browsed = child(entered, CLICK_ANY);
        StepTree.Node renewed = child(browsed, ENTER_NEW_KEY);

        assertThat(entered.word()).isEqualTo(ENTER);
        assertThat(entered.scenarios()).containsExactly("entry", "browse", "key-replaced", "reload");
        assertThat(entered.children()).containsOnlyKeys(CLICK_ANY);
        assertThat(browsed.scenarios()).containsExactly("browse", "key-replaced", "reload");
        assertThat(browsed.children().keySet()).containsExactly(ENTER_NEW_KEY, RELOAD);
        assertThat(renewed.scenarios()).containsExactly("key-replaced");
        assertThat(child(renewed, CLICK_ANY).children()).isEmpty();
        assertThat(child(browsed, RELOAD).scenarios()).containsExactly("reload");
    }

    private static StepTree.Node child(StepTree.Node node, StepWord word) {
        return Objects.requireNonNull(node.children().get(word), word.word());
    }

    private static Scenario scenario(String name, StepWord... steps) {
        return new Scenario(name, List.of(steps));
    }
}
