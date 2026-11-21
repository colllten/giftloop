package com.giftloop.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Friendly pages for "not found" (404) and "not allowed" (403) in browsers.
 * <p>
 * Two entry points: {@code UnhandledErrorMapper} passes JAX-RS client errors here, and a
 * last-resort Vert.x route renders the 404 page for URLs no endpoint or static file
 * matched (Quarkus REST hands those back to Vert.x, which would answer with an empty body).
 * Only empty 403/404 responses to HTML requests are replaced; API paths, assets and
 * responses that already carry a body pass through unchanged.
 */
@ApplicationScoped
public class ErrorPages {

    private static final Logger LOG = Logger.getLogger(ErrorPages.class);
    /** Statuses with a friendly page. */
    static final Set<Integer> HANDLED = Set.of(403, 404);
    /** Path prefixes that keep the bare status (machine clients, files, management). */
    private static final List<String> RAW_PREFIXES = List.of("/api/", "/assets/", "/_static/", "/q/");

    /**
     * Templates under {@code templates/ErrorPages/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Full error page.
         *
         * @param status 403 or 404
         * @return page
         */
        static native TemplateInstance page(int status);

        /**
         * Error fragment swapped into {@code main} for HTMX requests.
         *
         * @param status 403 or 404
         * @return fragment
         */
        static native TemplateInstance fragment(int status);
    }

    /**
     * Replaces an empty 403/404 for a browser with the friendly page; anything else is
     * returned unchanged.
     *
     * @param original the client-error response
     * @param path     raw request path
     * @param headers  request headers
     * @return the response to send
     */
    public Response forClientError(Response original, String path, HttpHeaders headers) {
        int status = original.getStatus();
        if (!HANDLED.contains(status) || original.hasEntity()) {
            return original;
        }
        boolean htmx = Redirects.isHtmx(headers);
        if (!htmx && !wantsHtml(path, headers.getHeaderString(HttpHeaders.ACCEPT))) {
            return original;
        }
        LOG.debugf("Friendly %d page for %s", status, path);
        if (htmx) {
            return Response.status(status).type(MediaType.TEXT_HTML)
                    .header("HX-Retarget", "main")
                    .header("HX-Reswap", "innerHTML")
                    .entity(Templates.fragment(status).render()).build();
        }
        return Response.status(status).type(MediaType.TEXT_HTML)
                .entity(Templates.page(status).render()).build();
    }

    /**
     * Registers the 404 fallback after every other route.
     *
     * @param router the HTTP router
     */
    void register(@Observes Router router) {
        router.route().order(Integer.MAX_VALUE).handler(this::fallback);
    }

    /**
     * Renders the 404 page for unmatched browser requests; otherwise lets Vert.x answer.
     *
     * @param rc routing context
     */
    void fallback(RoutingContext rc) {
        if (rc.response().headWritten()
                || !wantsHtml(rc.normalizedPath(), rc.request().getHeader(HttpHeaders.ACCEPT))) {
            rc.next();
            return;
        }
        LOG.debugf("No route for %s, friendly 404", rc.normalizedPath());
        rc.response().setStatusCode(404)
                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML + ";charset=UTF-8")
                .end(Templates.page(404).render());
    }

    /**
     * Whether a request is a browser page load: an HTML Accept header on a non-raw path.
     *
     * @param path   request path
     * @param accept Accept header, may be null
     * @return true when a friendly page should be shown
     */
    static boolean wantsHtml(String path, String accept) {
        if (accept == null || !accept.contains(MediaType.TEXT_HTML)) {
            return false;
        }
        return RAW_PREFIXES.stream().noneMatch(path::startsWith);
    }
}
