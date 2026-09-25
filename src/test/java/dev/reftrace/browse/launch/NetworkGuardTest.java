package dev.reftrace.browse.launch;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import dev.reftrace.browse.UrlPatterns;
import dev.reftrace.browse.page.BrowserTimeouts;
import dev.reftrace.config.Clicks;
import dev.reftrace.config.Routes;
import dev.reftrace.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkGuardTest {

    private static final BrowserSettings SETTINGS = new BrowserSettings(true, new UrlPatterns(
            List.of(".apk"),
            List.of("connect.facebook.net"),
            new Routes(List.of(
                    new dev.reftrace.config.Route("trustee.io", List.of(Fixtures.match("trustee.io")), true,
                            List.of()),
                    new dev.reftrace.config.Route("*.app.link", List.of(Fixtures.match("*.app.link")), false,
                            List.of())))),
            new BrowserTimeouts(Duration.ofSeconds(15), Duration.ofSeconds(1), Duration.ofSeconds(8),
                    Duration.ofSeconds(5), Duration.ofSeconds(3)),
            BrowserFixture.REVEAL, Clicks.ALL, List.of(), true);

    private Predicate<String> routed;
    private Consumer<Route> handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void install() {
        BrowserContext context = mock(BrowserContext.class);
        NetworkGuard.install(context, SETTINGS);
        ArgumentCaptor<Predicate<String>> predicate = ArgumentCaptor.forClass(Predicate.class);
        ArgumentCaptor<Consumer<Route>> consumer = ArgumentCaptor.forClass(Consumer.class);
        verify(context).route(predicate.capture(), consumer.capture());
        routed = predicate.getValue();
        handler = consumer.getValue();
    }

    @Test
    void routesEveryRequestThroughTheGuard() {
        assertThat(List.of("https://trustee.io/cards/", "https://trusteeplus.app.link/K1",
                "https://unlisted.test/", "not a url at all")).allMatch(routed);
    }

    @Test
    void sendsOnlyWhatThePolicyLetsPassAndDecidesOnce() {
        Request request = document("https://trustee.io/cards/");
        Route route = routeOf(request);

        handler.accept(route);

        verify(route).resume();
        verify(route, never()).fulfill(any());
        verify(route, never()).abort(anyString());
        verify(request, times(1)).url();
    }

    @Test
    void answersAnExitLocally() {
        Route route = routeOf(document("https://trusteeplus.app.link/K1"));

        handler.accept(route);

        ArgumentCaptor<Route.FulfillOptions> answer = ArgumentCaptor.forClass(Route.FulfillOptions.class);
        verify(route).fulfill(answer.capture());
        assertThat(answer.getValue().status).isEqualTo(204);
        verify(route, never()).resume();
    }

    @Test
    void abortsTracking() {
        Route route = routeOf(document("https://connect.facebook.net/en_US/fbevents.js"));

        handler.accept(route);

        verify(route).abort("blockedbyclient");
        verify(route, never()).resume();
    }

    @Test
    void abortsARequestItCannotDecideOn() {
        Request request = mock(Request.class);
        when(request.url()).thenThrow(new IllegalStateException("request is gone"));
        Route route = routeOf(request);

        handler.accept(route);

        verify(route).abort("blockedbyclient");
        verify(route, never()).resume();
        verify(route, never()).fulfill(any());
    }

    private static Request document(String url) {
        Request request = mock(Request.class);
        Frame frame = mock(Frame.class);
        when(request.url()).thenReturn(url);
        when(request.isNavigationRequest()).thenReturn(true);
        when(request.frame()).thenReturn(frame);
        when(request.resourceType()).thenReturn("document");
        return request;
    }

    private static Route routeOf(Request request) {
        Route route = mock(Route.class);
        when(route.request()).thenReturn(request);
        return route;
    }
}
