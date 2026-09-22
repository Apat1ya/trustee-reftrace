package dev.reftrace.judge;

import dev.reftrace.browse.UntestedReason;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTest {

    @Test
    void whatCouldNotBeTestedIsUntestedOrFailedByWhoseDoingItWas() {
        assertThat(Stream.of(UntestedReason.values()).filter(UntestedReason::bySite))
                .containsExactlyInAnyOrder(UntestedReason.HTTP_STATUS, UntestedReason.QR_UNREADABLE,
                        UntestedReason.QR_HIDDEN);
    }
}
