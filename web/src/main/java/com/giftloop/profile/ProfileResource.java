package com.giftloop.profile;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.jboss.logging.Logger;


/**
 * Endpoints for the signed-in user's profile.
 */
@Path("/api/me")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@SecurityScheme(securitySchemeName = "supabase", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "supabase")
public class ProfileResource {

    private static final Logger LOG = Logger.getLogger(ProfileResource.class);

    private final JsonWebToken jwt;
    private final ProfileService profiles;

    /**
     * Creates the resource.
     *
     * @param jwt      verified token of the caller
     * @param profiles profile access (RLS-scoped)
     */
    public ProfileResource(JsonWebToken jwt, ProfileService profiles) {
        this.jwt = jwt;
        this.profiles = profiles;
    }

    /**
     * Returns the caller's own profile, read through RLS.
     *
     * @return the profile
     * @throws NotFoundException if the caller has no profile row
     */
    @GET
    @Operation(summary = "Get the signed-in user's profile")
    public ProfileDto me() {
        return profiles.findOwn(jwt).map(ProfileDto::of).orElseThrow(() -> {
            LOG.warnf("No profile found for subject %s", jwt.getSubject());
            return new NotFoundException("Profile not found");
        });
    }
}
