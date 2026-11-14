package com.giftloop.wishlist;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A wishlist as returned by the API.
 *
 * @param id        wishlist id
 * @param eventId   event it belongs to
 * @param ownerName owner's display name
 * @param mine      whether the caller owns it
 * @param gifts     gifts the caller can see
 */
public record WishlistDto(UUID id, UUID eventId, String ownerName, boolean mine, List<GiftDto> gifts) {

    /**
     * A gift as returned by the API. Visibility fields are only included for the owner; claim
     * fields are left out while claims are hidden from the caller (owner before reveal).
     *
     * @param id           gift id
     * @param name         gift name
     * @param price        price, or null
     * @param links        shop links
     * @param quantity     how many the owner wants
     * @param notes        notes, or null
     * @param visibleToAll shared with everyone (owner only)
     * @param viewerIds    allowlist (owner only)
     * @param claimedQuantity units claimed across all claims (may exceed {@code quantity})
     * @param claimedByMe  whether the caller is on a claim
     * @param removed      true when the owner removed it (only its claimers see it); omitted otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GiftDto(UUID id, String name, BigDecimal price, List<String> links, int quantity, String notes,
                          Boolean visibleToAll, Set<UUID> viewerIds, Integer claimedQuantity, Boolean claimedByMe,
                          Boolean removed) {
    }

    /**
     * Maps a wishlist to its API shape, leaving out visibility for non-owners and hidden claims.
     *
     * @param w wishlist
     * @return DTO
     */
    public static WishlistDto of(WishlistDetail w) {
        List<GiftDto> gifts = w.gifts().stream()
                .map(g -> new GiftDto(g.id(), g.name(), g.price(), g.links(), g.quantity(), g.notes(),
                        w.callerIsOwner() ? g.visibleToAll() : null,
                        w.callerIsOwner() ? g.viewerIds() : null,
                        g.claimsShown() ? g.claims().claimedQuantity() : null,
                        g.claimsShown() ? g.claims().hasMine() : null,
                        g.removed() ? Boolean.TRUE : null))
                .toList();
        return new WishlistDto(w.id(), w.eventId(), w.ownerName(), w.callerIsOwner(), gifts);
    }
}
