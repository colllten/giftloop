package com.giftloop.share;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the link in share-sheet fields, and the URLs built from it.
 */
class SharedLinkTest {

    /** url wins; Android-style text carries the link after a name; trailing punctuation is dropped. */
    @Test
    void extractsFirstWebAddress() {
        assertEquals("https://shop.example/a", SharedLink.extract("https://shop.example/a", "https://other.example", null));
        assertEquals("https://shop.example/mug?id=2",
                SharedLink.extract(null, "Camp Mug - Shop https://shop.example/mug?id=2.", "Camp Mug"));
        assertEquals("http://shop.example/x", SharedLink.extract("", null, "see (http://shop.example/x)"));
    }

    /** Nothing usable gives null. */
    @Test
    void noLink() {
        assertNull(SharedLink.extract(null, null, null));
        assertNull(SharedLink.extract("javascript:alert(1)", "just words", "socks"));
        assertNull(SharedLink.extract(null, "https://localhost-without-dot/x", null));
    }

    /** Targets carry the encoded link and jump to the add form. */
    @Test
    void targetUrl() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("/wishlists/" + id + "?add=https%3A%2F%2Fshop.example%2Fa%3Fb%3D1#add-gift",
                ShareResource.target(id, "https://shop.example/a?b=1"));
    }

    /** The bookmarklet points at this site's /add. */
    @Test
    void bookmarklet() {
        String b = ShareResource.bookmarklet(URI.create("https://giftloop.example/"));
        assertTrue(b.startsWith("javascript:"));
        assertTrue(b.contains("'https://giftloop.example/add?url='+encodeURIComponent(location.href)"));
    }
}
