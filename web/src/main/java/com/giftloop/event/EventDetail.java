package com.giftloop.event;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * An event as seen by a member of its group.
 *
 * @param id           event id
 * @param name         event name
 * @param endDate      last day of the event, or null if open-ended
 * @param groupId      owning group
 * @param groupName    owning group's name
 * @param type         event type
 * @param budget       exchange spending limit, or null
 * @param drawn        whether exchange names have been drawn
 * @param wishlists    members' wishlists, the caller's first
 * @param myWishlistId the caller's wishlist, or null if they have none yet
 * @param canManage    whether the caller may edit or delete the event (group owner/admin)
 */
public record EventDetail(UUID id, String name, LocalDate endDate, UUID groupId, String groupName,
                          EventType type, BigDecimal budget, boolean drawn,
                          List<EventWishlist> wishlists, UUID myWishlistId, boolean canManage) {

    /**
     * End date for display.
     *
     * @return formatted date, or null if open-ended
     */
    public String endDateLabel() {
        return EventSummary.format(endDate);
    }

    /**
     * Budget for display, e.g. {@code $50.00}.
     *
     * @return formatted amount, or null if none
     */
    public String budgetLabel() {
        return formatMoney(budget);
    }

    /**
     * Formats an amount as US dollars.
     *
     * @param amount amount, or null
     * @return e.g. {@code $50.00}, or null
     */
    static String formatMoney(BigDecimal amount) {
        return amount == null ? null : NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    /**
     * A wishlist in an event's list.
     *
     * @param id        wishlist id
     * @param ownerName owner's display name (username fallback)
     * @param giftCount gifts the caller can see
     * @param mine      whether the caller owns it
     */
    public record EventWishlist(UUID id, String ownerName, int giftCount, boolean mine) {
    }
}
