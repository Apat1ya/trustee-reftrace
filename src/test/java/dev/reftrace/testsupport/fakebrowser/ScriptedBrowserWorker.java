package dev.reftrace.testsupport.fakebrowser;

import dev.reftrace.browse.BrowserSession;
import dev.reftrace.browse.BrowserStartException;
import dev.reftrace.browse.BrowserWorker;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.PageDriver;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScriptedBrowserWorker implements BrowserWorker {

    private final ScriptedSite site;
    private final ScriptedBrowserWorkerFactory factory;
    private final Thread owner;
    private final AtomicBoolean killed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch killSignal = new CountDownLatch(1);

    ScriptedBrowserWorker(ScriptedSite site, ScriptedBrowserWorkerFactory factory) {
        this.site = site;
        this.factory = factory;
        this.owner = Thread.currentThread();
    }

    @Override
    public BrowserSession openSession(DeviceProfile profile) {
        requireOwnThread();
        if (killed.get()) {
            throw new BrowserStartException(BrowserStartException.Stage.LAUNCH, profile,
                    "the browser stack was destroyed by the watchdog", null);
        }
        requireAlive();
        if (factory.sessionFails()) {
            throw new BrowserStartException(BrowserStartException.Stage.LAUNCH, profile,
                    "Executable doesn't exist", new IllegalStateException("Executable doesn't exist"));
        }
        PageDriver driver = new ScriptedPageDriver(site, this);
        return new BrowserSession() {

            @Override
            public PageDriver page() {
                return driver;
            }

            @Override
            public boolean endTrace(@Nullable Path file) {
                requireOwnThread();
                return false;
            }

            @Override
            public void close() {
                requireOwnThread();
            }
        };
    }

    @Override
    public void close() {
        requireOwnThread();
        closed.set(true);
        killSignal.countDown();
        factory.recordClose();
    }

    @Override
    public void forceKill() {
        killed.set(true);
        killSignal.countDown();
    }

    public boolean isClosed() {
        return closed.get();
    }

    void requireOwnThread() {
        if (!Thread.currentThread().equals(owner)) {
            throw new IllegalStateException("a browser worker created on " + owner.getName()
                    + " was used from " + Thread.currentThread().getName());
        }
    }

    void requireAlive() {
        if (killed.get() || closed.get()) {
            throw PageCheckException.of(new TechnicalError(UntestedReason.BROWSER_CRASH,
                    "the browser of this worker is gone"));
        }
    }

    void awaitKill() {
        try {
            killSignal.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
