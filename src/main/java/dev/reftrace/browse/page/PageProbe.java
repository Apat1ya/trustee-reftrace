package dev.reftrace.browse.page;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Clip;
import dev.reftrace.browse.DomAnchor;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PageProbe {

    static final String QR_SELECTOR = "svg, canvas, img";

    private static final String ANCHORS = "() => window.__reftrace.anchors()";
    private static final String EXIT_ANCHORS = "() => window.__reftrace.exitAnchors()";
    private static final String QR_CODES = "() => window.__reftrace.qrCodes()";
    private static final String SETTLED = "options => window.__reftrace.settled(options)";
    private static final String RESTART_QUIET = "() => window.__reftrace.restartQuiet()";
    private static final String STATE = "() => window.__reftrace.state()";
    private static final String REVEAL_CANDIDATES = "options => window.__reftrace.revealCandidates(options)";
    private static final String CANDIDATE = "index => window.__reftrace.candidates[index]";
    private static final String HIDDEN_EXIT_COUNT = "() => window.__reftrace.hiddenExitCount()";
    private static final String RELOCATE = "options => window.__reftrace.relocate(options)";
    private static final String ON_SCREEN = "element => window.__reftrace.onScreen(element)";
    private static final String RESTING_ON_SCREEN = "element => window.__reftrace.restingOnScreen(element)";
    private static final String WITHIN_REACH = "element => window.__reftrace.withinReach(element)";
    private static final String REACH = "element => window.__reftrace.reach(element)";
    private static final String IN_VIEW = "element => window.__reftrace.inView(element)";
    private static final String OFFER_MENU = "(elements, number) => window.__reftrace.offerMenu(elements, number)";
    private static final String MENU_CONTROL = "target => window.__reftrace.menuControl(target)";
    private static final String VISIBLE_EXITS_CHANGED =
            "previous => window.__reftrace.exitAnchors().filter(exit => exit.visible).length !== previous";
    private static final String OUTLINE = "selector => window.__reftrace && window.__reftrace.outline(selector)"
            + " ? 'outlined' : 'absent'";
    private static final String OUTLINED = "outlined";
    private static final String UNOUTLINE =
            "() => { if (window.__reftrace) { window.__reftrace.unoutline(); } return 'done'; }";

    private static final double SCRIPT_POLL_MS = 50;

    private final Page page;
    private final PageScripts scripts;

    PageProbe(Page page, PageScripts scripts) {
        this.page = page;
        this.scripts = scripts;
    }

    PageProbe on(Page tab) {
        return new PageProbe(tab, scripts);
    }

    void ensureInstalled() {
        scripts.ensureInstalled(page);
    }

    List<DomAnchor> anchors() {
        return rows(page.evaluate(ANCHORS)).stream()
                .map(PageProbe::toAnchor)
                .toList();
    }

    int exitAnchorCount() {
        return rows(page.evaluate(EXIT_ANCHORS)).size();
    }

    int visibleExitAnchorCount() {
        return (int) rows(page.evaluate(EXIT_ANCHORS)).stream()
                .filter(exit -> Boolean.TRUE.equals(exit.get("visible")))
                .count();
    }

    int hiddenExitCount() {
        Object count = page.evaluate(HIDDEN_EXIT_COUNT);
        return count instanceof Number number ? number.intValue() : 0;
    }

    record QrCandidate(String locator, String description, String selector, boolean visible, boolean strong) {
    }

    List<QrCandidate> qrCandidates() {
        return rows(page.evaluate(QR_CODES)).stream()
                .map(row -> {
                    String locator = String.valueOf(row.get("locator"));
                    return new QrCandidate(locator, String.valueOf(row.get("description")), selectorOf(row, locator),
                            Boolean.TRUE.equals(row.get("visible")), Boolean.TRUE.equals(row.get("strong")));
                })
                .toList();
    }

    @Nullable String relocate(String selector, String href, String text) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("selector", selector);
        options.put("href", href);
        options.put("text", text);
        Object relocated = page.evaluate(RELOCATE, options);
        return relocated == null ? null : String.valueOf(relocated);
    }

    void awaitSettled(int quietMs, boolean requireMarker, double pollingIntervalMs, double timeoutMs) {
        dispose(page.waitForFunction(SETTLED, settleOptions(quietMs, requireMarker),
                new Page.WaitForFunctionOptions().setPollingInterval(pollingIntervalMs).setTimeout(timeoutMs)));
    }

    void restartQuiet() {
        page.evaluate(RESTART_QUIET);
    }

    boolean settled(int quietMs, boolean requireMarker) {
        return Boolean.TRUE.equals(page.evaluate(SETTLED, settleOptions(quietMs, requireMarker)));
    }

    boolean markerShown() {
        Object state = page.evaluate(STATE);
        return state instanceof Map<?, ?> values && (Boolean.TRUE.equals(values.get("exitOnShow"))
                || Boolean.TRUE.equals(values.get("qrOnShow")));
    }

    record RevealCandidate(int index, String kind, String label, String tag, int hiddenExits) {

        boolean hover() {
            return "hover".equals(kind);
        }

        String describe() {
            return kind + " " + (label.isBlank() ? tag : tag + " \"" + label + "\"");
        }
    }

    List<RevealCandidate> revealCandidates(int max) {
        List<RevealCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows(page.evaluate(REVEAL_CANDIDATES, Map.of("max", max)))) {
            candidates.add(new RevealCandidate(number(row.get("index")), String.valueOf(row.get("kind")),
                    String.valueOf(row.getOrDefault("label", "")), String.valueOf(row.getOrDefault("tag", "")),
                    number(row.get("hiddenExits"))));
        }
        return candidates;
    }

    @Nullable ElementHandle candidate(int index) {
        JSHandle handle = page.evaluateHandle(CANDIDATE, index);
        ElementHandle element = handle.asElement();
        if (element == null) {
            handle.dispose();
        }
        return element;
    }

    boolean onScreen(Locator element) {
        return Boolean.TRUE.equals(element.evaluate(ON_SCREEN));
    }

    void awaitRestingOnScreen(ElementHandle element, double pollingIntervalMs, Duration timeout) {
        dispose(page.waitForFunction(RESTING_ON_SCREEN, element, new Page.WaitForFunctionOptions()
                .setPollingInterval(pollingIntervalMs).setTimeout(Timeouts.millis(timeout))));
    }

    boolean withinReach(ElementHandle element) {
        return Boolean.TRUE.equals(page.evaluate(WITHIN_REACH, element));
    }

    Optional<ClickFailure> unreachable(Locator element) {
        Object answer = element.evaluate(REACH);
        if (!(answer instanceof Map<?, ?> reach)) {
            throw new IllegalStateException("the probe gave no answer about reaching the element: " + answer);
        }
        Object blocker = reach.get("blocker");
        Object interceptor = reach.get("interceptor");
        return blocker == null ? Optional.empty() : Optional.of(ClickFailure.unreachable(String.valueOf(blocker),
                interceptor == null ? null : String.valueOf(interceptor)));
    }

    Clip inView(Locator element, double timeoutMs) {
        Object box = element.evaluate(IN_VIEW, null, new Locator.EvaluateOptions().setTimeout(timeoutMs));
        if (!(box instanceof Map<?, ?> values)) {
            throw new IllegalStateException("the probe gave no box for the element: " + box);
        }
        return new Clip(decimal(values.get("x")), decimal(values.get("y")),
                decimal(values.get("width")), decimal(values.get("height")));
    }

    void offerMenu(Locator menu, int number) {
        menu.evaluateAll(OFFER_MENU, number);
    }

    @Nullable Integer menuControl(ElementHandle target) {
        Object chosen = page.evaluate(MENU_CONTROL, target);
        return chosen instanceof Number number ? number.intValue() : null;
    }

    void awaitVisibleExitsOtherThan(int previous, double pollingIntervalMs, Duration timeout) {
        dispose(page.waitForFunction(VISIBLE_EXITS_CHANGED, previous, new Page.WaitForFunctionOptions()
                .setPollingInterval(pollingIntervalMs).setTimeout(Timeouts.millis(timeout))));
    }

    boolean outline(String selector, Duration timeout) {
        return OUTLINED.equals(bounded(OUTLINE, selector, timeout));
    }

    void unoutline(Duration timeout) {
        bounded(UNOUTLINE, null, timeout);
    }

    private String bounded(String script, @Nullable Object argument, Duration timeout) {
        JSHandle answer = page.waitForFunction(script, argument, new Page.WaitForFunctionOptions()
                .setPollingInterval(SCRIPT_POLL_MS).setTimeout(Timeouts.millis(timeout)));
        try {
            return String.valueOf(answer.jsonValue());
        } finally {
            dispose(answer);
        }
    }

    private static Map<String, Object> settleOptions(int quietMs, boolean requireMarker) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("quietMs", quietMs);
        options.put("requireMarker", requireMarker);
        return options;
    }

    private static DomAnchor toAnchor(Map<String, Object> row) {
        String locator = String.valueOf(row.get("locator"));
        return DomAnchor.of(locator,
                String.valueOf(row.get("href")),
                String.valueOf(row.getOrDefault("label", "")),
                Boolean.TRUE.equals(row.get("visible")),
                selectorOf(row, locator),
                Boolean.TRUE.equals(row.get("unwalked")));
    }

    private static String selectorOf(Map<String, Object> row, String locator) {
        Object selector = row.get("selector");
        return selector == null || String.valueOf(selector).isBlank() ? locator : String.valueOf(selector);
    }

    private static int number(@Nullable Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double decimal(@Nullable Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    @SuppressWarnings("EmptyCatch")
    private static void dispose(JSHandle handle) {
        try {
            handle.dispose();
        } catch (RuntimeException _) {
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(@Nullable Object result) {
        return result instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
