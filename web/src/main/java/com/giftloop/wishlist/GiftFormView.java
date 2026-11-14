package com.giftloop.wishlist;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data for the add/edit gift form.
 *
 * @param wishlistId wishlist the gift belongs to
 * @param giftId     gift being edited, or null when adding
 * @param form       field values
 * @param errors     field name → message
 * @param members    people the gift can be shared with
 */
public record GiftFormView(UUID wishlistId, UUID giftId, GiftForm form, Map<String, String> errors,
                           List<ViewerOption> members) {

    /**
     * Blank add form.
     *
     * @param wishlist the wishlist
     * @return view
     */
    public static GiftFormView add(WishlistDetail wishlist) {
        return new GiftFormView(wishlist.id(), null, GiftForm.empty(), Map.of(), wishlist.members());
    }

    /**
     * Edit form prefilled from a gift.
     *
     * @param wishlist the wishlist
     * @param gift     gift to edit
     * @return view
     */
    public static GiftFormView edit(WishlistDetail wishlist, Gift gift) {
        return new GiftFormView(wishlist.id(), gift.id(), GiftForm.from(gift), Map.of(), wishlist.members());
    }

    /**
     * Whether this edits an existing gift.
     *
     * @return true when editing
     */
    public boolean isEdit() {
        return giftId != null;
    }

    /**
     * Prefix for element ids, unique per form on the page.
     *
     * @return {@code gift-new} or {@code gift-<id>}
     */
    public String key() {
        return giftId == null ? "gift-new" : "gift-" + giftId;
    }

    /**
     * Where the form posts.
     *
     * @return add or update path
     */
    public String action() {
        String base = "/wishlists/" + wishlistId + "/gifts";
        return giftId == null ? base : base + "/" + giftId;
    }

    /**
     * Error for a field.
     *
     * @param field field name
     * @return message, or null
     */
    public String error(String field) {
        return errors.get(field);
    }
}
