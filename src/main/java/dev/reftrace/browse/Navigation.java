package dev.reftrace.browse;

public sealed interface Navigation {

    record Landed(PageLoad load) implements Navigation {
    }

    record NotFollowed(Untested untested) implements Navigation {
    }
}
