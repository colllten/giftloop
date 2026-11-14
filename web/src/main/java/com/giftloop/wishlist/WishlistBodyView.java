package com.giftloop.wishlist;

/**
 * Data for the wishlist page body fragment.
 *
 * @param wishlist the wishlist
 * @param addForm  add-gift form (owner only; null for others)
 * @param notice   confirmation to show, or null
 */
public record WishlistBodyView(WishlistDetail wishlist, GiftFormView addForm, String notice) {

    /**
     * View with a blank add form for the owner.
     *
     * @param wishlist the wishlist
     * @param notice   confirmation, or null
     * @return view
     */
    public static WishlistBodyView of(WishlistDetail wishlist, String notice) {
        return new WishlistBodyView(wishlist, wishlist.callerIsOwner() ? GiftFormView.add(wishlist) : null, notice);
    }
}
