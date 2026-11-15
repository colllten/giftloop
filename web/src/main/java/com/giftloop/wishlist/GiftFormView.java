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
 * @param currentImageUrl photo the gift already has (edit only), or null
 * @param notice     auto-fill outcome to show by the link field, or null
 */
public record GiftFormView(UUID wishlistId, UUID giftId, GiftForm form, Map<String, String> errors,
                           List<ViewerOption> members, String currentImageUrl, String notice) {

    /**
     * Blank add form.
     *
     * @param wishlist the wishlist
     * @return view
     */
    public static GiftFormView add(WishlistDetail wishlist) {
        return new GiftFormView(wishlist.id(), null, GiftForm.empty(), Map.of(), wishlist.members(), null, null);
    }

    /**
     * Edit form prefilled from a gift.
     *
     * @param wishlist the wishlist
     * @param gift     gift to edit
     * @return view
     */
    public static GiftFormView edit(WishlistDetail wishlist, Gift gift) {
        return new GiftFormView(wishlist.id(), gift.id(), GiftForm.from(gift), Map.of(), wishlist.members(),
                gift.imageUrl(), null);
    }

    /**
     * Form re-shown after a failed save or an auto-fill.
     *
     * @param wishlist the wishlist
     * @param gift     gift being edited, or null when adding
     * @param form     submitted (or filled) values
     * @param errors   field errors
     * @param notice   auto-fill outcome, or null
     * @return view
     */
    public static GiftFormView of(WishlistDetail wishlist, Gift gift, GiftForm form, Map<String, String> errors,
                                  String notice) {
        return new GiftFormView(wishlist.id(), gift == null ? null : gift.id(), form, errors, wishlist.members(),
                gift == null ? null : gift.imageUrl(), notice);
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
     * Where the link field posts for auto-fill.
     *
     * @return {@code <action>/autofill} for edits, {@code /wishlists/<id>/gifts/autofill} for adds
     */
    public String autofillAction() {
        return giftId == null ? "/wishlists/" + wishlistId + "/gifts/autofill" : action() + "/autofill";
    }

    /**
     * Inline preview of a staged photo.
     *
     * @return {@code data:} URL, or null when nothing is staged
     */
    public String stagedImageSrc() {
        return form.imageData() == null ? null : "data:image/jpeg;base64," + form.imageData();
    }

    /**
     * Photo to preview: the staged one, else the gift's current one unless removal is ticked.
     *
     * @return image URL, or null
     */
    public String previewImageSrc() {
        if (stagedImageSrc() != null) {
            return stagedImageSrc();
        }
        return form.removeImage() ? null : currentImageUrl;
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
