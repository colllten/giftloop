package com.giftloop.observability;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only endpoints that fail with an unexpected exception, to exercise
 * {@link UnhandledErrorMapper}. Lives under {@code src/test}, so it never ships.
 */
@Path("/")
public class ThrowingTestResource {

    /** Message carried by the thrown exception (must never reach the response). */
    static final String SECRET = "internal detail 3f9a";

    /**
     * A page that fails.
     *
     * @return never returns
     */
    @GET
    @Path("test-only/boom")
    @Produces(MediaType.TEXT_HTML)
    public String page() {
        throw new IllegalStateException(SECRET);
    }

    /**
     * An API endpoint that fails.
     *
     * @return never returns
     */
    @GET
    @Path("api/test-only/boom")
    @Produces(MediaType.APPLICATION_JSON)
    public String api() {
        throw new IllegalStateException(SECRET);
    }
}
