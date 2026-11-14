package com.giftloop.claim;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.List;

/**
 * Read-only claim endpoints. Writes are HTML-only until JSON writes have a CSRF strategy (ADR 003).
 */
@Path("/api/claims")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@SecurityRequirement(name = "supabase")
public class ClaimResource {

    private final JsonWebToken jwt;
    private final ClaimService claims;

    /**
     * Creates the resource.
     *
     * @param jwt    verified token of the caller
     * @param claims claim access (RLS-scoped)
     */
    public ClaimResource(JsonWebToken jwt, ClaimService claims) {
        this.jwt = jwt;
        this.claims = claims;
    }

    /**
     * Every claim the caller is on: upcoming events first (soonest first), then past ones.
     *
     * @return the caller's claims
     */
    @GET
    @Operation(summary = "List the gifts the signed-in user has claimed or co-buys")
    public List<MyClaimDto> list() {
        return claims.myClaims(jwt).stream()
                .flatMap(e -> e.claims().stream())
                .map(MyClaimDto::of)
                .toList();
    }
}
