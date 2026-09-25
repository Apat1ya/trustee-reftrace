package dev.reftrace.crawl;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

public record Landing(URI address, Arrival arrival) {

    public static Landing of(List<Step> path, boolean redirected, URI landed, String keyParam) {
        URI address = UriComponentsBuilder.fromUri(landed).replaceQueryParam(keyParam).fragment(null)
                .build(true).toUri();
        if (redirected) {
            return new Landing(address, Arrival.REDIRECTED);
        }
        Arrival arrival = switch (path.getLast()) {
            case Step.Enter _ -> path.stream().filter(Step.Enter.class::isInstance).count() == 1
                    ? Arrival.ENTERED : Arrival.ENTERED_NEW_KEY;
            case Step.Click click -> UriComponentsBuilder.fromUri(click.link().url()).build(true)
                    .getQueryParams().containsKey(keyParam) ? Arrival.CLICKED_WITH_KEY : Arrival.CLICKED_WITHOUT_KEY;
            case Step.Reload _ -> Arrival.RELOADED;
        };
        return new Landing(address, arrival);
    }
}
