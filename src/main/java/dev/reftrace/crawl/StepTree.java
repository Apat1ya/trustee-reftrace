package dev.reftrace.crawl;

import dev.reftrace.config.Scenario;
import dev.reftrace.config.StepWord;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StepTree {

    private final Node entered;

    private StepTree(Node entered) {
        this.entered = entered;
    }

    public static StepTree of(List<Scenario> scenarios) {
        Builder root = new Builder(List.of());
        for (Scenario scenario : scenarios) {
            List<StepWord> steps = scenario.steps();
            if (steps.isEmpty() || steps.getFirst() != StepWord.ENTER || steps.lastIndexOf(StepWord.ENTER) != 0) {
                throw new IllegalArgumentException("scenario " + scenario.name()
                        + " has to start with its only enter: " + steps);
            }
            Builder at = root;
            for (StepWord step : steps) {
                at = at.child(step);
                at.scenarios.add(scenario.name());
            }
        }
        Builder entered = root.children.get(StepWord.ENTER);
        if (entered == null) {
            throw new IllegalArgumentException("a walk needs at least one scenario");
        }
        return new StepTree(entered.build());
    }

    public Node entered() {
        return entered;
    }

    public static final class Node {

        private final List<StepWord> prefix;
        private final Map<StepWord, Node> children;
        private final List<String> scenarios;

        private Node(List<StepWord> prefix, Map<StepWord, Node> children, List<String> scenarios) {
            this.prefix = List.copyOf(prefix);
            this.children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
            this.scenarios = List.copyOf(scenarios);
        }

        public StepWord word() {
            return prefix.getLast();
        }

        public Map<StepWord, Node> children() {
            return children;
        }

        public List<String> scenarios() {
            return scenarios;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Node node && node.prefix.equals(prefix);
        }

        @Override
        public int hashCode() {
            return prefix.hashCode();
        }

        @Override
        public String toString() {
            return prefix + " " + scenarios;
        }
    }

    private static final class Builder {

        private final List<StepWord> prefix;
        private final Map<StepWord, Builder> children = new LinkedHashMap<>();
        private final List<String> scenarios = new ArrayList<>();

        private Builder(List<StepWord> prefix) {
            this.prefix = prefix;
        }

        Builder child(StepWord step) {
            return children.computeIfAbsent(step, word -> {
                List<StepWord> longer = new ArrayList<>(prefix);
                longer.add(word);
                return new Builder(List.copyOf(longer));
            });
        }

        Node build() {
            Map<StepWord, Node> built = new LinkedHashMap<>();
            children.forEach((word, child) -> built.put(word, child.build()));
            return new Node(prefix, built, scenarios);
        }
    }
}
