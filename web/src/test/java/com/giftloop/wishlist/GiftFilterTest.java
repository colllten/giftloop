package com.giftloop.wishlist;

import com.giftloop.claim.GiftClaim;
import com.giftloop.claim.GiftClaims;
import com.giftloop.claim.Participant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Claim filters: parsing and which gifts each one keeps. */
class GiftFilterTest {

    private static Gift gift(int wanted, GiftClaims claims, boolean removed) {
        return new Gift(UUID.randomUUID(), "x", null, List.of(), wanted, null, true, Set.of(), removed, claims);
    }

    private static GiftClaims claimed(int quantity, boolean mine) {
        Participant p = new Participant(UUID.randomUUID(), "P", false, mine);
        return new GiftClaims(List.of(new GiftClaim(UUID.randomUUID(), quantity, List.of(p), mine)));
    }

    /** Unknown values fall back to All; the page URL's show= carries over. */
    @Test
    void parsing() {
        assertEquals(GiftFilter.NEEDED, GiftFilter.of("needed"));
        assertEquals(GiftFilter.FULL, GiftFilter.of("claimed"));
        assertEquals(GiftFilter.ALL, GiftFilter.of("bogus"));
        assertEquals(GiftFilter.ALL, GiftFilter.of(null));
        assertEquals(GiftFilter.MINE, GiftFilter.fromUrl("http://localhost:8080/wishlists/1?x=1&show=mine"));
        assertEquals(GiftFilter.ALL, GiftFilter.fromUrl("http://localhost:8080/wishlists/1"));
        assertEquals(GiftFilter.ALL, GiftFilter.fromUrl("not a url ::"));
        assertEquals(GiftFilter.ALL, GiftFilter.fromUrl(null));
        assertFalse(GiftFilter.choices(true).contains(GiftFilter.MINE), "owners can't claim");
    }

    /** Partly claimed counts as still needed; removed gifts only show under All and Mine. */
    @Test
    void matching() {
        Gift open = gift(1, GiftClaims.NONE, false);
        Gift partial = gift(3, claimed(1, false), false);
        Gift full = gift(1, claimed(1, false), false);
        Gift mine = gift(2, claimed(2, true), false);
        Gift removedMine = gift(1, claimed(1, true), true);

        assertTrue(GiftFilter.NEEDED.matches(open));
        assertTrue(GiftFilter.NEEDED.matches(partial));
        assertFalse(GiftFilter.NEEDED.matches(full));
        assertFalse(GiftFilter.NEEDED.matches(removedMine));
        assertTrue(GiftFilter.FULL.matches(full));
        assertTrue(GiftFilter.FULL.matches(mine));
        assertFalse(GiftFilter.FULL.matches(partial));
        assertFalse(GiftFilter.FULL.matches(removedMine));
        assertTrue(GiftFilter.MINE.matches(mine));
        assertTrue(GiftFilter.MINE.matches(removedMine));
        assertFalse(GiftFilter.MINE.matches(full));
    }

    /** With claims hidden (owner before reveal), only All matches. */
    @Test
    void hiddenClaimsOnlyMatchAll() {
        Gift hidden = gift(1, null, false);
        assertTrue(GiftFilter.ALL.matches(hidden));
        for (GiftFilter f : List.of(GiftFilter.NEEDED, GiftFilter.FULL, GiftFilter.MINE)) {
            assertFalse(f.matches(hidden), f.name());
        }
    }
}
