package dev.reftrace.browse.launch;

import dev.reftrace.browse.UrlPatterns;
import dev.reftrace.browse.page.BrowserTimeouts;
import dev.reftrace.browse.page.RevealPlan;
import dev.reftrace.config.Clicks;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Routes;

import java.util.List;

public record BrowserSettings(boolean headless, UrlPatterns urls, BrowserTimeouts timeouts, RevealPlan reveal,
                              Clicks clicks, List<String> unwalkedBlocks, boolean traces) {

    public BrowserSettings {
        unwalkedBlocks = List.copyOf(unwalkedBlocks);
    }

    static final List<String> INSTALLER_SUFFIXES = List.of(".apk");

    static BrowserSettings of(ReftraceProperties properties, RevealProperties reveal) {
        ReftraceProperties.Browser browser = properties.browser();
        ReftraceProperties.Limits.Action timeouts = properties.limits().action();
        UrlPatterns urls = new UrlPatterns(INSTALLER_SUFFIXES, browser.blockedAnalyticsHosts(),
                new Routes(properties.routes()));
        return new BrowserSettings(browser.headless(), urls,
                new BrowserTimeouts(timeouts.pageLoad(), timeouts.settleQuiet(), timeouts.settleMax(),
                        timeouts.operation(), timeouts.clickNavigation()),
                revealPlan(reveal), browser.clicks(), browser.unwalkedBlocks(),
                properties.report().traces());
    }

    private static RevealPlan revealPlan(RevealProperties reveal) {
        return new RevealPlan(reveal.enabled(), reveal.budget(), reveal.maxActions(),
                revealers(reveal.revealers()), revealers(reveal.menus()));
    }

    private static List<RevealPlan.Revealer> revealers(List<RevealProperties.Revealer> configured) {
        return configured.stream()
                .map(revealer -> new RevealPlan.Revealer(revealer.name(), revealer.selector(),
                        switch (revealer.action()) {
                            case HOVER -> RevealPlan.RevealAction.HOVER;
                            case CLICK -> RevealPlan.RevealAction.CLICK;
                        }))
                .toList();
    }
}
