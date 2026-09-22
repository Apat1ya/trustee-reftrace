package dev.reftrace.testsupport.fakebrowser;

import dev.reftrace.browse.BrowserWorker;
import dev.reftrace.browse.BrowserWorkerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScriptedBrowserWorkerFactory implements BrowserWorkerFactory {

    private final ScriptedSite site;
    private final List<ScriptedBrowserWorker> created = new CopyOnWriteArrayList<>();
    private final List<Thread> creatingThreads = new CopyOnWriteArrayList<>();
    private final List<Thread> closingThreads = new CopyOnWriteArrayList<>();
    private final AtomicInteger creationsToFail = new AtomicInteger();
    private final AtomicInteger sessionsToFail = new AtomicInteger();

    public ScriptedBrowserWorkerFactory(ScriptedSite site) {
        this.site = site;
    }

    public ScriptedBrowserWorkerFactory failNextCreations(int times) {
        creationsToFail.set(times);
        return this;
    }

    public ScriptedBrowserWorkerFactory failNextSessions(int times) {
        sessionsToFail.set(times);
        return this;
    }

    boolean sessionFails() {
        return sessionsToFail.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
    }

    @Override
    public BrowserWorker create() {
        if (creationsToFail.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new IllegalStateException("no browser could be started");
        }
        creatingThreads.add(Thread.currentThread());
        ScriptedBrowserWorker worker = new ScriptedBrowserWorker(site, this);
        created.add(worker);
        return worker;
    }

    public List<ScriptedBrowserWorker> created() {
        return List.copyOf(created);
    }

    public List<Thread> creatingThreads() {
        return List.copyOf(creatingThreads);
    }

    public List<Thread> closingThreads() {
        return List.copyOf(closingThreads);
    }

    void recordClose() {
        closingThreads.add(Thread.currentThread());
    }
}
