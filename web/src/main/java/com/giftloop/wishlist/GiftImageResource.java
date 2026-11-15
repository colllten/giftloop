package com.giftloop.wishlist;

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
 * Serves gift photos from private storage to people who can see the gift (Storage RLS
 * follows Gift RLS). File names are random per upload, so responses cache as immutable.
 */
@Path("/gift-images")
@Authenticated
public class GiftImageResource {

    private static final int CACHE_SECONDS = 31_536_000;

    private final JsonWebToken jwt;
    private final GiftImageService images;

    /**
     * Creates the resource.
     *
     * @param jwt    caller's verified token
     * @param images gift photo storage
     */
    public GiftImageResource(JsonWebToken jwt, GiftImageService images) {
        this.jwt = jwt;
        this.images = images;
    }

    /**
     * Returns a gift photo.
     *
     * @param owner wishlist owner id
     * @param gift  gift id
     * @param file  file name
     * @return JPEG, or 404 if missing or not permitted
     */
    @GET
    @Path("{owner}/{gift}/{file}")
    @Operation(hidden = true)
    public Response image(@PathParam("owner") UUID owner, @PathParam("gift") UUID gift,
                          @PathParam("file") String file) {
        CacheControl cache = new CacheControl();
        cache.setPrivate(true);
        cache.setMaxAge(CACHE_SECONDS);
        return images.load(jwt, owner, gift, file)
                .map(bytes -> Response.ok(bytes, "image/jpeg").cacheControl(cache)
                        .header("X-Content-Type-Options", "nosniff").build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }
}
