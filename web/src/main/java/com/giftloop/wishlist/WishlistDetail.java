package com.giftloop.wishlist;

import com.giftloop.claim.GiftClaim;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
 * @param claimsRevealed the owner may see claims (event ended, or the group allows it early)
 * @param revealDate    day claims are revealed to the owner by date, or null if the event has no end date
 * @param coBuyers      members a claimer can add as co-buyers: not the owner or the caller (non-owners only)
 */
public record WishlistDetail(UUID id, UUID eventId, String eventName, UUID groupId, String groupName,
                             String ownerName, boolean callerIsOwner, List<Gift> gifts,
                             List<ViewerOption> members, boolean claimsRevealed, LocalDate revealDate,
                             List<ViewerOption> coBuyers) {

    private static final DateTimeFormatter REVEAL_FORMAT = DateTimeFormatter.ofPattern("MMMM d", Locale.US);

    /**
     * Whether the caller may see claims on this wishlist (always for members; owners after reveal).
     *
     * @return true unless the caller is the owner and claims are still hidden
     */
    public boolean claimsVisibleToCaller() {
        return !callerIsOwner || claimsRevealed;
    }

    /**
     * Banner text for the owner while claims are hidden.
     *
     * @return when claims become visible, or null when the caller isn't a waiting owner
     */
    public String revealNotice() {
        if (!callerIsOwner || claimsRevealed) {
            return null;
        }
        return revealDate == null
                ? "You can't see who claimed what. This event has no end date, so claims stay hidden unless a group admin shows them."
                : "You can't see who claimed what until " + REVEAL_FORMAT.format(revealDate) + ".";
    }

    /**
     * URL of a claim on one of this wishlist's gifts; claim actions append to it.
     *
     * @param gift  gift on this wishlist
     * @param claim claim on that gift
     * @return {@code /wishlists/<w>/gifts/<g>/claims/<c>}
     */
    public String claimUrl(Gift gift, GiftClaim claim) {
        return "/wishlists/" + id + "/gifts/" + gift.id() + "/claims/" + claim.id();
    }

    /**
     * Co-buyers the caller could still add to a claim.
     *
     * @param claim one of the caller's claims on this wishlist
     * @return members not already on it
     */
    public List<ViewerOption> coBuyersFor(GiftClaim claim) {
        return coBuyers.stream().filter(m -> !claim.hasParticipant(m.userId())).toList();
    }

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
