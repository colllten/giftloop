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
     * A gift as returned by the API. Visibility fields are only included for the owner.
     *
     * @param id           gift id
     * @param name         gift name
     * @param price        price, or null
     * @param links        shop links
     * @param quantity     how many the owner wants
     * @param notes        notes, or null
     * @param visibleToAll shared with everyone (owner only)
     * @param viewerIds    allowlist (owner only)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GiftDto(UUID id, String name, BigDecimal price, List<String> links, int quantity, String notes,
                          Boolean visibleToAll, Set<UUID> viewerIds) {
    }

    /**
     * Maps a wishlist to its API shape, leaving out visibility for non-owners.
     *
     * @param w wishlist
     * @return DTO
     */
    public static WishlistDto of(WishlistDetail w) {
        List<GiftDto> gifts = w.gifts().stream()
                .map(g -> new GiftDto(g.id(), g.name(), g.price(), g.links(), g.quantity(), g.notes(),
                        w.callerIsOwner() ? g.visibleToAll() : null,
                        w.callerIsOwner() ? g.viewerIds() : null))
                .toList();
        return new WishlistDto(w.id(), w.eventId(), w.ownerName(), w.callerIsOwner(), gifts);
    }
}
