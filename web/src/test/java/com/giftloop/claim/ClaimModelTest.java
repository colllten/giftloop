package com.giftloop.claim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Claim form validation and the display rules on claim records. */
class ClaimModelTest {

    private static final UUID ME = UUID.randomUUID();

    private static GiftClaim claim(int quantity, Participant... participants) {
        return bought(quantity, 0, participants);
    }

    private static GiftClaim bought(int quantity, int purchased, Participant... participants) {
        return new GiftClaim(UUID.randomUUID(), quantity, purchased, List.of(participants), false);
    }

    private static Participant me(boolean anonymous) {
        return new Participant(ME, "Me", anonymous, true);
    }

    private static Participant other(String name, boolean anonymous) {
        return new Participant(UUID.randomUUID(), name, anonymous, false);
    }

    /** Quantity must be a whole number from 1 to 999; the checkbox maps to anonymity. */
    @Test
    void formValidation() {
        assertTrue(ClaimForm.of("2", null).validate().isEmpty());
        assertEquals(2, ClaimForm.of(" 2 ", null).quantityValue());
        for (String bad : new String[] {null, "", "0", "-1", "1000", "two", "1.5"}) {
            assertTrue(ClaimForm.of(bad, null).validate().containsKey("quantity"), "rejects " + bad);
        }
        assertTrue(ClaimForm.of("1", "on").anonymous());
        assertTrue(ClaimForm.of("1", "true").anonymous());
        assertFalse(ClaimForm.of("1", null).anonymous());
    }

    /** Bought count is a whole number from 0 to 999; the database caps it at the claim. */
    @Test
    void purchasedFormValidation() {
        assertTrue(PurchasedForm.of("0").validate().isEmpty());
        assertEquals(3, PurchasedForm.of(" 3 ").value());
        for (String bad : new String[] {null, "", "-1", "1000", "one", "1.5"}) {
            assertTrue(PurchasedForm.of(bad).validate().containsKey("purchased"), "rejects " + bad);
        }
    }

    /** Purchase progress reads as text: nothing, some, all, or a single unit bought. */
    @Test
    void purchasedLabels() {
        assertNull(claim(2).purchasedLabel());
        assertFalse(claim(2).isFullyPurchased());
        assertEquals("1 bought", bought(3, 1).purchasedLabel());
        assertEquals("all bought", bought(2, 2).purchasedLabel());
        assertEquals("bought", bought(1, 1).purchasedLabel());
        assertTrue(bought(1, 1).isFullyPurchased());

        GiftClaims claims = new GiftClaims(List.of(bought(2, 1, me(false)), bought(3, 2, other("Bob", false))));
        assertEquals(3, claims.purchasedQuantity());
        assertEquals("You claimed", claims.statusLabel(5), "partly bought is still just claimed");
        assertEquals("You bought", new GiftClaims(List.of(bought(2, 2, me(false)))).statusLabel(5));
        assertEquals("Claimed", new GiftClaims(List.of(bought(1, 1, other("Bob", false)))).statusLabel(1),
                "others' purchases don't change the chip");
    }

    /** Status text never relies on colour: mine, full, partial, or nothing. */
    @Test
    void statusLabels() {
        assertNull(GiftClaims.NONE.statusLabel(2));
        GiftClaims partial = new GiftClaims(List.of(claim(1, other("Bob", false))));
        assertEquals("1 of 3 claimed", partial.statusLabel(3));
        assertEquals(2, partial.remaining(3));
        assertEquals("Claimed", partial.statusLabel(1));
        assertTrue(partial.isFull(1));
        GiftClaims mine = new GiftClaims(List.of(claim(1, me(false)), claim(2, other("Bob", false))));
        assertEquals("You claimed", mine.statusLabel(5));
        assertEquals(3, mine.claimedQuantity());
        assertEquals(1, mine.others().size());
        assertEquals(0, mine.remaining(2), "over-claimed never goes negative");
    }

    /** Hidden participants read as "Someone"; the caller and anonymous ones are labelled. */
    @Test
    void whoLabels() {
        assertEquals("Someone", claim(1).who());
        assertEquals("Bob, Carol (anonymous)", claim(1, other("Bob", false), other("Carol", true)).who());
        GiftClaim mine = claim(1, me(true), other("Bob", false));
        assertTrue(mine.callerIsParticipant());
        assertTrue(mine.callerIsAnonymous());
        assertEquals(ME, mine.myUserId());
        assertEquals("Bob", mine.othersLabel());
        assertEquals("You (anonymous), Bob", mine.who());
    }
}
