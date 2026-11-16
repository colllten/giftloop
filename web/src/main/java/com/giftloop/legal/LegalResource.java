package com.giftloop.legal;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Public legal pages: Terms of Service, Privacy Policy and cookie notice. Keep them in
 * step with the code (see {@code docs/LEGAL.md}).
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class LegalResource {

    private final LegalService legal;

    /**
     * Creates the resource.
     *
     * @param legal document version and contact
     */
    public LegalResource(LegalService legal) {
        this.legal = legal;
    }

    /**
     * Templates under {@code templates/LegalResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Terms of Service.
         *
         * @param legal version and contact
         * @return page
         */
        static native TemplateInstance terms(LegalInfo legal);

        /**
         * Privacy Policy.
         *
         * @param legal version and contact
         * @return page
         */
        static native TemplateInstance privacy(LegalInfo legal);

        /**
         * Cookie notice.
         *
         * @param legal version and contact
         * @return page
         */
        static native TemplateInstance cookies(LegalInfo legal);
    }

    /**
     * Terms of Service page.
     *
     * @return page
     */
    @GET
    @Path("terms")
    @Operation(hidden = true)
    public TemplateInstance terms() {
        return Templates.terms(legal.info());
    }

    /**
     * Privacy Policy page.
     *
     * @return page
     */
    @GET
    @Path("privacy")
    @Operation(hidden = true)
    public TemplateInstance privacy() {
        return Templates.privacy(legal.info());
    }

    /**
     * Cookie notice page.
     *
     * @return page
     */
    @GET
    @Path("cookies")
    @Operation(hidden = true)
    public TemplateInstance cookies() {
        return Templates.cookies(legal.info());
    }
}
