package dev.reftrace.browse.page;

import java.time.Duration;

public record BrowserTimeouts(Duration pageLoad,
                              Duration settleQuiet,
                              Duration settleMax,
                              Duration operation,
                              Duration clickNavigation) {
}
