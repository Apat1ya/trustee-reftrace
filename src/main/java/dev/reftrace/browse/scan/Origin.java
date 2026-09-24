package dev.reftrace.browse.scan;

import dev.reftrace.browse.FoundLink;

public sealed interface Origin {

    record Entered() implements Origin {
    }

    record Followed(FoundLink link) implements Origin {
    }
}
