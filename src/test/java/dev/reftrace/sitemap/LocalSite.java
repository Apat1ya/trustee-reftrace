package dev.reftrace.sitemap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LocalSite implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Route> routes = new HashMap<>();
    private final List<String> requests = new ArrayList<>();

    public LocalSite() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException unusable) {
            throw new UncheckedIOException(unusable);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    public URI url(String path) {
        return baseUrl().resolve(path);
    }

    public LocalSite serve(String path, String contentType, String body) {
        routes.put(path, new Route(200, contentType, body, null));
        return this;
    }

    public LocalSite redirect(String path, int status, String target) {
        routes.put(path, new Route(status, null, "", target));
        return this;
    }

    public LocalSite answerStatus(String path, int status) {
        routes.put(path, new Route(status, "text/plain", "", null));
        return this;
    }

    public List<String> requestLog() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        synchronized (requests) {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        }
        Route route = routes.get(path);
        if (route == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        if (route.location() != null) {
            exchange.getResponseHeaders().add("Location", route.location());
            exchange.sendResponseHeaders(route.status(), -1);
            exchange.close();
            return;
        }
        if (route.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", route.contentType());
        }
        byte[] body = route.body().getBytes(StandardCharsets.UTF_8);
        boolean headOnly = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(route.status(), headOnly ? -1 : body.length);
        if (!headOnly && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private record Route(int status, @Nullable String contentType, String body, @Nullable String location) {
    }
}
