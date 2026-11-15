package com.giftloop.wishlist;

import java.util.Map;
import java.util.UUID;

/**
 * Data for the off-list gift form (a giver adding or editing a gift that isn't on the list).
 *
 * @param wishlistId wishlist the gift goes on
 * @param giftId     gift being edited, or null when adding
 * @param form       field values
 * @param errors     field name → message
 * @param ownerName  wishlist owner's name, for the hint
 * @param revealed   the owner can already see claims (so they'd see this too)
 */
public record OffListFormView(UUID wishlistId, UUID giftId, OffListForm form, Map<String, String> errors,
                              String ownerName, boolean revealed) {

    /**
     * Blank add form.
     *
     * @param wishlist the wishlist
     * @return view
     */
    public static OffListFormView add(WishlistDetail wishlist) {
        return of(wishlist, null, OffListForm.empty(), Map.of());
    }

    /**
     * Edit form prefilled from an off-list gift.
     *
     * @param wishlist the wishlist
     * @param gift     the gift
     * @return view
     */
    public static OffListFormView edit(WishlistDetail wishlist, Gift gift) {
        return of(wishlist, gift.id(), OffListForm.from(gift), Map.of());
    }

    /**
     * Form re-shown after a failed save.
     *
     * @param wishlist the wishlist
     * @param giftId   gift being edited, or null when adding
     * @param form     submitted values
     * @param errors   field errors
     * @return view
     */
    public static OffListFormView of(WishlistDetail wishlist, UUID giftId, OffListForm form,
                                     Map<String, String> errors) {
        return new OffListFormView(wishlist.id(), giftId, form, errors, wishlist.ownerName(),
                wishlist.claimsRevealed());
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
     * Where the form posts.
     *
     * @return {@code /wishlists/<w>/off-list} or {@code /wishlists/<w>/gifts/<g>/off-list}
     */
    public String action() {
        return isEdit() ? "/wishlists/" + wishlistId + "/gifts/" + giftId + "/off-list"
                : "/wishlists/" + wishlistId + "/off-list";
    }

    /**
     * Prefix for element ids, unique per form on the page.
     *
     * @return {@code off-list-new} or {@code off-list-<gift id>}
     */
    public String key() {
        return isEdit() ? "off-list-" + giftId : "off-list-new";
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
