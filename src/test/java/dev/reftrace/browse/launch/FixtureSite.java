package dev.reftrace.browse.launch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.reftrace.browse.UrlPatterns;
import dev.reftrace.config.Route;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import dev.reftrace.testsupport.QrCodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

final class FixtureSite implements AutoCloseable {

    static final String EXIT_SUFFIX = "/install";

    static final String STORE_HOST = "store.appexit.invalid";

    private static final String ANALYTICS_HOST = "beacon.tracker.localhost";

    static final String QR_SHOWN = "https://trusteeplus.app.link/qrShownKey";
    static final String QR_HIDDEN = "https://trusteeplus.app.link/qrHiddenKey";
    static final String QR_COVERED = "https://trusteeplus.app.link/qrCoveredKey";

    private final HttpServer site;
    private final HttpServer counter;
    private final List<String> counted = new CopyOnWriteArrayList<>();

    private FixtureSite(HttpServer site, HttpServer counter) {
        this.site = site;
        this.counter = counter;
    }

    static FixtureSite start() {
        try {
            HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            HttpServer counter = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FixtureSite fixture = new FixtureSite(site, counter);
            site.setExecutor(Executors.newCachedThreadPool());
            counter.setExecutor(Executors.newCachedThreadPool());
            site.createContext("/", fixture::serve);
            counter.createContext("/", fixture::count);
            site.start();
            counter.start();
            return fixture;
        } catch (IOException e) {
            throw new UncheckedIOException("the fixture site could not be started", e);
        }
    }

    String url(String path) {
        return "http://127.0.0.1:" + site.getAddress().getPort() + path;
    }

    String unreachableUrl() {
        return "http://127.0.0.1:" + (counter.getAddress().getPort() + 20000) + "/nowhere";
    }

    String exitBase() {
        return "http://127.0.0.1:" + counter.getAddress().getPort() + "/go";
    }

    List<String> counted() {
        return List.copyOf(counted);
    }

    UrlPatterns patterns() {
        return new UrlPatterns(List.of(EXIT_SUFFIX, ".apk"), List.of("*.tracker.localhost"), routes());
    }

    Routes routes() {
        return new Routes(List.of(
                route("127.0.0.1", true),
                route("127.0.0.1/go/**", false),
                route("*." + STORE_HOST.substring(STORE_HOST.indexOf('.') + 1), false),
                route("*.app.link", false)));
    }

    private static Route route(String match, boolean follow) {
        return new Route(match, List.of(Fixtures.match(match)), follow, List.of());
    }

    @Override
    public void close() {
        site.stop(0);
        counter.stop(0);
    }

    private void count(HttpExchange exchange) throws IOException {
        counted.add(exchange.getRequestURI().toString());
        byte[] body = "counted".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/boom")) {
            send(exchange, 500, "text/html; charset=utf-8", "<html><body>sorry"
                    + "<a href=\"https://" + STORE_HOST + "/app\">open the store</a>"
                    + "<script>history.replaceState(null, '', location.href);</script></body></html>");
            return;
        }
        if (path.equals("/redirect")) {
            String query = exchange.getRequestURI().getRawQuery();
            exchange.getResponseHeaders().add("Location", "/plain" + (query == null ? "" : "?" + query));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (path.equals("/hang")) {
            return;
        }
        String name = path.equals("/") ? "home" : path.substring(1);
        String page = read(name);
        send(exchange, 200, "text/html; charset=utf-8", page
                .replace("EXIT_BASE", exitBase())
                .replace("STORE_HOST", STORE_HOST)
                .replace("ANALYTICS_HOST", ANALYTICS_HOST + ":" + counter.getAddress().getPort())
                .replace("QR_SHOWN", QrCodes.svg(QR_SHOWN, 200))
                .replace("QR_HIDDEN", QrCodes.svg(QR_HIDDEN, 200))
                .replace("QR_COVERED", QrCodes.svg(QR_COVERED, 200)));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String read(String name) {
        String resource = "/playwright-fixtures/" + name + ".html";
        try (InputStream stream = FixtureSite.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture page " + name + " is missing from the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("fixture page " + name + " could not be read", e);
        }
    }
}
