package dev.reftrace.browse.page;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ExitRevealer {

    private static final Logger log = LoggerFactory.getLogger(ExitRevealer.class);

    private static final Duration REACTION = Duration.ofMillis(400);

    private static final double REACTION_POLL_MS = 50;

    static final Duration ACTION_TIMEOUT = Duration.ofMillis(1500);

    private final RevealPlan plan;
    private final ObservationRegistry observations;

    ExitRevealer(RevealPlan plan, ObservationRegistry observations) {
        this.plan = plan;
        this.observations = observations;
    }

    void reveal(Page page, BrowserContext context, PageProbe probe) {
        if (!plan.enabled()) {
            return;
        }
        probe.ensureInstalled();
        Run run = new Run(plan, WaitOutcome.ofCurrent(observations));
        named(page, context, probe, run);
        generic(page, context, probe, run);
        run.ended("reveal");
    }

    void openWayTo(Page page, PageProbe probe, Locator target, WaitOutcome wait) {
        if (!plan.enabled() || plan.menus().isEmpty()) {
            return;
        }
        probe.ensureInstalled();
        ElementHandle element = target.elementHandle(
                new Locator.ElementHandleOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
        Run run = new Run(plan, wait);
        try {
            String urlBefore = page.url();
            while (!probe.withinReach(element) && !run.spent()) {
                RevealPlan.Revealer menu = nextMenu(page, probe, plan.menus(), element);
                if (menu == null) {
                    log.debug("no menu opens the way to a link on {}", page.url());
                    return;
                }
                run.actions++;
                ElementHandle control = probe.candidate(0);
                if (control == null) {
                    return;
                }
                try {
                    if (menu.action() == RevealPlan.RevealAction.HOVER) {
                        control.hover(new ElementHandle.HoverOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
                    } else {
                        control.click(new ElementHandle.ClickOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
                    }
                } catch (RuntimeException e) {
                    if (PlaywrightErrors.lostBrowser(e)) {
                        throw e;
                    }
                    run.actionFailed("menu", e);
                    log.debug("menu {} did not open: {}", menu.name(), PlaywrightErrors.message(e));
                } finally {
                    control.dispose();
                }
                if (!page.url().equals(urlBefore)) {
                    log.debug("stopped opening menus: {} navigated to {}", menu.name(), page.url());
                    return;
                }
                awaitRestingOnScreen(probe, element);
            }
        } finally {
            run.ended("menu");
            element.dispose();
        }
    }

    private static RevealPlan.@Nullable Revealer nextMenu(Page page, PageProbe probe, List<RevealPlan.Revealer> menus,
                                                          ElementHandle target) {
        for (int number = 0; number < menus.size(); number++) {
            try {
                probe.offerMenu(page.locator(menus.get(number).selector()), number);
            } catch (RuntimeException e) {
                if (PlaywrightErrors.lostBrowser(e)) {
                    throw e;
                }
                log.debug("menu {} could not be looked up: {}", menus.get(number).name(), PlaywrightErrors.message(e));
            }
        }
        Integer chosen = probe.menuControl(target);
        return chosen == null ? null : menus.get(chosen);
    }

    @SuppressWarnings("EmptyCatch")
    private static void awaitRestingOnScreen(PageProbe probe, ElementHandle target) {
        try {
            probe.awaitRestingOnScreen(target, REACTION_POLL_MS, REACTION);
        } catch (TimeoutError _) {
        } catch (RuntimeException e) {
            if (PlaywrightErrors.lostBrowser(e)) {
                throw e;
            }
            log.debug("waiting for a menu to open failed: {}", PlaywrightErrors.message(e));
        }
    }

    private void named(Page page, BrowserContext context, PageProbe probe, Run run) {
        for (RevealPlan.Revealer revealer : plan.revealers()) {
            if (run.spent()) {
                return;
            }
            Locator locator = page.locator(revealer.selector()).first();
            int matches;
            try {
                matches = page.locator(revealer.selector()).count();
            } catch (RuntimeException e) {
                log.debug("revealer {} could not be looked up: {}", revealer.name(),
                        PlaywrightErrors.message(e));
                continue;
            }
            if (matches == 0) {
                log.debug("revealer {} matched nothing on {}", revealer.name(), page.url());
                continue;
            }
            run.actions++;
            boolean hover = revealer.action() == RevealPlan.RevealAction.HOVER;
            if (!perform(page, context, probe, run, revealer.name(), hover, () -> touchLocator(locator, hover))) {
                return;
            }
        }
    }

    private void generic(Page page, BrowserContext context, PageProbe probe, Run run) {
        if (run.spent() || hiddenExitCount(probe) == 0) {
            return;
        }
        for (PageProbe.RevealCandidate candidate : candidates(probe, run.plan.maxActions())) {
            if (run.spent()) {
                return;
            }
            run.actions++;
            if (!perform(page, context, probe, run, candidate.describe(), candidate.hover(),
                    () -> touchCandidate(probe, candidate))) {
                return;
            }
        }
    }

    private boolean perform(Page page, BrowserContext context, PageProbe probe, Run run, String from,
                            boolean hover, Runnable touch) {
        String urlBefore = page.url();
        List<Page> pagesBefore = context.pages();
        int visibleBefore = visibleExitCount(probe);
        try {
            touch.run();
        } catch (RuntimeException e) {
            if (PlaywrightErrors.lostBrowser(e)) {
                throw e;
            }
            run.actionFailed("reveal", e);
            log.debug("{} did not work out: {}", from, PlaywrightErrors.message(e));
            return true;
        }

        if (!page.url().equals(urlBefore)) {
            log.debug("stopped revealing: {} navigated to {}", from, page.url());
            return false;
        }
        awaitReaction(probe, visibleBefore);
        closeNewTabs(context, probe, pagesBefore, from);

        if (visibleExitCount(probe) == visibleBefore && !hover) {
            press(page);
        }
        return true;
    }

    private static void touchLocator(Locator locator, boolean hover) {
        if (hover) {
            locator.hover(new Locator.HoverOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
        } else {
            locator.click(new Locator.ClickOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
        }
    }

    private static void touchCandidate(PageProbe probe, PageProbe.RevealCandidate candidate) {
        ElementHandle element = probe.candidate(candidate.index());
        if (element == null) {
            return;
        }
        try {
            if (candidate.hover()) {
                element.hover(new ElementHandle.HoverOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
            } else {
                element.click(new ElementHandle.ClickOptions().setTimeout(Timeouts.millis(ACTION_TIMEOUT)));
            }
        } finally {
            element.dispose();
        }
    }

    private static void closeNewTabs(BrowserContext context, PageProbe probe, List<Page> pagesBefore, String from) {
        List<Page> opened = context.pages().stream().filter(page -> !pagesBefore.contains(page)).toList();
        for (Page tab : opened) {
            try {
                PageProbe tabProbe = probe.on(tab);
                tabProbe.ensureInstalled();
                int exits = tabProbe.exitAnchorCount();
                if (exits > 0) {
                    log.info("{} opened a tab holding {} exits, which cannot be checked from here",
                            from, exits);
                }
            } catch (RuntimeException e) {
                log.debug("a tab opened by {} could not be read: {}", from, PlaywrightErrors.message(e));
            }
        }
        ClickCapture.close(opened);
    }

    @SuppressWarnings("EmptyCatch")
    private static void awaitReaction(PageProbe probe, int visibleBefore) {
        try {
            probe.awaitVisibleExitsOtherThan(visibleBefore, REACTION_POLL_MS, REACTION);
        } catch (TimeoutError _) {
        } catch (RuntimeException e) {
            log.debug("waiting for the page to react failed: {}", PlaywrightErrors.message(e));
        }
    }

    private static void press(Page page) {
        try {
            page.keyboard().press("Escape");
        } catch (RuntimeException e) {
            log.debug("could not send Escape: {}", PlaywrightErrors.message(e));
        }
    }

    private static List<PageProbe.RevealCandidate> candidates(PageProbe probe, int max) {
        List<PageProbe.RevealCandidate> candidates = new ArrayList<>(probe.revealCandidates(max * 2));
        candidates.sort(Comparator.comparingInt((PageProbe.RevealCandidate candidate) -> -candidate.hiddenExits()));
        return candidates;
    }

    private static int hiddenExitCount(PageProbe probe) {
        try {
            return probe.hiddenExitCount();
        } catch (RuntimeException _) {
            return 0;
        }
    }

    private static int visibleExitCount(PageProbe probe) {
        try {
            return probe.visibleExitAnchorCount();
        } catch (RuntimeException _) {
            return -1;
        }
    }

    private static final class Run {

        private final RevealPlan plan;
        private final long deadline;
        private final WaitOutcome wait;

        private int actions;

        private Run(RevealPlan plan, WaitOutcome wait) {
            this.plan = plan;
            this.deadline = System.nanoTime() + plan.budget().toNanos();
            this.wait = wait;
        }

        private boolean spent() {
            return actions >= plan.maxActions() || System.nanoTime() >= deadline;
        }

        private void actionFailed(String waitedFor, RuntimeException e) {
            if (e instanceof TimeoutError) {
                wait.ended(waitedFor, ACTION_TIMEOUT, true);
            }
        }

        private void ended(String waitedFor) {
            wait.ended(waitedFor, plan.budget(), System.nanoTime() >= deadline);
        }
    }
}
