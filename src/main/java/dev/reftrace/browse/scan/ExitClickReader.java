package dev.reftrace.browse.scan;

import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.How;
import dev.reftrace.browse.ObservedUrl;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Routes;

import java.net.URI;
import java.util.List;

final class ExitClickReader {

    private final Routes routes;

    ExitClickReader(Routes routes) {
        this.routes = routes;
    }

    void read(PageDriver page, PageLoad load, List<DomAnchor> anchors, List<FoundLink> links,
              List<Untested> untested) {
        for (DomAnchor anchor : anchors) {
            if (clickable(anchor)) {
                click(page, load, anchor, links, untested);
            }
        }
    }

    private boolean clickable(DomAnchor anchor) {
        URI href = anchor.href();
        return href != null && routes.routeFor(href).map(route -> !route.follow()).orElse(false);
    }

    private void click(PageDriver page, PageLoad load, DomAnchor anchor, List<FoundLink> links,
                       List<Untested> untested) {
        URI href = anchor.href();
        if (href == null) {
            return;
        }
        ClickObservation observation = page.clickExit(anchor.locator(), href.toString());
        switch (observation) {
            case ClickObservation.Navigated navigated -> {
                if (!ObservedUrl.sameIgnoringFragment(navigated.url(), href)
                        && routes.routeFor(navigated.url()).isPresent()) {
                    links.add(new FoundLink(navigated.url(), anchor.selector(), LinkDiscovery.text(anchor),
                            How.CLICK, anchor.visible(), anchor.unwalked()));
                }
            }
            case ClickObservation.NoNavigation none -> untested.add(new Untested.Issue(
                    UntestedReason.CLICK_NO_NAVIGATION, anchor.selector(),
                    "nothing happened within " + none.waited().toMillis() + " ms of the click on " + href));
            case ClickObservation.NotReachable _ -> {
            }
            case ClickObservation.Failed failed -> untested.add(new Untested.Issue(failed.error().kind(),
                    anchor.selector(), failed.error().message()));
        }
        if (!observation.pageIntact()) {
            page.open(load.finalUrl());
        }
    }
}
