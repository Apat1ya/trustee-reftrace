package dev.reftrace.browse;

import java.net.URI;

public record FoundLink(URI url, String selector, String text, How how, boolean visible, boolean unwalked) {
}
