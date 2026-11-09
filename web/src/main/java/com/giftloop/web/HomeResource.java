package com.giftloop.web;

import com.giftloop.profile.Profile;
import com.giftloop.profile.ProfileService;
import com.giftloop.security.RequiresOnboarding;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Landing page (signed-in greeting or sign-in prompt) and HTMX demo fragment.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class HomeResource {

    private static final Logger LOG = Logger.getLogger(HomeResource.class);

    private final SecurityIdentity identity;
    private final JsonWebToken jwt;
    private final ProfileService profiles;

    /**
     * Creates the resource.
     *
     * @param identity current identity (resolved lazily)
     * @param jwt      caller's token when signed in
     * @param profiles profile access
     */
    public HomeResource(SecurityIdentity identity, JsonWebToken jwt, ProfileService profiles) {
        this.identity = identity;
        this.jwt = jwt;
        this.profiles = profiles;
    }

    /**
     * Type-safe templates under {@code templates/HomeResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Full landing page.
         *
         * @param profile signed-in user's profile, or null when signed out
         * @return the rendered page
         */
        static native TemplateInstance index(Profile profile);

        /**
         * HTMX fragment confirming the server responded.
         *
         * @param time server time, ISO-8601 UTC
         * @return the rendered fragment
         */
        static native TemplateInstance ping(String time);
    }

    /**
     * Renders the landing page.
     *
     * @return landing page template
     */
    @GET
    @RequiresOnboarding
    @Operation(hidden = true)
    public TemplateInstance index() {
        Profile profile = identity.isAnonymous() ? null : profiles.findOwn(jwt).orElse(null);
        return Templates.index(profile);
    }

    /**
     * Returns a small HTML fragment for HTMX to swap in.
     *
     * @return ping fragment with the current server time
     */
    @GET
    @Path("fragments/ping")
    @Operation(hidden = true)
    public TemplateInstance ping() {
        String now = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        LOG.debugf("Ping fragment requested at %s", now);
        return Templates.ping(now);
    }
}
