package com.giftloop.wishlist;

import java.util.List;

/**
 * Data for the wishlist page body fragment.
 *
 * @param wishlist the wishlist
 * @param addForm  add-gift form (owner only; null for others)
 * @param notice   confirmation to show, or null
 * @param filter   claim filter in effect ({@link GiftFilter#ALL} when claims are hidden from the caller)
 * @param offListForm form to record a gift that isn't on the list (non-owners only; null for the owner)
 */
public record WishlistBodyView(WishlistDetail wishlist, GiftFormView addForm, String notice, GiftFilter filter,
                               OffListFormView offListForm) {

    /**
     * View with a blank add form for the owner and no filter.
     *
     * @param wishlist the wishlist
     * @param notice   confirmation, or null
     * @return view
     */
    public static WishlistBodyView of(WishlistDetail wishlist, String notice) {
        return of(wishlist, notice, GiftFilter.ALL);
    }

    /**
     * View with a blank add form for the owner, filtered by claim state when the caller may see
     * claims. Requested filters are ignored otherwise, so they can't hint at hidden claims.
     *
     * @param wishlist  the wishlist
     * @param notice    confirmation, or null
     * @param requested filter asked for
     * @return view
     */
    public static WishlistBodyView of(WishlistDetail wishlist, String notice, GiftFilter requested) {
        GiftFilter filter = wishlist.claimsVisibleToCaller() ? requested : GiftFilter.ALL;
        boolean owner = wishlist.callerIsOwner();
        return new WishlistBodyView(wishlist, owner ? GiftFormView.add(wishlist) : null, notice, filter,
                owner ? null : OffListFormView.add(wishlist));
    }

    /**
     * Same view with a different add form (auto-filled from a shared link).
     *
     * @param form owner's add form
     * @return view
     */
    public WishlistBodyView withAddForm(GiftFormView form) {
        return new WishlistBodyView(wishlist, form, notice, filter, offListForm);
    }

    /**
     * Same view with a different off-list form (re-shown with errors).
     *
     * @param form off-list form
     * @return view
     */
    public WishlistBodyView withOffListForm(OffListFormView form) {
        return new WishlistBodyView(wishlist, addForm, notice, filter, form);
    }

    /**
     * Whether to show the filter bar: the caller can see claims and there is something to filter.
     *
     * @return true to show the filters
     */
    public boolean showFilters() {
        return wishlist.claimsVisibleToCaller() && !wishlist.gifts().isEmpty();
    }

    /**
     * Filters the caller may choose from.
     *
     * @return choices in display order
     */
    public List<GiftFilter> filters() {
        return GiftFilter.choices(wishlist.callerIsOwner());
    }

    /**
     * Page URL for a filter (pushed to the address bar; works without JavaScript).
     *
     * @param f a filter
     * @return {@code /wishlists/<id>} plus {@code ?show=} unless it is {@link GiftFilter#ALL}
     */
    public String pageUrl(GiftFilter f) {
        String base = "/wishlists/" + wishlist.id();
        return f == GiftFilter.ALL ? base : base + "?show=" + f.param();
    }

    /**
     * Body-fragment URL for a filter, fetched by HTMX.
     *
     * @param f a filter
     * @return {@code /wishlists/<id>/body?show=<f>}
     */
    public String bodyUrl(GiftFilter f) {
        return "/wishlists/" + wishlist.id() + "/body?show=" + f.param();
    }

    /**
     * Whether a filter is the one in effect (Qute-friendly enum comparison).
     *
     * @param f a filter
     * @return true for the current filter
     */
    public boolean isCurrent(GiftFilter f) {
        return f == filter;
    }

    /**
     * Gifts matching a filter, for the counts on the filter buttons.
     *
     * @param f a filter
     * @return number of matching gifts
     */
    public long count(GiftFilter f) {
        return wishlist.gifts().stream().filter(f::matches).count();
    }

    /**
     * Whether the owner put anything on the list (off-list gifts don't count).
     *
     * @return true if there is at least one gift from the owner
     */
    public boolean hasListGifts() {
        return wishlist.gifts().stream().anyMatch(g -> !g.offList());
    }

    /**
     * The owner's gifts to list under the current filter.
     *
     * @return matching gifts, oldest first
     */
    public List<Gift> shownGifts() {
        return wishlist.gifts().stream().filter(g -> !g.offList()).filter(filter::matches).toList();
    }

    /**
     * Gifts givers added that aren't on the list, under the current filter.
     *
     * @return matching off-list gifts, oldest first
     */
    public List<Gift> shownOffListGifts() {
        return wishlist.gifts().stream().filter(Gift::offList).filter(filter::matches).toList();
    }

    /**
     * Whether the filter hides everything, so the empty-filter message shows.
     *
     * @return true if no gift (on or off the list) matches
     */
    public boolean nothingShown() {
        return wishlist.gifts().stream().noneMatch(filter::matches);
    }

    /**
     * Empty-state text when the filter matches nothing.
     *
     * @return message for the current filter
     */
    public String emptyFilterMessage() {
        return switch (filter) {
            case NEEDED -> "Everything here is fully claimed.";
            case FULL -> "Nothing here is fully claimed yet.";
            case MINE -> "You haven't claimed anything on this list.";
            case ALL -> "";
        };
    }
}
