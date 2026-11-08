package com.giftloop.profile;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.util.UUID;

/**
 * Serves avatars from private storage to people allowed to see them
 * (the owner and co-members). URLs are versioned, so responses cache for a day.
 */
@Path("/avatars")
@Authenticated
public class AvatarResource {

    private static final int CACHE_SECONDS = 86_400;

    private final JsonWebToken jwt;
    private final AvatarService avatars;

    /**
     * Creates the resource.
     *
     * @param jwt     caller's verified token
     * @param avatars avatar storage
     */
    public AvatarResource(JsonWebToken jwt, AvatarService avatars) {
        this.jwt = jwt;
        this.avatars = avatars;
    }

    /**
     * Returns a user's avatar.
     *
     * @param userId whose avatar
     * @return JPEG, or 404 if missing or not permitted
     */
    @GET
    @Path("{userId}")
    @Operation(hidden = true)
    public Response avatar(@PathParam("userId") UUID userId) {
        CacheControl cache = new CacheControl();
        cache.setPrivate(true);
        cache.setMaxAge(CACHE_SECONDS);
        return avatars.load(jwt, userId)
                .map(bytes -> Response.ok(bytes, "image/jpeg").cacheControl(cache).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }
}
