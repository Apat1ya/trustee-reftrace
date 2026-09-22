package dev.reftrace.browse;

public interface BrowserWorker extends AutoCloseable {

    BrowserSession openSession(DeviceProfile profile);

    @Override
    void close();

    void forceKill();
}
