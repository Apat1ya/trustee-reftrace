package dev.reftrace.browse.launch;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import dev.reftrace.browse.page.GuardObservations;
import dev.reftrace.browse.page.PlaywrightErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NetworkGuard {

    private static final Logger log = LoggerFactory.getLogger(NetworkGuard.class);

    private final RequestPolicy policy;
    private final GuardObservations observations = new GuardObservations();

    private NetworkGuard(RequestPolicy policy) {
        this.policy = policy;
    }

    public static NetworkGuard install(BrowserContext context, BrowserSettings settings) {
        NetworkGuard guard = new NetworkGuard(new RequestPolicy(settings.urls()));
        context.route(_ -> true, guard::handle);
        return guard;
    }

    GuardObservations observations() {
        return observations;
    }

    private void handle(Route route) {
        Request request;
        String url;
        RequestPolicy.Outcome outcome;
        try {
            request = route.request();
            url = request.url();
            outcome = policy.decide(url);
        } catch (RuntimeException e) {
            log.warn("aborted a request the guard could not decide on: {}", PlaywrightErrors.message(e));
            route.abort("blockedbyclient");
            return;
        }
        switch (outcome) {
            case RequestPolicy.Fulfill204 _ -> {
                if (isDocument(request)) {
                    observations.hit(url);
                }
                log.debug("stopped {} request at the browser edge: {}", request.resourceType(), url);
                route.fulfill(new Route.FulfillOptions().setStatus(204));
            }
            case RequestPolicy.AbortAnalytics _ -> route.abort("blockedbyclient");
            case RequestPolicy.Pass _ -> route.resume();
        }
    }

    private static boolean isDocument(Request request) {
        if (!request.isNavigationRequest()) {
            return false;
        }
        try {
            return request.frame().parentFrame() == null;
        } catch (RuntimeException _) {
            return true;
        }
    }
}
