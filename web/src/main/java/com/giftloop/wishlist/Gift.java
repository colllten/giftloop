package com.giftloop.wishlist;

import com.giftloop.claim.GiftClaims;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A gift on a wishlist, as the caller may see it.
 *
 * @param id           gift id
 * @param name         gift name
 * @param price        price, or null
 * @param links        shop links (http/https), possibly empty
 * @param quantity     how many the owner wants
 * @param notes        notes, or null
 * @param visibleToAll shared with every group member
 * @param viewerIds    allowlist when not shared with everyone; filled in for the owner only
 * @param removed      the owner removed it; only its claimers still see it
 * @param claims       claims the caller may see, or null when claims are hidden (owner before reveal)
 */
public record Gift(UUID id, String name, BigDecimal price, List<String> links, int quantity, String notes,
                   boolean visibleToAll, Set<UUID> viewerIds, boolean removed, GiftClaims claims) {

    /**
     * Whether the caller may see claims on this gift.
     *
     * @return true unless claims are hidden from the caller
     */
    public boolean claimsShown() {
        return claims != null;
    }

    /**
     * CSS selector of this gift's card, for HTMX swaps.
     *
     * @return {@code #gift-<id>}
     */
    public String target() {
        return "#gift-" + id;
    }

    /**
     * Whether claiming would go past what the owner wants, so the UI asks first.
     *
     * @return true if existing claims already cover the quantity
     */
    public boolean fullyClaimed() {
        return claims != null && claims.isFull(quantity);
    }

    /**
     * Quantity to suggest in the claim form: what's left, or 1 when already covered.
     *
     * @return suggested quantity
     */
    public int suggestedClaimQuantity() {
        int left = claims == null ? quantity : claims.remaining(quantity);
        return Math.max(1, left);
    }

    /**
     * Price for display, e.g. {@code $249.99}.
     *
     * @return formatted price, or null if none
     */
    public String priceLabel() {
        return price == null ? null : NumberFormat.getCurrencyInstance(Locale.US).format(price);
    }

    /**
     * Visibility chip text for the owner.
     *
     * @return "Everyone", "Only you", or "Shared with N"
     */
    public String visibilityLabel() {
        if (visibleToAll) {
            return "Everyone";
        }
        int n = viewerIds.size();
        return n == 0 ? "Only you" : "Shared with " + n + (n == 1 ? " person" : " people");
    }
}
