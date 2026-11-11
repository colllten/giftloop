package com.giftloop.web;

import com.giftloop.group.GroupService;
import com.giftloop.group.GroupSummary;
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
import java.util.List;

/**
 * Landing page (signed-in: the user's groups; signed-out: sign-in prompt) and HTMX demo fragment.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class HomeResource {

    private static final Logger LOG = Logger.getLogger(HomeResource.class);

    private final SecurityIdentity identity;
    private final JsonWebToken jwt;
    private final ProfileService profiles;
    private final GroupService groups;

    /**
     * Creates the resource.
     *
     * @param identity current identity (resolved lazily)
     * @param jwt      caller's token when signed in
     * @param profiles profile access
     * @param groups   group access
     */
    public HomeResource(SecurityIdentity identity, JsonWebToken jwt, ProfileService profiles, GroupService groups) {
        this.identity = identity;
        this.jwt = jwt;
        this.profiles = profiles;
        this.groups = groups;
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
         * @param groups  signed-in user's groups (empty when signed out)
         * @return the rendered page
         */
        static native TemplateInstance index(Profile profile, List<GroupSummary> groups);

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
        if (identity.isAnonymous()) {
            return Templates.index(null, List.of());
        }
        return Templates.index(profiles.findOwn(jwt).orElse(null), groups.listMine(jwt));
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
