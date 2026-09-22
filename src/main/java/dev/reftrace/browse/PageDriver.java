package dev.reftrace.browse;

import dev.reftrace.config.Expectation;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;

public interface PageDriver {

    PageLoad open(URI url);

    PageLoad reload();

    Navigation follow(FoundLink link);

    PageLoad lastLoad();

    URI currentUrl();

    List<DomAnchor> anchors();

    List<QrReading> qrCodes();

    List<HiddenQrCode> hiddenQrCodes();

    ClickObservation clickExit(String locator, String expectedHref);

    byte @Nullable [] screenshot(@Nullable String selector);

    @Nullable String stored(Expectation.OnArrival expectation);
}
