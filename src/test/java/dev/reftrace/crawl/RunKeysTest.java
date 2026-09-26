package dev.reftrace.crawl;

import dev.reftrace.judge.ReferralKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RunKeysTest {

    @Test
    void everyKeyLooksLikeTheSitesKeysAndHasEveryKindOfCharacter() {
        SplittableRandom random = new SplittableRandom(7);

        IntStream.range(0, 500).mapToObj(_ -> RunKeys.draw(random)).forEach(keys -> {
            for (ReferralKey key : List.of(keys.first(), keys.second())) {
                assertThat(key.value()).hasSize(11)
                        .containsPattern("[A-Z]").containsPattern("[a-z]").containsPattern("[0-9]");
            }
            assertThat(keys.first()).isNotEqualTo(keys.second());
        });
    }

    @Test
    void theSecondKeyIsDrawnAgainUntilItDiffers() {
        RandomGenerator repeatsOnce = new RandomGenerator() {
            private int calls;

            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                return calls++ < 2 * 21 ? 0 : 1;
            }
        };

        RunKeys keys = RunKeys.draw(repeatsOnce);

        assertThat(keys.first()).isNotEqualTo(keys.second());
    }
}
