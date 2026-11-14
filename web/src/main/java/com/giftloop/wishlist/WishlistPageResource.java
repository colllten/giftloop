package com.giftloop.wishlist;

import com.giftloop.security.RequiresOnboarding;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wishlist pages: start a wishlist for an event, view one, and (owner) add, edit and remove gifts
 * with per-gift visibility.
 *
 * <p>Gift forms swap themselves on validation errors. On success the response re-targets the
 * whole {@code #wishlist-body} via {@code HX-Retarget}/{@code HX-Reswap}.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class WishlistPageResource {

    /** HTMX response header that changes the swap target. */
    static final String HX_RETARGET = "HX-Retarget";
    /** HTMX response header that changes the swap style. */
    static final String HX_RESWAP = "HX-Reswap";
    private static final String BODY_TARGET = "#wishlist-body";

    private final JsonWebToken jwt;
    private final WishlistService wishlists;

    /**
     * Creates the resource.
     *
     * @param jwt       caller's verified token
     * @param wishlists wishlist access
     */
    public WishlistPageResource(JsonWebToken jwt, WishlistService wishlists) {
        this.jwt = jwt;
        this.wishlists = wishlists;
    }

    /**
     * Templates under {@code templates/WishlistPageResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Wishlist page.
         *
         * @param view body data
         * @return page
         */
        static native TemplateInstance wishlist(WishlistBodyView view);

        /**
         * Wishlist page body fragment.
         *
         * @param view body data
         * @return fragment
         */
        static native TemplateInstance wishlistBody(WishlistBodyView view);

        /**
         * Add/edit gift form fragment.
         *
         * @param view form data
         * @return fragment
         */
        static native TemplateInstance giftForm(GiftFormView view);
    }

    /**
     * Starts the caller's wishlist for an event (or opens the existing one).
     *
     * @param eventId event id
     * @param headers request headers
     * @return redirect to the wishlist
     */
    @POST
    @Path("events/{id}/wishlist")
    @Operation(hidden = true)
    public Response create(@PathParam("id") UUID eventId, HttpHeaders headers) {
        UUID id = wishlists.create(jwt, eventId);
        return Redirects.to(headers, wishlistPath(id)).build();
    }

    /**
     * Wishlist page.
     *
     * @param id wishlist id
     * @return page
     * @throws NotFoundException if missing or the caller is not a member of its group
     */
    @GET
    @Path("wishlists/{id}")
    @Operation(hidden = true)
    public TemplateInstance wishlistPage(@PathParam("id") UUID id) {
        return Templates.wishlist(WishlistBodyView.of(load(id), null));
    }

    /**
     * Adds a gift (owner).
     *
     * @param id         wishlist id
     * @param name       gift name
     * @param price      optional price
     * @param quantity   how many
     * @param links      optional links, one per line
     * @param notes      optional notes
     * @param visibility {@code everyone} or {@code selected}
     * @param viewers    selected member ids
     * @return refreshed body, or the form with errors
     */
    @POST
    @Path("wishlists/{id}/gifts")
    @Operation(hidden = true)
    public Response addGift(@PathParam("id") UUID id, @RestForm String name, @RestForm String price,
                            @RestForm String quantity, @RestForm String links, @RestForm String notes,
                            @RestForm String visibility, @RestForm List<String> viewers) {
        GiftForm form = GiftForm.of(name, price, quantity, links, notes, visibility, viewers);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return Response.ok(Templates.giftForm(new GiftFormView(id, null, form, errors, load(id).members()))).build();
        }
        wishlists.addGift(jwt, id, form);
        return body(id, "Gift added.");
    }

    /**
     * Edit form for a gift (owner).
     *
     * @param id     wishlist id
     * @param giftId gift id
     * @return form fragment
     * @throws NotFoundException  if the gift is missing or hidden from the caller
     * @throws ForbiddenException if the caller does not own the wishlist
     */
    @GET
    @Path("wishlists/{id}/gifts/{giftId}/edit")
    @Operation(hidden = true)
    public TemplateInstance editGift(@PathParam("id") UUID id, @PathParam("giftId") UUID giftId) {
        WishlistDetail wishlist = load(id);
        Gift gift = wishlist.gift(giftId).orElseThrow(NotFoundException::new);
        if (!wishlist.callerIsOwner()) {
            throw new ForbiddenException();
        }
        return Templates.giftForm(GiftFormView.edit(wishlist, gift));
    }

    /**
     * Updates a gift and who can see it (owner).
     *
     * @param id         wishlist id
     * @param giftId     gift id
     * @param name       gift name
     * @param price      optional price
     * @param quantity   how many
     * @param links      optional links, one per line
     * @param notes      optional notes
     * @param visibility {@code everyone} or {@code selected}
     * @param viewers    selected member ids
     * @return refreshed body, or the form with errors
     */
    @POST
    @Path("wishlists/{id}/gifts/{giftId}")
    @Operation(hidden = true)
    public Response updateGift(@PathParam("id") UUID id, @PathParam("giftId") UUID giftId,
                               @RestForm String name, @RestForm String price, @RestForm String quantity,
                               @RestForm String links, @RestForm String notes, @RestForm String visibility,
                               @RestForm List<String> viewers) {
        GiftForm form = GiftForm.of(name, price, quantity, links, notes, visibility, viewers);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return Response.ok(Templates.giftForm(new GiftFormView(id, giftId, form, errors, load(id).members())))
                    .build();
        }
        wishlists.updateGift(jwt, id, giftId, form);
        return body(id, "Gift saved.");
    }

    /**
     * Removes a gift (owner).
     *
     * @param id     wishlist id
     * @param giftId gift id
     * @return refreshed body
     */
    @POST
    @Path("wishlists/{id}/gifts/{giftId}/delete")
    @Operation(hidden = true)
    public TemplateInstance deleteGift(@PathParam("id") UUID id, @PathParam("giftId") UUID giftId) {
        wishlists.deleteGift(jwt, id, giftId);
        return Templates.wishlistBody(WishlistBodyView.of(load(id), "Gift removed."));
    }

    /**
     * Deletes the wishlist and its gifts (owner).
     *
     * @param id      wishlist id
     * @param headers request headers
     * @return redirect to the event
     */
    @POST
    @Path("wishlists/{id}/delete")
    @Operation(hidden = true)
    public Response delete(@PathParam("id") UUID id, HttpHeaders headers) {
        UUID eventId = load(id).eventId();
        wishlists.delete(jwt, id);
        return Redirects.to(headers, "/events/" + eventId).build();
    }

    /** Refreshed body swapped into {@code #wishlist-body}, whatever element the request targeted. */
    private Response body(UUID id, String notice) {
        return Response.ok(Templates.wishlistBody(WishlistBodyView.of(load(id), notice)))
                .header(HX_RETARGET, BODY_TARGET)
                .header(HX_RESWAP, "innerHTML")
                .build();
    }

    private WishlistDetail load(UUID id) {
        return wishlists.find(jwt, id).orElseThrow(NotFoundException::new);
    }

    private static String wishlistPath(UUID id) {
        return "/wishlists/" + id;
    }
}
