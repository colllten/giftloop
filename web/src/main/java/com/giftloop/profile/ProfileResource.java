package com.giftloop.profile;

import com.giftloop.security.RlsContext;
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

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
    private final RlsContext rls;

    /**
     * Creates the resource.
     *
     * @param jwt verified token of the caller
     * @param rls user-scoped DB access
     */
    public ProfileResource(JsonWebToken jwt, RlsContext rls) {
        this.jwt = jwt;
        this.rls = rls;
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
        ProfileDto profile = rls.inUserTransaction(jwt, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, username, display_name from \"Profile\" where id = auth.uid()");
                 ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new ProfileDto(rows.getObject("id", UUID.class), rows.getString("username"),
                                rows.getString("display_name"))
                        : null;
            }
        });
        if (profile == null) {
            LOG.warnf("No profile found for subject %s", jwt.getSubject());
            throw new NotFoundException("Profile not found");
        }
        return profile;
    }
}
