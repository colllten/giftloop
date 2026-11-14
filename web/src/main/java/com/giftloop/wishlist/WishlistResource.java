package com.giftloop.wishlist;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.UUID;

/**
 * Read-only wishlist endpoints. Writes are HTML-only until JSON writes have a CSRF strategy (ADR 003).
 */
@Path("/api/wishlists")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@SecurityRequirement(name = "supabase")
public class WishlistResource {

    private final JsonWebToken jwt;
    private final WishlistService wishlists;

    /**
     * Creates the resource.
     *
     * @param jwt       verified token of the caller
     * @param wishlists wishlist access (RLS-scoped)
     */
    public WishlistResource(JsonWebToken jwt, WishlistService wishlists) {
        this.jwt = jwt;
        this.wishlists = wishlists;
    }

    /**
     * A wishlist with the gifts the caller can see.
     *
     * @param id wishlist id
     * @return the wishlist
     * @throws NotFoundException if missing or the caller is not a member of its group
     */
    @GET
    @Path("{id}")
    @Operation(summary = "Get a wishlist with the gifts the signed-in user can see")
    public WishlistDto get(@PathParam("id") UUID id) {
        return wishlists.find(jwt, id).map(WishlistDto::of).orElseThrow(NotFoundException::new);
    }
}
