package dev.reftrace.crawl;

import dev.reftrace.browse.FoundLink;
import dev.reftrace.config.StepWord;
import dev.reftrace.judge.ReferralKey;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public sealed interface Step {

    record Enter(URI url, ReferralKey key) implements Step {
    }

    record Click(FoundLink link) implements Step {
    }

    record Reload() implements Step {
    }

    static List<String> spelled(List<Step> path, String keyParam) {
        List<String> words = new ArrayList<>();
        for (Step step : path) {
            words.add(switch (step) {
                case Enter enter -> (words.isEmpty() ? StepWord.ENTER : StepWord.ENTER_NEW_KEY).word() + " "
                        + UriComponentsBuilder.fromUri(enter.url()).replaceQueryParam(keyParam, enter.key().value())
                        .build(true).toUriString();
                case Click(FoundLink link) -> "click " + (link.text().isBlank()
                        ? link.selector() : "\"%s\"".formatted(link.text().strip()));
                case Reload _ -> StepWord.RELOAD.word();
            });
        }
        return words;
    }
}
