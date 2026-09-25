package dev.reftrace.browse.page;

import java.time.Duration;
import java.util.List;

public record RevealPlan(boolean enabled, Duration budget, int maxActions, List<Revealer> revealers,
                         List<Revealer> menus) {

    public RevealPlan {
        revealers = List.copyOf(revealers);
        menus = List.copyOf(menus);
    }

    public record Revealer(String name, String selector, RevealAction action) {
    }

    public enum RevealAction {
        HOVER,
        CLICK
    }
}
