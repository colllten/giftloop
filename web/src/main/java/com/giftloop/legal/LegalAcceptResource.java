package com.giftloop.legal;

import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

/**
 * Asks signed-in users to accept the current Terms and Privacy Policy. Users land here
 * from {@code OnboardingGate} when they never accepted or the documents changed.
 * Deliberately not {@code @RequiresOnboarding}, which would loop.
 */
@Path("/legal/accept")
@Produces(MediaType.TEXT_HTML)
@Authenticated
public class LegalAcceptResource {

    private static final Logger LOG = Logger.getLogger(LegalAcceptResource.class);
    /** Shown when the checkbox is left unticked. */
    public static final String MUST_ACCEPT = "Please tick the box to agree before continuing.";

    private final JsonWebToken jwt;
    private final LegalService legal;

    /**
     * Creates the resource.
     *
     * @param jwt   caller's verified token
     * @param legal acceptance storage
     */
    public LegalAcceptResource(JsonWebToken jwt, LegalService legal) {
        this.jwt = jwt;
        this.legal = legal;
    }

    /**
     * Templates under {@code templates/LegalAcceptResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Acceptance page.
         *
         * @param legal version and contact
         * @param next  where to go afterwards
         * @return page
         */
        static native TemplateInstance accept(LegalInfo legal, String next);

        /**
         * Acceptance form fragment.
         *
         * @param next  where to go afterwards
         * @param error message for the checkbox, may be null
         * @return fragment
         */
        static native TemplateInstance acceptForm(String next, String error);
    }

    /**
     * Acceptance page; users who already accepted go straight on to {@code next}.
     *
     * @param next    where to go afterwards
     * @param headers request headers
     * @return page or redirect
     */
    @GET
    @Operation(hidden = true)
    public Response page(@QueryParam("next") String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        if (legal.hasAcceptedCurrent(jwt)) {
            return Redirects.to(headers, safeNext).build();
        }
        return Response.ok(Templates.accept(legal.info(), safeNext)).build();
    }

    /**
     * Records acceptance and continues to {@code next}.
     *
     * @param acceptTerms whether the checkbox was ticked
     * @param next        where to go afterwards
     * @param headers     request headers
     * @return redirect, or the form with an error
     */
    @POST
    @Operation(hidden = true)
    public Response accept(@RestForm boolean acceptTerms, @RestForm String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        if (!acceptTerms) {
            LOG.debugf("Legal acceptance submitted without the checkbox by %s", jwt.getSubject());
            return Response.ok(Templates.acceptForm(safeNext, MUST_ACCEPT)).build();
        }
        legal.recordAcceptance(jwt);
        return Redirects.to(headers, safeNext).build();
    }
}
