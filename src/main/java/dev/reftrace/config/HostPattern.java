package dev.reftrace.config;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public record HostPattern(String pattern) {

    public HostPattern {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("host pattern must not be blank");
        }
        pattern = normalise(pattern);
    }

    public static HostPattern of(String pattern) {
        return new HostPattern(pattern);
    }

    public boolean matches(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return globMatches(pattern, normalise(host));
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "").replaceAll("\\.$", "");
    }

    private static boolean globMatches(String glob, String value) {
        String[] parts = glob.split("\\*", -1);
        if (parts.length == 1) {
            return glob.equals(value);
        }
        if (!value.startsWith(parts[0])) {
            return false;
        }
        int index = parts[0].length();
        for (int part = 1; part < parts.length - 1; part++) {
            int found = value.indexOf(parts[part], index);
            if (found < 0) {
                return false;
            }
            index = found + parts[part].length();
        }
        String tail = parts[parts.length - 1];
        return value.length() - index >= tail.length() && value.endsWith(tail);
    }
}
