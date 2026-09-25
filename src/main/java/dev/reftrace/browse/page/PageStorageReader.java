package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Expectation;
import org.jspecify.annotations.Nullable;

final class PageStorageReader {

    private static final String LOCAL_STORAGE_ITEM = "name => window.localStorage.getItem(name)";

    private final DriverThread thread;
    private final BrowserContext context;
    private final Page page;

    PageStorageReader(DriverThread thread, BrowserContext context, Page page) {
        this.thread = thread;
        this.context = context;
        this.page = page;
    }

    @Nullable String stored(Expectation.OnArrival expectation) {
        try {
            return switch (expectation) {
                case Expectation.Cookie(String name) -> context.cookies(page.url()).stream()
                        .filter(cookie -> cookie.name.equals(name))
                        .map(cookie -> cookie.value)
                        .findFirst()
                        .orElse(null);
                case Expectation.LocalStorage(String name) -> {
                    Object value = page.evaluate(LOCAL_STORAGE_ITEM, name);
                    yield value == null ? null : value.toString();
                }
            };
        } catch (RuntimeException e) {
            throw thread.translate(e, UntestedReason.INTERNAL);
        }
    }
}
