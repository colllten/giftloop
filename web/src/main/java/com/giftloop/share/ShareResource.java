package com.giftloop.share;

import com.giftloop.security.RequiresOnboarding;
import com.giftloop.wishlist.WishlistService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * "Add to GiftLoop" entry point for the web app share target (Android/desktop PWA) and the
 * bookmarklet. Finds the shared link, then opens the caller's wishlist with the add form
 * auto-filled ({@code ?add=}), asking which wishlist when there is more than one open.
 * Signed-out visitors go through sign-in and come back here (the query is kept in {@code next}).
 */
@Path("/add")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class ShareResource {

    private static final Logger LOG = Logger.getLogger(ShareResource.class);

    private final JsonWebToken jwt;
    private final WishlistService wishlists;

    /**
     * Creates the resource.
     *
     * @param jwt       caller's verified token
     * @param wishlists wishlist access
     */
    public ShareResource(JsonWebToken jwt, WishlistService wishlists) {
        this.jwt = jwt;
        this.wishlists = wishlists;
    }

    /**
     * Templates under {@code templates/ShareResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Wishlist picker for a shared link (also covers "no open wishlists").
         *
         * @param link  shared link
         * @param lists open wishlists
         * @return page
         */
        static native TemplateInstance pick(String link, List<Choice> lists);

        /**
         * How to add gifts from other sites (bookmarklet, install).
         *
         * @param bookmarklet bookmarklet {@code javascript:} URL for this site
         * @param noLink      a share arrived but had no web address in it
         * @return page
         */
        static native TemplateInstance help(String bookmarklet, boolean noLink);
    }

    /**
     * A wishlist option on the picker.
     *
     * @param wishlist the wishlist
     * @param href     where choosing it goes
     */
    public record Choice(WishlistService.OpenWishlist wishlist, String href) {
    }

    /**
     * Handles a shared page (or shows how to share when nothing was sent).
     *
     * @param url     shared URL
     * @param title   shared page title
     * @param text    shared text (Android puts the link here)
     * @param uriInfo request URI (for the bookmarklet's host)
     * @return redirect to the wishlist, the picker, or the help page
     */
    @GET
    @Operation(hidden = true)
    public Response add(@QueryParam("url") String url, @QueryParam("title") String title,
                        @QueryParam("text") String text, @Context UriInfo uriInfo) {
        boolean shared = url != null || title != null || text != null;
        String link = SharedLink.extract(url, text, title);
        if (link == null) {
            if (shared) {
                LOG.infof("User %s shared something without a link", jwt.getSubject());
            }
            return Response.ok(Templates.help(bookmarklet(uriInfo.getBaseUri()), shared)).build();
        }
        List<Choice> choices = wishlists.openWishlists(jwt).stream()
                .map(w -> new Choice(w, target(w.id(), link))).toList();
        if (choices.size() == 1) {
            return Response.seeOther(URI.create(choices.get(0).href())).build();
        }
        return Response.ok(Templates.pick(link, choices)).build();
    }

    /**
     * Wishlist page with the add form auto-filled from the link, scrolled to the form.
     *
     * @param wishlistId wishlist id
     * @param link       shared link
     * @return relative URL
     */
    static String target(UUID wishlistId, String link) {
        return "/wishlists/" + wishlistId + "?add=" + URLEncoder.encode(link, StandardCharsets.UTF_8) + "#add-gift";
    }

    /**
     * Bookmarklet that sends the current page to {@code /add} on this site.
     *
     * @param base site base URI, ending in {@code /}
     * @return {@code javascript:} URL
     */
    static String bookmarklet(URI base) {
        return "javascript:(()=>{location.href='" + base + "add?url='+encodeURIComponent(location.href)"
                + "+'&title='+encodeURIComponent(document.title)})()";
    }
}
