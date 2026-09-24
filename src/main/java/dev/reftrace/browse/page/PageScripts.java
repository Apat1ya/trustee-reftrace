package dev.reftrace.browse.page;

import com.microsoft.playwright.Page;
import dev.reftrace.browse.UrlPatterns;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class PageScripts {

    static final String AVOID_LABELS = "accept|agree|consent|allow|reject|decline|subscribe|submit|send"
            + "|buy|pay|order|checkout|log ?in|sign ?in|sign ?up|register|delete|remove|log ?out"
            + "|погоджу|прийня|згоден|згодн|принима|согла|купити|купить|увійти|войти"
            + "|zgadzam|akceptuj|kup |zaloguj";

    private static final String CONFIG_PLACEHOLDER = "REFTRACE_CONFIG";

    private static final String INSTALLED = "() => !!window.__reftrace";
    static final String NEXT_TASK = "() => new Promise(resolve => setTimeout(resolve, 0))";

    private final String source;

    public PageScripts(UrlPatterns patterns, List<String> unwalkedBlocks) {
        this.source = read("/playwright/probe.js").replace(CONFIG_PLACEHOLDER, config(patterns, unwalkedBlocks));
    }

    public String initScript() {
        return source;
    }

    public void ensureInstalled(Page page) {
        if (Boolean.TRUE.equals(page.evaluate(INSTALLED))) {
            return;
        }
        page.evaluate("() => { " + source + " }");
    }

    private static String config(UrlPatterns patterns, List<String> unwalkedBlocks) {
        List<Map<String, Object>> routes = patterns.routes().all().stream()
                .flatMap(route -> route.match().stream().<Map<String, Object>>map(match -> Map.of(
                        "host", match.host().pattern(),
                        "path", match.path(),
                        "follow", route.follow())))
                .toList();
        Map<String, Object> config = Map.of(
                "routes", routes,
                "exitPathSuffixes", patterns.exitPathSuffixes(),
                "avoidLabels", AVOID_LABELS,
                "unwalkedBlocks", unwalkedBlocks);
        return new ObjectMapper().writeValueAsString(config);
    }

    private static String read(String resource) {
        try (InputStream stream = PageScripts.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("page script " + resource + " is missing from the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("page script " + resource + " could not be read", e);
        }
    }
}
