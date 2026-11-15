package com.giftloop.wishlist;

import com.giftloop.claim.GiftClaim;
import com.giftloop.claim.GiftClaims;
import com.giftloop.claim.Participant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Off-list gift form rules, giver edit rights, and how the wishlist body splits gifts. */
class OffListFormTest {

    private static Gift gift(boolean offList, boolean removed, GiftClaims claims) {
        return new Gift(UUID.randomUUID(), "x", null, List.of(), 1, null, true, Set.of(), removed, claims, null,
                offList);
    }

    private static GiftClaims claim(boolean mine, boolean creator) {
        Participant p = new Participant(UUID.randomUUID(), "P", false, mine);
        return new GiftClaims(List.of(new GiftClaim(UUID.randomUUID(), 1, 0, List.of(p), creator)));
    }

    /** Uses the gift field rules; checkboxes parse like the claim form. */
    @Test
    void validatesLikeAGift() {
        OffListForm ok = OffListForm.of(" Scarf ", "$1,000", "example.com/a", " ", "on", null);
        assertTrue(ok.validate().isEmpty());
        assertEquals("Scarf", ok.name());
        assertEquals(new BigDecimal("1000"), ok.priceValue());
        assertEquals(List.of("https://example.com/a"), ok.linkList());
        assertEquals(null, ok.notes());
        assertTrue(ok.anonymous());
        assertFalse(ok.bought());

        OffListForm bad = OffListForm.of("", "-1", "javascript:alert(1)", null, null, "true");
        assertTrue(bad.bought());
        assertEquals(Set.of("name", "price", "links"), bad.validate().keySet());
        assertTrue(OffListForm.empty().bought());
    }

    /** Only the giver (claim creator) of a live off-list gift may edit it. */
    @Test
    void editableByGiver() {
        assertTrue(gift(true, false, claim(true, true)).editableByGiver());
        assertFalse(gift(true, false, claim(true, false)).editableByGiver(), "co-buyer");
        assertFalse(gift(true, false, claim(false, false)).editableByGiver(), "bystander");
        assertFalse(gift(true, true, claim(true, true)).editableByGiver(), "removed");
        assertFalse(gift(false, false, claim(true, true)).editableByGiver(), "regular gift");
        assertFalse(gift(true, false, null).editableByGiver(), "claims hidden");
    }

    /** Off-list gifts list separately, follow the filter, and don't count as the owner's list. */
    @Test
    void bodySplitsOffListGifts() {
        Gift offList = gift(true, false, claim(true, true));
        WishlistDetail w = new WishlistDetail(UUID.randomUUID(), UUID.randomUUID(), "E", UUID.randomUUID(), "G",
                "Jo", false, List.of(offList), List.of(), false, null, List.of());
        WishlistBodyView all = WishlistBodyView.of(w, null);
        assertFalse(all.hasListGifts());
        assertEquals(List.of(), all.shownGifts());
        assertEquals(List.of(offList), all.shownOffListGifts());
        assertFalse(all.nothingShown());
        assertTrue(all.offListForm() != null && all.addForm() == null);

        WishlistBodyView needed = WishlistBodyView.of(w, null, GiftFilter.NEEDED);
        assertEquals(List.of(), needed.shownOffListGifts(), "always covered");
        assertTrue(needed.nothingShown());
        assertEquals(List.of(offList), WishlistBodyView.of(w, null, GiftFilter.MINE).shownOffListGifts());
    }
}
