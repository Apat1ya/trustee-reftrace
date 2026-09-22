package dev.reftrace.testsupport.fakebrowser;

import dev.reftrace.browse.ClickObservation;
import dev.reftrace.browse.PageCheckException;
import dev.reftrace.browse.TechnicalError;
import dev.reftrace.browse.UntestedReason;
import dev.reftrace.config.Expectation;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScriptedSite {

    public static final String KEY = "{key}";

    private final Map<String, List<String>> exitsByPath = new LinkedHashMap<>();
    private final Map<String, List<String>> internalLinksByPath = new LinkedHashMap<>();
    private final Map<String, HttpStatusCode> statusByPath = new LinkedHashMap<>();
    private final Map<String, Deque<TechnicalError>> scriptedFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> opens = new ConcurrentHashMap<>();
    private final List<Optional<String>> screenshots = new CopyOnWriteArrayList<>();
    private final List<String> hangingPaths = new ArrayList<>();
    private final Map<String, Integer> hiddenQrCodesByPath = new LinkedHashMap<>();
    private final Map<String, String> redirects = new LinkedHashMap<>();
    private final Map<String, Map<Expectation.OnArrival, String>> storedByPath = new ConcurrentHashMap<>();
    private final Map<String, Map<Expectation.OnArrival, TechnicalError>> unreadableByPath = new ConcurrentHashMap<>();

    public ScriptedSite stores(String path, Expectation.OnArrival where, String value) {
        storedByPath.computeIfAbsent(path, _ -> new ConcurrentHashMap<>()).put(where, value);
        return this;
    }

    public ScriptedSite cannotRead(String path, Expectation.OnArrival where, UntestedReason kind, String message) {
        unreadableByPath.computeIfAbsent(path, _ -> new ConcurrentHashMap<>())
                .put(where, new TechnicalError(kind, message));
        return this;
    }

    public ScriptedSite page(String path, String... exitHrefs) {
        exitsByPath.put(path, List.of(exitHrefs));
        return this;
    }

    public ScriptedSite internalLink(String fromPath, String toPath) {
        internalLinksByPath.computeIfAbsent(fromPath, _ -> new ArrayList<>()).add(toPath);
        return this;
    }

    public ScriptedSite status(String path, int status) {
        statusByPath.put(path, HttpStatusCode.valueOf(status));
        return this;
    }

    public ScriptedSite failNextOpens(String path, int times, UntestedReason kind, String message) {
        Deque<TechnicalError> failures = scriptedFailures.computeIfAbsent(path, _ -> new ArrayDeque<>());
        synchronized (failures) {
            for (int i = 0; i < times; i++) {
                failures.add(new TechnicalError(kind, message));
            }
        }
        return this;
    }

    public ScriptedSite hangOnOpen(String path) {
        hangingPaths.add(path);
        return this;
    }

    public ScriptedSite hiddenQrCode(String path) {
        hiddenQrCodesByPath.merge(path, 1, Integer::sum);
        return this;
    }

    public ScriptedSite redirect(String fromPath, String toPath) {
        redirects.put(fromPath, toPath);
        return this;
    }

    Optional<URI> redirected(URI url) {
        String toPath = redirects.get(pathOf(url));
        return toPath == null ? Optional.empty()
                : Optional.of(UriComponentsBuilder.fromUri(url).replacePath(toPath).build(true).toUri());
    }

    public int opens(String path) {
        AtomicInteger counter = opens.get(path);
        return counter == null ? 0 : counter.get();
    }

    public List<Optional<String>> screenshots() {
        return List.copyOf(screenshots);
    }

    void recordScreenshot(@Nullable String selector) {
        screenshots.add(Optional.ofNullable(selector));
    }

    List<String> exitHrefs(String path) {
        return exitsByPath.getOrDefault(path, List.of());
    }

    List<String> internalLinks(String path) {
        return internalLinksByPath.getOrDefault(path, List.of());
    }

    int hiddenQrCodes(String path) {
        return hiddenQrCodesByPath.getOrDefault(path, 0);
    }

    boolean knows(String path) {
        return exitsByPath.containsKey(path) || internalLinksByPath.containsKey(path)
                || statusByPath.containsKey(path) || hangingPaths.contains(path);
    }

    boolean hangs(String path) {
        return hangingPaths.contains(path);
    }

    @Nullable HttpStatusCode statusOf(String path) {
        return statusByPath.get(path);
    }

    @Nullable TechnicalError nextFailure(String path) {
        Deque<TechnicalError> failures = scriptedFailures.get(path);
        if (failures == null) {
            return null;
        }
        synchronized (failures) {
            return failures.poll();
        }
    }

    @Nullable String stored(String path, Expectation.OnArrival where) {
        TechnicalError failure = unreadableByPath.getOrDefault(path, Map.of()).get(where);
        if (failure != null) {
            throw PageCheckException.of(failure);
        }
        return storedByPath.getOrDefault(path, Map.of()).get(where);
    }

    void recordOpen(String path) {
        opens.computeIfAbsent(path, _ -> new AtomicInteger()).incrementAndGet();
    }

    static String pathOf(URI url) {
        String path = url.getPath();
        return path == null || path.isBlank() ? "/" : path;
    }
}
