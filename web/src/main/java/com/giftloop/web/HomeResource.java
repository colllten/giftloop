package com.giftloop.web;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Server-rendered pages (Qute) and HTMX fragments for the landing page.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class HomeResource {

    private static final Logger LOG = Logger.getLogger(HomeResource.class);

    /**
     * Type-safe templates under {@code templates/HomeResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Full landing page.
         *
         * @return the rendered page
         */
        static native TemplateInstance index();

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
    @Operation(hidden = true)
    public TemplateInstance index() {
        return Templates.index();
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
