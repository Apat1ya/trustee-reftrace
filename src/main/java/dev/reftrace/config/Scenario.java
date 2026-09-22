package dev.reftrace.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record Scenario(@NotBlank String name, @NotEmpty List<@NotNull StepWord> steps) {

    public Scenario {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
