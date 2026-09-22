package dev.reftrace.browse;

public record Viewport(int width, int height) {

    public Viewport {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("viewport must be positive but was " + width + "x" + height);
        }
    }
}
