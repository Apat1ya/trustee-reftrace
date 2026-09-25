package dev.reftrace.browse.launch;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Tracing;
import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.page.DriverThread;
import dev.reftrace.browse.page.PlaywrightErrors;
import dev.reftrace.browse.page.PlaywrightPageDriver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PlaywrightBrowserSession implements BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserSession.class);

    private final PlaywrightBrowserWorker worker;
    private final DriverThread thread;
    private final BrowserContext context;
    private final PlaywrightPageDriver driver;
    private boolean closed;

    PlaywrightBrowserSession(PlaywrightBrowserWorker worker, DriverThread thread, BrowserContext context,
                             PlaywrightPageDriver driver) {
        this.worker = worker;
        this.thread = thread;
        this.context = context;
        this.driver = driver;
    }

    @Override
    public PageDriver page() {
        thread.requireOwnerThread();
        return driver;
    }

    @Override
    public boolean endTrace(@Nullable Path file) {
        thread.requireOwnerThread();
        boolean written = false;
        try {
            if (file == null) {
                context.tracing().stopChunk();
            } else {
                Files.createDirectories(file.toAbsolutePath().getParent());
                context.tracing().stopChunk(new Tracing.StopChunkOptions().setPath(file));
                written = true;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("the browser trace could not be ended: {}", e.toString());
        }
        try {
            context.tracing().startChunk();
        } catch (RuntimeException e) {
            log.debug("the next browser trace could not be started: {}", e.toString());
        }
        return written;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.forget(this);
        try {
            context.close();
        } catch (RuntimeException e) {
            log.debug("session did not close cleanly: {}", PlaywrightErrors.message(e));
        }
    }
}
