package com.giftloop.observability;

import com.giftloop.web.ErrorPages;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.security.Principal;
import java.util.Map;

/**
 * Last-resort handler for exceptions no resource or more specific mapper handled
 * (ADR 006). Each one gets a reference ID that is logged with the stack trace, counted,
 * and shown to the user so a report can be matched to the log.
 * <p>
 * Client errors ({@link WebApplicationException} below 500) go to {@link ErrorPages},
 * which shows a friendly page for empty 403/404s in browsers and passes the rest through;
 * {@code AuthRedirectMapper} still wins for auth failures because it maps more specific types.
 */
public class UnhandledErrorMapper {

    private static final Logger LOG = Logger.getLogger(UnhandledErrorMapper.class);
    /** MDC key; appears as a field in JSON logs. */
    static final String MDC_ERROR_ID = "errorId";

    private final AppMetrics metrics;
    private final ErrorPages errorPages;

    /**
     * Creates the mapper.
     *
     * @param metrics    app metrics
     * @param errorPages friendly 403/404 pages
     */
    public UnhandledErrorMapper(AppMetrics metrics, ErrorPages errorPages) {
        this.metrics = metrics;
        this.errorPages = errorPages;
    }

    /**
     * Templates under {@code templates/UnhandledErrorMapper/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Full error page.
         *
         * @param errorId reference ID
         * @return page
         */
        static native TemplateInstance page(String errorId);

        /**
         * Error fragment swapped into {@code main} for HTMX requests.
         *
         * @param errorId reference ID
         * @return fragment
         */
        static native TemplateInstance fragment(String errorId);
    }

    /**
     * Maps any otherwise-unhandled exception.
     *
     * @param e       the failure
     * @param request current request
     * @param headers request headers
     * @param routing Vert.x context (for the already-authenticated user, if any)
     * @return a client-error response (friendly page or original), or a 500 with a reference ID
     */
    @ServerExceptionMapper
    public Response unhandled(Exception e, ContainerRequestContext request, HttpHeaders headers,
                              RoutingContext routing) {
        String path = request.getUriInfo().getRequestUri().getRawPath();
        if (e instanceof WebApplicationException wae && wae.getResponse().getStatus() < 500) {
            return errorPages.forClientError(wae.getResponse(), path, headers);
        }
        String errorId = ErrorIds.next();
        MDC.put(MDC_ERROR_ID, errorId);
        try {
            LOG.errorf(e, "Unhandled error errorId=%s %s %s user=%s",
                    errorId, request.getMethod(), path, userId(routing));
        } finally {
            MDC.remove(MDC_ERROR_ID);
        }
        metrics.unhandledError(e.getClass().getSimpleName());
        return response(errorId, path, headers);
    }

    private static Response response(String errorId, String path, HttpHeaders headers) {
        if (path.startsWith("/api/")) {
            return Response.serverError().type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "internal_error", "errorId", errorId)).build();
        }
        if (Redirects.isHtmx(headers)) {
            return Response.serverError().type(MediaType.TEXT_HTML)
                    .header("HX-Retarget", "main")
                    .header("HX-Reswap", "innerHTML")
                    .entity(Templates.fragment(errorId).render()).build();
        }
        return Response.serverError().type(MediaType.TEXT_HTML)
                .entity(Templates.page(errorId).render()).build();
    }

    /**
     * The caller's account ID, only if authentication already ran: looking it up here
     * must not trigger (lazy) authentication from inside an error handler.
     */
    private static String userId(RoutingContext routing) {
        if (routing != null && routing.user() instanceof QuarkusHttpUser user) {
            Principal principal = user.getSecurityIdentity().getPrincipal();
            return principal instanceof JsonWebToken jwt ? jwt.getSubject() : principal.getName();
        }
        return "-";
    }
}
