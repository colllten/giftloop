package com.giftloop.wishlist;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Auto-fill rate limit notice rounds the wait up to whole minutes.
 */
class AutofillLimitMessageTest {

    /** Partial minutes round up; never shows less than 1 minute. */
    @Test
    void roundsUpToMinutes() {
        assertEquals("You've auto-filled a lot of gifts lately. Try again in 60 minutes, or fill in the details below.",
                WishlistPageResource.autofillLimitedMessage(Duration.ofHours(1)));
        assertEquals("You've auto-filled a lot of gifts lately. Try again in 12 minutes, or fill in the details below.",
                WishlistPageResource.autofillLimitedMessage(Duration.ofSeconds(11 * 60 + 1)));
        assertEquals("You've auto-filled a lot of gifts lately. Try again in 1 minute, or fill in the details below.",
                WishlistPageResource.autofillLimitedMessage(Duration.ZERO));
    }
}
