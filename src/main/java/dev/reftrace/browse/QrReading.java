package dev.reftrace.browse;

public sealed interface QrReading {

    String locator();

    String description();

    String selector();

    record Decoded(String locator, String description, String text, String selector) implements QrReading {
    }

    record Unreadable(String locator, String description, String selector) implements QrReading {
    }
}
