package com.giftloop.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

/**
 * Test-only endpoint that echoes the base URI the app sees, to check proxy header
 * handling. Lives under {@code src/test}, so it never ships.
 */
@Path("/test-only/base-uri")
public class BaseUriTestResource {

    /**
     * The request's base URI, as used for generated links such as the bookmarklet.
     *
     * @param uriInfo request URI
     * @return base URI, e.g. {@code https://giftloop.fly.dev/}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String baseUri(@Context UriInfo uriInfo) {
        return uriInfo.getBaseUri().toString();
    }
}
