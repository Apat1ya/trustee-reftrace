package dev.reftrace.judge;

import java.util.regex.Pattern;

public record ReferralKey(String value) {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9]{4,64}");

    public ReferralKey {
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("referral key must match [A-Za-z0-9]{4,64} but was: " + value);
        }
    }

    public static ReferralKey of(String value) {
        return new ReferralKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
