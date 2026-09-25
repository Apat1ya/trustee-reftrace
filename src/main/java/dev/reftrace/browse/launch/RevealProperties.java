package dev.reftrace.browse.launch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("reftrace.reveal")
@Validated
public record RevealProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("5s") @NotNull Duration budget,
                               @DefaultValue("24") @Min(1) int maxActions,
                               @DefaultValue List<@Valid Revealer> revealers,
                               @DefaultValue List<@Valid Revealer> menus) {

    public RevealProperties {
        revealers = List.copyOf(revealers);
        menus = List.copyOf(menus);
    }

    public record Revealer(@NotBlank String name, @NotBlank String selector, @NotNull RevealAction action) {
    }

    public enum RevealAction {
        HOVER,
        CLICK
    }
}
