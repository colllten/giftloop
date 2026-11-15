package com.giftloop.wishlist;

import com.giftloop.enrich.AutofillQuota;
import com.giftloop.enrich.AutofillRateLimitedException;
import com.giftloop.enrich.LinkPreviewService;
import com.giftloop.image.Images;
import com.giftloop.security.RequiresOnboarding;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.jboss.resteasy.reactive.RestForm;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wishlist pages: start a wishlist for an event, view one, and (owner) add, edit and remove gifts
 * with per-gift visibility, photos and link auto-fill.
 *
 * <p>Gift forms swap themselves on validation errors and after auto-fill. On a successful save
 * the response re-targets the whole {@code #wishlist-body} via {@code HX-Retarget}/{@code HX-Reswap}.
 *
 * <p>Photos: a chosen file is normalized right away and kept in the form as base64 ("staged"),
 * so a failed save doesn't lose it. After the gift row is saved the photo goes to Storage; if
 * that fails the gift stays saved and the notice says so.
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
    /** HTMX request header with the browser's current URL (carries {@code ?show=}). */
    static final String HX_CURRENT_URL = "HX-Current-URL";
    private static final String BODY_TARGET = "#wishlist-body";
    private static final Logger LOG = Logger.getLogger(WishlistPageResource.class);

    private final JsonWebToken jwt;
    private final WishlistService wishlists;
    private final GiftImageService images;
    private final LinkPreviewService previews;
    private final AutofillQuota autofillQuota;

    /**
     * Creates the resource.
     *
     * @param jwt           caller's verified token
     * @param wishlists     wishlist access
     * @param images        gift photo storage
     * @param previews      link auto-fill
     * @param autofillQuota per-user auto-fill rate limit
     */
    public WishlistPageResource(JsonWebToken jwt, WishlistService wishlists, GiftImageService images,
                                LinkPreviewService previews, AutofillQuota autofillQuota) {
        this.jwt = jwt;
        this.wishlists = wishlists;
        this.images = images;
        this.previews = previews;
        this.autofillQuota = autofillQuota;
    }

    /**
     * A submitted gift form after photo staging.
     *
     * @param form   values (with any new photo staged)
     * @param errors field errors
     * @param jpeg   normalized photo to store after saving, or null
     */
    private record Submission(GiftForm form, Map<String, String> errors, byte[] jpeg) {
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

        /**
         * "Add from a past list" picker fragment.
         *
         * @param view the owner's other gifts
         * @return fragment
         */
        static native TemplateInstance pastGifts(PastGiftsView view);
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
     * @param id   wishlist id
     * @param show claim filter ({@code needed}, {@code claimed}, {@code mine}); ignored while claims are hidden
     * @param add  link to auto-fill the add form with (share target); owner only, ignored for others
     * @return page
     * @throws NotFoundException if missing or the caller is not a member of its group
     */
    @GET
    @Path("wishlists/{id}")
    @Operation(hidden = true)
    public TemplateInstance wishlistPage(@PathParam("id") UUID id, @QueryParam("show") String show,
                                         @QueryParam("add") String add) {
        WishlistDetail wishlist = load(id);
        WishlistBodyView view = WishlistBodyView.of(wishlist, null, GiftFilter.of(show));
        if (add != null && !add.isBlank() && wishlist.callerIsOwner()) {
            GiftFormView form = autofilled(wishlist, null, GiftForm.empty(), Map.of(), add);
            view = new WishlistBodyView(wishlist, form, null, view.filter());
        }
        return Templates.wishlist(view);
    }

    /**
     * Wishlist body fragment, for the claim filter links (which push {@code ?show=} to the URL).
     *
     * @param id   wishlist id
     * @param show claim filter; ignored while claims are hidden
     * @return fragment
     * @throws NotFoundException if missing or the caller is not a member of its group
     */
    @GET
    @Path("wishlists/{id}/body")
    @Operation(hidden = true)
    public TemplateInstance wishlistBody(@PathParam("id") UUID id, @QueryParam("show") String show) {
        return Templates.wishlistBody(WishlistBodyView.of(load(id), null, GiftFilter.of(show)));
    }

    /**
     * Adds a gift (owner), then stores its photo if one was chosen or auto-filled.
     *
     * @param id         wishlist id
     * @param params     form fields and optional photo
     * @param currentUrl page URL (HTMX), to keep its claim filter
     * @return refreshed body, or the form with errors
     */
    @POST
    @Path("wishlists/{id}/gifts")
    @Operation(hidden = true)
    public Response addGift(@PathParam("id") UUID id, @BeanParam GiftFormParams params,
                            @HeaderParam(HX_CURRENT_URL) String currentUrl) {
        Submission sub = submission(params, true);
        if (!sub.errors().isEmpty()) {
            return Response.ok(Templates.giftForm(GiftFormView.of(load(id), null, sub.form(), sub.errors(), null)))
                    .build();
        }
        UUID giftId = wishlists.addGift(jwt, id, sub.form());
        boolean photoOk = applyPhoto(giftId, sub);
        return body(id, photoOk ? "Gift added." : "Gift added, but the photo couldn't be saved.", currentUrl);
    }

    /**
     * Fills the add form from a pasted link (owner). Only empty fields are filled; nothing is saved.
     *
     * @param id     wishlist id
     * @param params form fields, including {@code source_url}
     * @return the form, filled where possible, with a note on what happened
     */
    @POST
    @Path("wishlists/{id}/gifts/autofill")
    @Operation(hidden = true)
    public TemplateInstance autofillNew(@PathParam("id") UUID id, @BeanParam GiftFormParams params) {
        return autofill(id, null, params);
    }

    /**
     * Fills the edit form from a pasted link (owner). Only empty fields are filled; nothing is saved.
     *
     * @param id     wishlist id
     * @param giftId gift being edited
     * @param params form fields, including {@code source_url}
     * @return the form, filled where possible, with a note on what happened
     */
    @POST
    @Path("wishlists/{id}/gifts/{giftId}/autofill")
    @Operation(hidden = true)
    public TemplateInstance autofillEdit(@PathParam("id") UUID id, @PathParam("giftId") UUID giftId,
                                         @BeanParam GiftFormParams params) {
        return autofill(id, giftId, params);
    }

    /**
     * Picker of the owner's gifts on their other wishlists (owner).
     *
     * @param id wishlist id
     * @return fragment
     * @throws ForbiddenException if the caller does not own the wishlist
     */
    @GET
    @Path("wishlists/{id}/past-gifts")
    @Operation(hidden = true)
    public TemplateInstance pastGifts(@PathParam("id") UUID id) {
        requireOwner(id);
        return Templates.pastGifts(wishlists.pastGifts(jwt, id));
    }

    /**
     * Copies picked gifts from the owner's other wishlists onto this one (owner).
     *
     * @param id         wishlist id
     * @param picked     source gift ids
     * @param currentUrl page URL (HTMX), to keep its claim filter
     * @return refreshed body, or the picker with an error
     */
    @POST
    @Path("wishlists/{id}/gifts/copy")
    @Operation(hidden = true)
    public Response copyGifts(@PathParam("id") UUID id, @RestForm("gift") List<String> picked,
                              @HeaderParam(HX_CURRENT_URL) String currentUrl) {
        List<UUID> ids = new ArrayList<>();
        for (String raw : picked == null ? List.<String>of() : picked) {
            try {
                ids.add(UUID.fromString(raw.strip()));
            } catch (IllegalArgumentException e) {
                LOG.debugf("Ignoring bad gift id %s", raw);
            }
        }
        if (ids.isEmpty()) {
            requireOwner(id);
            return Response.ok(Templates.pastGifts(wishlists.pastGifts(jwt, id)
                    .withError("Pick at least one gift to copy."))).build();
        }
        WishlistService.CopyResult result = wishlists.copyGifts(jwt, id, ids);
        String notice = switch (result.copied()) {
            case 0 -> "Nothing was copied. Those gifts are already on this list.";
            case 1 -> "Copied 1 gift.";
            default -> "Copied " + result.copied() + " gifts.";
        };
        if (result.photoFailures() > 0) {
            notice += " Some photos couldn't be copied.";
        }
        return body(id, notice, currentUrl);
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
     * Updates a gift and who can see it (owner), then applies any photo change.
     *
     * @param id         wishlist id
     * @param giftId     gift id
     * @param params     form fields and optional photo
     * @param currentUrl page URL (HTMX), to keep its claim filter
     * @return refreshed body, or the form with errors
     */
    @POST
    @Path("wishlists/{id}/gifts/{giftId}")
    @Operation(hidden = true)
    public Response updateGift(@PathParam("id") UUID id, @PathParam("giftId") UUID giftId,
                               @BeanParam GiftFormParams params, @HeaderParam(HX_CURRENT_URL) String currentUrl) {
        Submission sub = submission(params, true);
        if (!sub.errors().isEmpty()) {
            WishlistDetail wishlist = load(id);
            Gift gift = wishlist.gift(giftId).orElse(null);
            return Response.ok(Templates.giftForm(new GiftFormView(id, giftId, sub.form(), sub.errors(),
                    wishlist.members(), gift == null ? null : gift.imageUrl(), null))).build();
        }
        wishlists.updateGift(jwt, id, giftId, sub.form());
        boolean photoOk = applyPhoto(giftId, sub);
        return body(id, photoOk ? "Gift saved." : "Gift saved, but the photo couldn't be saved.", currentUrl);
    }

    /**
     * Removes a gift (owner).
     *
     * @param id     wishlist id
     * @param giftId gift id
     * @param currentUrl page URL (HTMX), to keep its claim filter
     * @return refreshed body
     */
    @POST
    @Path("wishlists/{id}/gifts/{giftId}/delete")
    @Operation(hidden = true)
    public TemplateInstance deleteGift(@PathParam("id") UUID id, @PathParam("giftId") UUID giftId,
                                       @HeaderParam(HX_CURRENT_URL) String currentUrl) {
        wishlists.deleteGift(jwt, id, giftId);
        return Templates.wishlistBody(WishlistBodyView.of(load(id), "Gift removed.", GiftFilter.fromUrl(currentUrl)));
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

    /**
     * Shared auto-fill: stages any chosen photo, reads the pasted link and fills empty fields.
     */
    private TemplateInstance autofill(UUID id, UUID giftId, GiftFormParams params) {
        WishlistDetail wishlist = load(id);
        if (!wishlist.callerIsOwner()) {
            throw new ForbiddenException();
        }
        Gift gift = giftId == null ? null : wishlist.gift(giftId).orElseThrow(NotFoundException::new);
        Submission sub = submission(params, false);
        return Templates.giftForm(autofilled(wishlist, gift, sub.form(), sub.errors(), params.sourceUrl));
    }

    /**
     * Form filled from a link. Errors on the link go under {@code source}; site problems become
     * the notice, since the owner can still fill the form in by hand.
     *
     * @param wishlist the wishlist (caller owns it)
     * @param gift     gift being edited, or null when adding
     * @param form     current values
     * @param errors   errors so far (photo staging)
     * @param rawLink  pasted link
     * @return form view
     */
    GiftFormView autofilled(WishlistDetail wishlist, Gift gift, GiftForm form, Map<String, String> errors,
                            String rawLink) {
        Map<String, String> out = new LinkedHashMap<>(errors);
        String raw = rawLink == null ? "" : rawLink.strip();
        if (raw.isEmpty()) {
            out.put("source", "Paste a link to a product page.");
            return GiftFormView.of(wishlist, gift, form, out, null);
        }
        String link = GiftForm.withScheme(raw);
        if (!GiftForm.isWebLink(link)) {
            out.put("source", "Please paste a web address, like example.com/gift.");
            return GiftFormView.of(wishlist, gift, form, out, null);
        }
        try {
            autofillQuota.consume(jwt);
        } catch (AutofillRateLimitedException e) {
            return GiftFormView.of(wishlist, gift, form, out, autofillLimitedMessage(e.retryAfter()));
        }
        LinkPreviewService.Result result = previews.preview(link);
        GiftForm filled = form.autofill(result.preview(), link, result.image(),
                gift != null && gift.imagePath() != null);
        String notice = result.problem() != null ? result.problem()
                : "Filled in from " + URI.create(link).getHost().replaceFirst("^www\\.", "")
                + ". Check the details, then " + (gift == null ? "add the gift." : "save.");
        return GiftFormView.of(wishlist, gift, filled, out, notice);
    }

    /**
     * Auto-fill notice while rate limited, with the wait rounded up to whole minutes.
     *
     * @param retryAfter time until the user may try again
     * @return e.g. "You've auto-filled a lot of gifts lately. Try again in 12 minutes, or fill in the
     *         details below."
     */
    static String autofillLimitedMessage(Duration retryAfter) {
        long minutes = Math.max(1, (retryAfter.toSeconds() + 59) / 60);
        return "You've auto-filled a lot of gifts lately. Try again in " + minutes
                + (minutes == 1 ? " minute" : " minutes") + ", or fill in the details below.";
    }

    /**
     * Reads the form and stages its photo: a chosen file wins over staged data, and staged data
     * is re-validated (it came back from the browser).
     *
     * @param params   raw fields
     * @param validate whether to check the gift fields too (false for auto-fill)
     * @return values, errors and the photo to store
     */
    private Submission submission(GiftFormParams params, boolean validate) {
        GiftForm form = params.toForm();
        Map<String, String> errors = new LinkedHashMap<>(validate ? form.validate() : Map.of());
        byte[] jpeg = null;
        try {
            if (params.hasPhoto()) {
                errors.remove("photo");
                jpeg = GiftImageService.normalize(read(params));
                form = form.withImage(jpeg);
            } else if (form.imageData() != null && !form.removeImage()) {
                jpeg = GiftImageService.normalize(form.imageBytes());
            }
        } catch (Images.InvalidImageException e) {
            errors.put("photo", e.getMessage());
            form = form.withPhoto(null, form.removeImage());
            jpeg = null;
        }
        return new Submission(form, errors, jpeg);
    }

    private static byte[] read(GiftFormParams params) throws Images.InvalidImageException {
        if (params.photo.size() > GiftImageService.MAX_SOURCE_BYTES) {
            throw new Images.InvalidImageException("That photo is too large. Please choose one under 5 MB.");
        }
        try {
            return Files.readAllBytes(params.photo.uploadedFile());
        } catch (IOException e) {
            LOG.warnf("Could not read uploaded gift photo: %s", e.getMessage());
            throw new Images.InvalidImageException("We couldn't read that photo. Please try a different one.");
        }
    }

    /** Stores, keeps or removes the photo after the gift row is saved; false if storing failed. */
    private boolean applyPhoto(UUID giftId, Submission sub) {
        if (sub.jpeg() != null) {
            return images.save(jwt, giftId, sub.jpeg());
        }
        if (sub.form().removeImage()) {
            images.remove(jwt, giftId);
        }
        return true;
    }

    /** Refreshed body swapped into {@code #wishlist-body}, keeping the page's claim filter. */
    private Response body(UUID id, String notice, String currentUrl) {
        return Response.ok(Templates.wishlistBody(WishlistBodyView.of(load(id), notice, GiftFilter.fromUrl(currentUrl))))
                .header(HX_RETARGET, BODY_TARGET)
                .header(HX_RESWAP, "innerHTML")
                .build();
    }

    private void requireOwner(UUID id) {
        if (!load(id).callerIsOwner()) {
            throw new ForbiddenException();
        }
    }

    private WishlistDetail load(UUID id) {
        return wishlists.find(jwt, id).orElseThrow(NotFoundException::new);
    }

    private static String wishlistPath(UUID id) {
        return "/wishlists/" + id;
    }
}
