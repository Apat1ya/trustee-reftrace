package dev.reftrace.browse.launch;

import dev.reftrace.browse.BrowserWorker;
import dev.reftrace.browse.BrowserWorkerFactory;
import dev.reftrace.browse.page.PageScripts;
import dev.reftrace.config.ReftraceProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

@Component
public class PlaywrightBrowserWorkerFactory implements BrowserWorkerFactory {

    private final BrowserSettings settings;
    private final PageScripts scripts;
    private final ObservationRegistry observations;

    public PlaywrightBrowserWorkerFactory(ReftraceProperties properties, RevealProperties reveal,
                                          ObservationRegistry observations) {
        this.settings = BrowserSettings.of(properties, reveal);
        this.scripts = new PageScripts(settings.urls(), settings.unwalkedBlocks());
        this.observations = observations;
    }

    @Override
    public BrowserWorker create() {
        return new PlaywrightBrowserWorker(settings, scripts, observations);
    }
}
