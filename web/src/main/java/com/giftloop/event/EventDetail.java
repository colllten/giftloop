package com.giftloop.event;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An event as seen by a member of its group.
 *
 * @param id           event id
 * @param name         event name
 * @param endDate      last day of the event, or null if open-ended
 * @param groupId      owning group
 * @param groupName    owning group's name
 * @param wishlists    members' wishlists, the caller's first
 * @param myWishlistId the caller's wishlist, or null if they have none yet
 */
public record EventDetail(UUID id, String name, LocalDate endDate, UUID groupId, String groupName,
                          List<EventWishlist> wishlists, UUID myWishlistId) {

    /**
     * End date for display.
     *
     * @return formatted date, or null if open-ended
     */
    public String endDateLabel() {
        return EventSummary.format(endDate);
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
