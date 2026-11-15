package com.giftloop.wishlist;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gift form validation: DB limits plus link schemes and viewer selection.
 */
class GiftFormTest {

    private static GiftForm form(String price, String quantity, String links) {
        return GiftForm.of("Socks", price, quantity, links, null, GiftForm.EVERYONE, null);
    }

    private static GiftForm shared(List<String> viewers) {
        return GiftForm.of("Socks", null, null, null, null, GiftForm.SELECTED, viewers);
    }

    /** Name is required and at most 200 characters; notes at most 1000. */
    @Test
    void nameAndNotes() {
        assertTrue(form(null, null, null).validate().isEmpty());
        assertTrue(GiftForm.of(" ", null, null, null, null, null, null).validate().containsKey("name"));
        assertTrue(GiftForm.of("x".repeat(201), null, null, null, null, null, null).validate().containsKey("name"));
        assertTrue(GiftForm.of("x", null, null, null, "n".repeat(1001), null, null).validate().containsKey("notes"));
    }

    /** Prices allow "$" and commas, at most 2 decimals, not negative, fit numeric(10,2). */
    @Test
    void price() {
        assertEquals(new BigDecimal("1249.99"), form("$1,249.99", null, null).priceValue());
        assertNull(form("", null, null).priceValue());
        assertTrue(form("12.345", null, null).validate().containsKey("price"));
        assertTrue(form("-1", null, null).validate().containsKey("price"));
        assertTrue(form("abc", null, null).validate().containsKey("price"));
        assertTrue(form("100000000", null, null).validate().containsKey("price"));
    }

    /** Quantity defaults to 1 and must be 1–999. */
    @Test
    void quantity() {
        assertEquals(1, form(null, null, null).quantityValue());
        assertEquals(3, form(null, "3", null).quantityValue());
        assertTrue(form(null, "0", null).validate().containsKey("quantity"));
        assertTrue(form(null, "1000", null).validate().containsKey("quantity"));
        assertTrue(form(null, "two", null).validate().containsKey("quantity"));
    }

    /** Links are one per line, at most 10; https:// is added when no scheme is typed. */
    @Test
    void links() {
        assertEquals(List.of("https://a.example", "http://b.example/x"),
                form(null, null, " https://a.example \n\n http://b.example/x ").linkList());
        assertEquals(List.of("https://www.example.com/item?id=1", "https://shop.example:8080/x", "https://cdn.example"),
                form(null, null, "www.example.com/item?id=1\nshop.example:8080/x\n//cdn.example").linkList());
        assertTrue(form(null, null, "example.com/gift\nHTTPS://Shop.Example").validate().isEmpty());
        assertTrue(form(null, null, "javascript:alert(1)").validate().containsKey("links"));
        assertTrue(form(null, null, "mailto:a@example.com").validate().containsKey("links"));
        assertTrue(form(null, null, "http:/broken.example").validate().containsKey("links"));
        assertTrue(form(null, null, "socks").validate().containsKey("links"));
        assertTrue(form(null, null, "has space.example").validate().containsKey("links"));
        assertTrue(form(null, null, "https://ok.example\n".repeat(11)).validate().containsKey("links"));
    }

    /** "Only people I pick" needs at least one valid person; anything else means everyone. */
    @Test
    void visibility() {
        UUID id = UUID.randomUUID();
        assertTrue(GiftForm.of("x", null, null, null, null, "bogus", List.of()).visibleToAll());
        assertTrue(shared(List.of()).validate().containsKey("viewers"));
        assertTrue(shared(List.of("not-a-uuid")).validate().containsKey("viewers"));
        GiftForm ok = shared(List.of(id.toString(), id.toString()));
        assertTrue(ok.validate().isEmpty());
        assertFalse(ok.visibleToAll());
        assertEquals(Set.of(id), ok.viewerIds());
        assertTrue(ok.isViewer(id));
    }

    /** Owner chip text says who can see the gift. */
    @Test
    void visibilityLabel() {
        Gift everyone = new Gift(UUID.randomUUID(), "x", null, List.of(), 1, null, true, Set.of(), false, null, null, false);
        Gift two = new Gift(UUID.randomUUID(), "x", null, List.of(), 1, null, false,
                Set.of(UUID.randomUUID(), UUID.randomUUID()), false, null, null, false);
        Gift none = new Gift(UUID.randomUUID(), "x", null, List.of(), 1, null, false, Set.of(), false, null, null, false);
        assertEquals("Everyone", everyone.visibilityLabel());
        assertEquals("Shared with 2 people", two.visibilityLabel());
        assertEquals("Only you", none.visibilityLabel());
        assertEquals("$5.00", new Gift(UUID.randomUUID(), "x", new BigDecimal("5"), List.of(), 1, null, true,
                Set.of(), false, null, null, false).priceLabel());
    }

    /** Editing prefills from the gift. */
    @Test
    void fromGift() {
        UUID viewer = UUID.randomUUID();
        Gift gift = new Gift(UUID.randomUUID(), "Socks", new BigDecimal("15.00"),
                List.of("https://a.example", "https://b.example"), 3, "Size M", false, Set.of(viewer), false, null, null, false);
        GiftForm f = GiftForm.from(gift);
        assertEquals("15.00", f.price());
        assertEquals("3", f.quantity());
        assertEquals("https://a.example\nhttps://b.example", f.links());
        assertEquals(GiftForm.SELECTED, f.visibility());
        assertTrue(f.isViewer(viewer));
        assertTrue(f.validate().isEmpty());
    }
}
