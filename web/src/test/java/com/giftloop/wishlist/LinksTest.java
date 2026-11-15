package com.giftloop.wishlist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Link tidying (tracking parameters) and display labels.
 */
class LinksTest {

    /** Tracking parameters go; real ones, order and fragments stay. */
    @Test
    void stripsTrackingParameters() {
        assertEquals("https://shop.example/p/1?color=red&size=m#reviews",
                Links.tidy("https://shop.example/p/1?utm_source=x&color=red&fbclid=abc&size=m&UTM_Campaign=y#reviews"));
        assertEquals("https://shop.example/p/1", Links.tidy("https://shop.example/p/1?gclid=1&ref=home"));
        assertEquals("https://shop.example/p/1?q=a%26b", Links.tidy("https://shop.example/p/1?q=a%26b&srsltid=zz"));
        assertEquals("https://shop.example/p/1", Links.tidy("https://shop.example/p/1?"));
    }

    /** Amazon /ref= path segments are dropped; other stores keep their paths. */
    @Test
    void stripsAmazonRefSegments() {
        assertEquals("https://www.amazon.com/Kettle/dp/B000123?th=1",
                Links.tidy("https://www.amazon.com/Kettle/dp/B000123/ref=sr_1_3?th=1&ref_=nav"));
        assertEquals("https://www.amazon.co.uk/dp/B000123", Links.tidy("https://www.amazon.co.uk/dp/B000123/ref=abc"));
        assertEquals("https://shop.example/ref=keep", Links.tidy("https://shop.example/ref=keep"));
    }

    /** Non-web links are left for validation to reject. */
    @Test
    void leavesOtherLinksAlone() {
        assertEquals("javascript:alert(1)", Links.tidy("javascript:alert(1)"));
        assertEquals(null, Links.tidy(null));
    }

    /** Labels show the store host without www. */
    @Test
    void labels() {
        assertEquals("target.com", Links.label("https://www.target.com/p/thing?x=1"));
        assertEquals("shop.example", Links.label("https://Shop.Example:8080/x"));
        assertEquals("not a link", Links.label("not a link"));
    }

    /** Gift forms tidy links on the way in. */
    @Test
    void giftFormTidiesLinks() {
        GiftForm form = GiftForm.of("Socks", null, null, "shop.example/x?utm_medium=email&id=4", null, null, null);
        assertEquals(List.of("https://shop.example/x?id=4"), form.linkList());
    }
}
