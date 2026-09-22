package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

public interface BrowserSession extends AutoCloseable {

    PageDriver page();

    boolean endTrace(@Nullable Path file);

    @Override
    void close();
}
