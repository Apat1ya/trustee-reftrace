package dev.reftrace.crawl;

import dev.reftrace.judge.ReferralKey;

import java.util.random.RandomGenerator;

public record RunKeys(ReferralKey first, ReferralKey second) {

    private static final int LENGTH = 11;

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String ALPHABET = UPPER + LOWER + DIGITS;

    public RunKeys {
        if (first.equals(second)) {
            throw new IllegalArgumentException("the two keys of a run must differ: " + first);
        }
    }

    public static RunKeys draw(RandomGenerator random) {
        ReferralKey first = key(random);
        ReferralKey second = key(random);
        while (second.equals(first)) {
            second = key(random);
        }
        return new RunKeys(first, second);
    }

    private static ReferralKey key(RandomGenerator random) {
        char[] value = new char[LENGTH];
        value[0] = pick(UPPER, random);
        value[1] = pick(LOWER, random);
        value[2] = pick(DIGITS, random);
        for (int i = 3; i < LENGTH; i++) {
            value[i] = pick(ALPHABET, random);
        }
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = value[i];
            value[i] = value[j];
            value[j] = swap;
        }
        return ReferralKey.of(new String(value));
    }

    private static char pick(String characters, RandomGenerator random) {
        return characters.charAt(random.nextInt(characters.length()));
    }
}
