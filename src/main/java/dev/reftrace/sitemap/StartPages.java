package dev.reftrace.sitemap;

import dev.reftrace.config.Routes;
import dev.reftrace.config.Start;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class StartPages {

    private final RestClient http;
    private final Routes routes;
    private final String keyParam;

    public StartPages(RestClient http, Routes routes, String keyParam) {
        this.http = http;
        this.routes = routes;
        this.keyParam = keyParam;
    }

    public Resolved resolve(List<Start> start) {
        Set<URI> pages = new LinkedHashSet<>();
        List<UnreadSitemap> unread = new ArrayList<>();
        for (Start entry : start) {
            Stream<URI> listed = switch (entry) {
                case Start.Page page -> Stream.of(page.url());
                case Start.Sitemap sitemap -> pagesOf(sitemap.url(), unread).stream();
            };
            listed.map(url -> UrlNormalizer.address(url, keyParam))
                    .flatMap(Optional::stream)
                    .filter(routes::followed)
                    .forEach(pages::add);
        }
        return new Resolved(List.copyOf(pages), unread);
    }

    private List<URI> pagesOf(URI sitemap, List<UnreadSitemap> unread) {
        List<URI> pages = new ArrayList<>();
        Set<URI> read = new HashSet<>();
        Deque<URI> pending = new ArrayDeque<>(List.of(sitemap));
        while (!pending.isEmpty()) {
            URI file = pending.removeFirst();
            if (!read.add(file)) {
                continue;
            }
            try {
                switch (fetch(file)) {
                    case SitemapIndex index -> pending.addAll(index.files());
                    case UrlSet urls -> pages.addAll(urls.pages());
                }
            } catch (SitemapFormatException unreadable) {
                unread.add(new UnreadSitemap(file,
                        Objects.requireNonNullElse(unreadable.getMessage(), file + " is not a sitemap")));
            }
        }
        return pages;
    }

    private Sitemap fetch(URI file) {
        ResponseEntity<byte[]> response;
        try {
            response = http.get().uri(file).retrieve()
                    .onStatus(HttpStatusCode::isError, (_, _) -> { })
                    .toEntity(byte[].class);
        } catch (RestClientException unreachable) {
            throw SitemapFormatException.withCause(file + " could not be read: " + unreachable.getMessage(),
                    unreachable);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw SitemapFormatException.of(file + " answered " + response.getStatusCode().value()
                    + " instead of a sitemap");
        }
        byte[] body = response.getBody();
        if (body == null) {
            throw SitemapFormatException.of(file + " answered without a body");
        }
        return SitemapParser.parse(file.toString(), body);
    }

    public record Resolved(List<URI> pages, List<UnreadSitemap> unread) {

        public Resolved {
            pages = List.copyOf(pages);
            unread = List.copyOf(unread);
        }
    }

    public record UnreadSitemap(URI sitemap, String reason) {
    }
}
