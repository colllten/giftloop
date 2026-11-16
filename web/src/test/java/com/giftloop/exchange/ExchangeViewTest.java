package com.giftloop.exchange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exchange view helpers.
 */
class ExchangeViewTest {

    /** Exclusion names display alphabetically whatever order the ids are stored in, keeping ids paired. */
    @Test
    void exclusionNamesAreAlphabetical() {
        UUID alex = UUID.randomUUID();
        UUID blair = UUID.randomUUID();
        ExchangeView.Exclusion forward = ExchangeView.Exclusion.of(alex, "Alex", blair, "Blair");
        ExchangeView.Exclusion reversed = ExchangeView.Exclusion.of(blair, "Blair", alex, "Alex");
        assertEquals(forward, reversed);
        assertEquals("Alex", reversed.nameA());
        assertEquals(alex, reversed.userA());
        assertEquals("alex", ExchangeView.Exclusion.of(blair, "Blair", alex, "alex").nameA());
    }

    /** Drawing needs an admin, no prior draw, and at least three participants. */
    @Test
    void canDraw() {
        List<ExchangeView.Participant> three = List.of(
                new ExchangeView.Participant(UUID.randomUUID(), "A", true),
                new ExchangeView.Participant(UUID.randomUUID(), "B", false),
                new ExchangeView.Participant(UUID.randomUUID(), "C", false));
        UUID event = UUID.randomUUID();
        assertTrue(new ExchangeView(event, false, true, three, null, List.of(), null).canDraw());
        assertFalse(new ExchangeView(event, false, false, three, null, List.of(), null).canDraw());
        assertFalse(new ExchangeView(event, true, true, three, null, List.of(), null).canDraw());
        ExchangeView two = new ExchangeView(event, false, true, three.subList(0, 2), null, List.of(), null);
        assertFalse(two.canDraw());
        assertEquals(1, two.stillNeeded());
        assertTrue(new ExchangeView(event, true, false, three, null, List.of(), null).recipientMissing());
    }
}
