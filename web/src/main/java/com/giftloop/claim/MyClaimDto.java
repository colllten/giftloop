package com.giftloop.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A claim the caller is on, as returned by the API.
 *
 * @param id          claim id
 * @param quantity    units claimed
 * @param purchased   units marked bought
 * @param creator     whether the caller created it (and may change it)
 * @param anonymous   whether the caller's name is hidden on it
 * @param coBuyers    other visible participants' names
 * @param giftId      gift id
 * @param giftName    gift name
 * @param price       gift price, or null
 * @param links       shop links
 * @param wanted      how many the owner wants
 * @param removed     the owner removed the gift
 * @param wishlistId  wishlist id
 * @param ownerName   wishlist owner's name
 * @param eventId     event id
 * @param eventName   event name
 * @param endDate     event end date, or null
 * @param groupName   group name
 * @param offList     the gift isn't on the owner's list (a giver added it)
 */
public record MyClaimDto(UUID id, int quantity, int purchased, boolean creator, boolean anonymous,
                         List<String> coBuyers, UUID giftId, String giftName, BigDecimal price, List<String> links,
                         int wanted, boolean removed, UUID wishlistId, String ownerName, UUID eventId, String eventName,
                         LocalDate endDate, String groupName, boolean offList) {

    /**
     * Maps a claim to its API shape.
     *
     * @param c the caller's claim
     * @return DTO
     */
    public static MyClaimDto of(MyClaim c) {
        return new MyClaimDto(c.claim().id(), c.claim().quantity(), c.claim().purchased(), c.claim().callerIsCreator(),
                c.claim().callerIsAnonymous(), c.claim().others().stream().map(Participant::name).toList(),
                c.giftId(), c.giftName(), c.price(), c.links(), c.wanted(), c.giftRemoved(), c.wishlistId(),
                c.ownerName(), c.eventId(), c.eventName(), c.endDate(), c.groupName(), c.offList());
    }
}
