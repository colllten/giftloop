package com.giftloop.wishlist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A wishlist as seen by a member of its event's group.
 *
 * @param id            wishlist id
 * @param eventId       event it belongs to
 * @param eventName     event name
 * @param groupId       group of the event
 * @param groupName     group name
 * @param ownerName     owner's display name (username fallback)
 * @param callerIsOwner whether the caller owns the wishlist
 * @param gifts         gifts the caller can see, oldest first
 * @param members       members the owner can share gifts with (owner only; empty for others)
 */
public record WishlistDetail(UUID id, UUID eventId, String eventName, UUID groupId, String groupName,
                             String ownerName, boolean callerIsOwner, List<Gift> gifts,
                             List<ViewerOption> members) {

    /**
     * Page heading.
     *
     * @return "Your wishlist" or "Name's wishlist"
     */
    public String title() {
        return callerIsOwner ? "Your wishlist" : ownerName + "'s wishlist";
    }

    /**
     * A gift on this wishlist.
     *
     * @param giftId gift id
     * @return the gift, or empty if missing or hidden from the caller
     */
    public Optional<Gift> gift(UUID giftId) {
        return gifts.stream().filter(g -> g.id().equals(giftId)).findFirst();
    }

    /**
     * Names of the people a restricted gift is shared with, for the owner.
     *
     * @param gift a gift on this wishlist
     * @return comma-separated names in member order, or empty
     */
    public String viewerNames(Gift gift) {
        return members.stream()
                .filter(m -> gift.viewerIds().contains(m.userId()))
                .map(ViewerOption::name)
                .collect(Collectors.joining(", "));
    }
}
