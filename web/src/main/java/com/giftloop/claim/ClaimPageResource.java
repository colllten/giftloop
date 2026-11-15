package com.giftloop.claim;

import com.giftloop.security.RequiresOnboarding;
import com.giftloop.wishlist.Gift;
import com.giftloop.wishlist.GiftFilter;
import com.giftloop.wishlist.WishlistBodyView;
import com.giftloop.wishlist.WishlistDetail;
import com.giftloop.wishlist.WishlistService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestForm;

import java.util.Map;
import java.util.UUID;

/**
 * Claiming gifts: the "My claims" page and the claim actions on wishlist gift cards.
 *
 * <p>Actions live under the gift ({@code /wishlists/{w}/gifts/{g}/claims/...}) so the response can
 * re-render that gift's card ({@code #gift-<id>}, outerHTML). Forms on My claims post
 * {@code view=claims} and get the refreshed {@code #claims-body} instead. Expected rule failures
 * (bad quantity, too many marked bought, a co-buyer who can't buy the gift) re-render with an inline error.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class ClaimPageResource {

    /** Form value of {@code view} sent from the My claims page. */
    static final String CLAIMS_VIEW = "claims";
    /** HTMX request header with the browser's current URL (carries {@code ?show=}). */
    static final String HX_CURRENT_URL = "HX-Current-URL";

    private final JsonWebToken jwt;
    private final ClaimService claims;
    private final WishlistService wishlists;

    /**
     * Creates the resource.
     *
     * @param jwt       caller's verified token
     * @param claims    claim access
     * @param wishlists wishlist access (to re-render gift cards)
     */
    public ClaimPageResource(JsonWebToken jwt, ClaimService claims, WishlistService wishlists) {
        this.jwt = jwt;
        this.claims = claims;
        this.wishlists = wishlists;
    }

    /**
     * Templates under {@code templates/ClaimPageResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * My claims page.
         *
         * @param view the caller's claims
         * @return page
         */
        static native TemplateInstance claims(MyClaimsView view);

        /**
         * My claims body fragment ({@code #claims-body}).
         *
         * @param view the caller's claims
         * @return fragment
         */
        static native TemplateInstance claimsBody(MyClaimsView view);

        /**
         * One gift card fragment ({@code #gift-<id>}).
         *
         * @param w      the gift's wishlist
         * @param gift   the gift
         * @param notice confirmation, or null
         * @param error  error, or null
         * @return fragment
         */
        static native TemplateInstance giftCard(WishlistDetail w, Gift gift, String notice, String error);

        /**
         * Whole wishlist body ({@code #wishlist-body}), for when the gift's card goes away.
         *
         * @param view body data
         * @return fragment
         */
        static native TemplateInstance wishlistBody(WishlistBodyView view);
    }

    /**
     * Everything the caller has claimed, grouped by event.
     *
     * @return page
     */
    @GET
    @Path("claims")
    @Operation(hidden = true)
    public TemplateInstance claimsPage() {
        return Templates.claims(MyClaimsView.of(claims.myClaims(jwt), null, null));
    }

    /**
     * Claims a gift.
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param quantity   units to claim
     * @param anonymous  {@code on} to hide the caller's name
     * @return the refreshed gift card
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims")
    @Operation(hidden = true)
    public Response claim(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                          @RestForm String quantity, @RestForm String anonymous) {
        ClaimForm form = ClaimForm.of(quantity, anonymous);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return respond(wishlistId, giftId, null, null, errors.get("quantity"));
        }
        claims.create(jwt, giftId, form);
        return respond(wishlistId, giftId, null, "Claimed. It's on your My claims list.", null);
    }

    /**
     * Changes how many units the caller's claim covers (creator).
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param claimId    claim id
     * @param quantity   new quantity
     * @param view       {@code claims} when posted from My claims
     * @return the refreshed card or My claims body
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims/{claimId}")
    @Operation(hidden = true)
    public Response updateQuantity(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                                   @PathParam("claimId") UUID claimId, @RestForm String quantity,
                                   @RestForm String view) {
        ClaimForm form = ClaimForm.of(quantity, null);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return respond(wishlistId, giftId, view, null, errors.get("quantity"));
        }
        return attempt(wishlistId, giftId, view, "Claim updated.",
                () -> claims.updateQuantity(jwt, claimId, form.quantityValue()));
    }

    /**
     * Sets how many units of the claim are bought (any participant).
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param claimId    claim id
     * @param purchased  units bought, 0 to the claim's quantity
     * @param view       {@code claims} when posted from My claims
     * @return the refreshed card or My claims body, with an error if it's out of range
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims/{claimId}/purchased")
    @Operation(hidden = true)
    public Response setPurchased(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                                 @PathParam("claimId") UUID claimId, @RestForm String purchased,
                                 @RestForm String view) {
        PurchasedForm form = PurchasedForm.of(purchased);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return respond(wishlistId, giftId, view, null, errors.get("purchased"));
        }
        int count = form.value();
        String notice = count == 0 ? "Marked as not bought." : "Marked " + count + " as bought.";
        return attempt(wishlistId, giftId, view, notice,
                () -> claims.setPurchased(jwt, claimId, count));
    }

    /**
     * Shows or hides the caller's name on a claim they are on.
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param claimId    claim id
     * @param anonymous  {@code true} to hide the caller's name
     * @param view       {@code claims} when posted from My claims
     * @return the refreshed card or My claims body
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims/{claimId}/anonymous")
    @Operation(hidden = true)
    public Response setAnonymous(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                                 @PathParam("claimId") UUID claimId, @RestForm boolean anonymous,
                                 @RestForm String view) {
        claims.setAnonymous(jwt, claimId, anonymous);
        return respond(wishlistId, giftId, view, anonymous ? "Your name is hidden." : "Your name is shown.", null);
    }

    /**
     * Adds a co-buyer to the caller's claim (creator).
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param claimId    claim id
     * @param userId     member to add
     * @param view       {@code claims} when posted from My claims
     * @return the refreshed card or My claims body, with an error if they can't buy this gift
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims/{claimId}/participants")
    @Operation(hidden = true)
    public Response addParticipant(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                                   @PathParam("claimId") UUID claimId, @RestForm UUID userId,
                                   @RestForm String view) {
        if (userId == null) {
            return respond(wishlistId, giftId, view, null, "Pick someone to add.");
        }
        return attempt(wishlistId, giftId, view, "Co-buyer added.",
                () -> claims.addParticipant(jwt, claimId, userId));
    }

    /**
     * Removes a co-buyer (creator) or leaves a claim (co-buyer).
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param claimId    claim id
     * @param userId     participant to remove
     * @param view       {@code claims} when posted from My claims
     * @param currentUrl page URL (HTMX), to keep its claim filter if the whole body is refreshed
     * @return the refreshed card (or wishlist body, if the caller can no longer see the gift) or My claims body
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims/{claimId}/participants/{userId}/delete")
    @Operation(hidden = true)
    public Response removeParticipant(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                                      @PathParam("claimId") UUID claimId, @PathParam("userId") UUID userId,
                                      @RestForm String view, @HeaderParam(HX_CURRENT_URL) String currentUrl) {
        boolean leaving = userId.toString().equals(jwt.getSubject());
        String notice = leaving ? "You left the claim." : "Co-buyer removed.";
        try {
            claims.removeParticipant(jwt, claimId, userId);
        } catch (BadRequestException e) {
            return respond(wishlistId, giftId, view, null, e.getMessage());
        }
        return respond(wishlistId, giftId, view, notice, null, currentUrl);
    }

    /**
     * Releases the caller's claim (creator).
     *
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param claimId    claim id
     * @param view       {@code claims} when posted from My claims
     * @param currentUrl page URL (HTMX), to keep its claim filter if the whole body is refreshed
     * @return the refreshed card (or wishlist body, if the gift is now gone: removed or off-list) or My claims body
     */
    @POST
    @Path("wishlists/{wishlistId}/gifts/{giftId}/claims/{claimId}/delete")
    @Operation(hidden = true)
    public Response release(@PathParam("wishlistId") UUID wishlistId, @PathParam("giftId") UUID giftId,
                            @PathParam("claimId") UUID claimId, @RestForm String view,
                            @HeaderParam(HX_CURRENT_URL) String currentUrl) {
        claims.release(jwt, claimId);
        return respond(wishlistId, giftId, view, "Claim released.", null, currentUrl);
    }

    /** Runs an action; a rule rejection ({@link BadRequestException}) becomes an inline error. */
    private Response attempt(UUID wishlistId, UUID giftId, String view, String notice, Runnable action) {
        try {
            action.run();
        } catch (BadRequestException e) {
            return respond(wishlistId, giftId, view, null, e.getMessage());
        }
        return respond(wishlistId, giftId, view, notice, null);
    }

    /** {@link #respond(UUID, UUID, String, String, String, String)} without a page URL. */
    private Response respond(UUID wishlistId, UUID giftId, String view, String notice, String error) {
        return respond(wishlistId, giftId, view, notice, error, null);
    }

    /**
     * The fragment the form asked for: the My claims body, or the gift's card. When the caller can
     * no longer see the gift (released claim on a removed or off-list gift), the whole wishlist
     * body is re-rendered instead ({@code HX-Retarget}), so section headings and counts stay right.
     */
    private Response respond(UUID wishlistId, UUID giftId, String view, String notice, String error,
                             String currentUrl) {
        if (CLAIMS_VIEW.equals(view)) {
            return Response.ok(Templates.claimsBody(MyClaimsView.of(claims.myClaims(jwt), notice, error))).build();
        }
        WishlistDetail w = wishlists.find(jwt, wishlistId).orElseThrow(NotFoundException::new);
        return w.gift(giftId)
                .map(gift -> Response.ok(Templates.giftCard(w, gift, notice, error)).build())
                .orElseGet(() -> Response.ok(Templates.wishlistBody(
                                WishlistBodyView.of(w, notice, GiftFilter.fromUrl(currentUrl))))
                        .header("HX-Retarget", "#wishlist-body")
                        .header("HX-Reswap", "innerHTML")
                        .build());
    }
}
