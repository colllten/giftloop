package com.giftloop.wishlist;

import java.util.UUID;

/**
 * A group member the wishlist owner can share a gift with.
 *
 * @param userId member's user id
 * @param name   display name (username fallback)
 */
public record ViewerOption(UUID userId, String name) {
}
