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

import java.util.List;

/**
 * Home page: the user's groups when signed in, the product landing page when signed out.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class HomeResource {

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
    }

    /**
     * Renders the home page.
     *
     * @return home page template
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
}
