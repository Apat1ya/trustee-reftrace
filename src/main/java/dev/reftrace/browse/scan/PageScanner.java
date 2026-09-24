package dev.reftrace.browse.scan;

import dev.reftrace.browse.DomAnchor;
import dev.reftrace.browse.FoundLink;
import dev.reftrace.browse.HiddenQrCode;
import dev.reftrace.browse.How;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.PageLoad;
import dev.reftrace.browse.PageScan;
import dev.reftrace.browse.QrReading;
import dev.reftrace.browse.StoredValue;
import dev.reftrace.browse.Untested;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Routes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;

public final class PageScanner {

    private final LinkDiscovery discovery;
    private final ArrivalStorageReader storage;
    private final ExitClickReader exits;
    private final boolean qrCheck;
    private final ObservationRegistry observations;

    public PageScanner(Routes routes, boolean qrCheck, ObservationRegistry observations) {
        this.discovery = new LinkDiscovery(routes);
        this.storage = new ArrivalStorageReader(routes);
        this.exits = new ExitClickReader(routes);
        this.qrCheck = qrCheck;
        this.observations = observations;
    }

    public PageScan scan(PageDriver page, Origin origin) {
        PageLoad load = page.lastLoad();
        List<FoundLink> links = new ArrayList<>();
        List<Untested> untested = new ArrayList<>();
        List<StoredValue> stored = switch (origin) {
            case Origin.Entered _ -> List.of();
            case Origin.Followed(FoundLink followed) -> storage.read(page, followed, untested);
        };
        List<DomAnchor> anchors = page.anchors();
        links.addAll(discovery.hrefLinks(anchors));
        if (qrCheck) {
            List<QrReading> readings = page.qrCodes();
            links.addAll(discovery.qrLinks(readings));
            List<HiddenQrCode> hidden = page.hiddenQrCodes();
            untested.addAll(discovery.qrProblems(readings, hidden));
        }
        Observation.createNotStarted("page.exit-clicks", observations)
                .observe(() -> exits.read(page, load, anchors, links, untested));
        return new PageScan(load.finalUrl(), links, stored, untested);
    }
}
