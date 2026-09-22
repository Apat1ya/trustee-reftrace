package dev.reftrace.config;

import java.util.Arrays;

public enum StepWord {
    ENTER("enter"),
    CLICK("click"),
    CLICK_ANY("click*"),
    RELOAD("reload"),
    ENTER_NEW_KEY("enter-new-key");

    private final String word;

    StepWord(String word) {
        this.word = word;
    }

    public String word() {
        return word;
    }

    public static StepWord of(String word) {
        return Arrays.stream(values()).filter(each -> each.word.equals(word)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown step: " + word));
    }

    @Override
    public String toString() {
        return word;
    }
}
