package dev.reftrace.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import java.util.List;

public record Route(@NotBlank String name, @NotEmpty List<@NotNull LinkMatch> match, boolean follow,
                   List<@NotNull ExpectEntry> expect) {

    public Route {
        match = match == null ? List.of() : List.copyOf(match);
        expect = expect == null ? List.of() : List.copyOf(expect);
        expect.forEach(ExpectEntry::toExpectation);
    }

    public boolean matches(URI url) {
        return match.stream().anyMatch(each -> each.matches(url));
    }

    private List<Expectation> expectations() {
        return expect.stream().map(ExpectEntry::toExpectation).toList();
    }

    public List<Expectation.OnLink> onLink() {
        return expectations().stream()
                .filter(Expectation.OnLink.class::isInstance)
                .map(Expectation.OnLink.class::cast)
                .toList();
    }

    public List<Expectation.OnArrival> onArrival() {
        return expectations().stream()
                .filter(Expectation.OnArrival.class::isInstance)
                .map(Expectation.OnArrival.class::cast)
                .toList();
    }
}
