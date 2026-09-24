package dev.reftrace.browse.scan;

import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.HiddenQrCode;
import dev.reftrace.browse.How;
import dev.reftrace.browse.ObservedUrl;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Routes;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class LinkDiscovery {

    private final Routes routes;

    LinkDiscovery(Routes routes) {
        this.routes = routes;
    }

    List<FoundLink> hrefLinks(List<DomAnchor> anchors) {
        List<FoundLink> links = new ArrayList<>();
        for (DomAnchor anchor : anchors) {
            URI href = anchor.href();
            if (href != null && routes.routeFor(href).isPresent()) {
                links.add(new FoundLink(href, anchor.selector(), text(anchor), How.HREF, anchor.visible(),
                        anchor.unwalked()));
            }
        }
        return links;
    }

    List<FoundLink> qrLinks(List<QrReading> readings) {
        List<FoundLink> links = new ArrayList<>();
        for (QrReading reading : readings) {
            if (reading instanceof QrReading.Decoded decoded) {
                ObservedUrl.parse(decoded.text())
                        .filter(url -> routes.routeFor(url).isPresent())
                        .ifPresent(url -> links.add(
                                new FoundLink(url, decoded.selector(), decoded.description(), How.QR, true, false)));
            }
        }
        return links;
    }

    List<Untested> qrProblems(List<QrReading> readings, List<HiddenQrCode> hidden) {
        List<Untested> untested = new ArrayList<>();
        for (QrReading reading : readings) {
            if (reading instanceof QrReading.Unreadable unreadable) {
                untested.add(new Untested.Issue(UntestedReason.QR_UNREADABLE, unreadable.selector(),
                        "the QR code " + unreadable.description() + " could not be read"));
            }
        }
        for (HiddenQrCode code : hidden) {
            untested.add(new Untested.Issue(UntestedReason.QR_HIDDEN, code.selector(),
                    "the QR code " + code.description() + " is not shown on this device"));
        }
        return untested;
    }

    static String text(DomAnchor anchor) {
        String label = anchor.label();
        return label == null ? "" : label;
    }
}
