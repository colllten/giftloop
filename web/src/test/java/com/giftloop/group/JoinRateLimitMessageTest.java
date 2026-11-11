package com.giftloop.group;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lockout message on the join form rounds the wait up to whole minutes.
 */
class JoinRateLimitMessageTest {

    /** Partial minutes round up; never shows less than 1 minute. */
    @Test
    void roundsUpToMinutes() {
        assertEquals("Too many incorrect codes. Try again in 15 minutes.",
                GroupPageResource.rateLimitedMessage(Duration.ofMinutes(15)));
        assertEquals("Too many incorrect codes. Try again in 12 minutes.",
                GroupPageResource.rateLimitedMessage(Duration.ofSeconds(11 * 60 + 1)));
        assertEquals("Too many incorrect codes. Try again in 1 minute.",
                GroupPageResource.rateLimitedMessage(Duration.ofSeconds(1)));
        assertEquals("Too many incorrect codes. Try again in 1 minute.",
                GroupPageResource.rateLimitedMessage(Duration.ZERO));
    }
}
