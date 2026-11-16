package com.giftloop.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event form validation (mirrors the {@code "Event"} constraints).
 */
class EventFormTest {

    /** Names are trimmed and must be 1–100 characters. */
    @Test
    void name() {
        assertEquals("Xmas", EventForm.of("  Xmas ", null).name());
        assertTrue(EventForm.of("Xmas", null).validate().isEmpty());
        assertTrue(EventForm.of(" ", null).validate().containsKey("name"));
        assertTrue(EventForm.of("x".repeat(100), null).validate().isEmpty());
        assertTrue(EventForm.of("x".repeat(101), null).validate().containsKey("name"));
    }

    /** End date is optional and must be an ISO date. */
    @Test
    void endDate() {
        assertNull(EventForm.of("Xmas", "  ").endDateValue());
        assertEquals(LocalDate.of(2026, 12, 26), EventForm.of("Xmas", "2026-12-26").endDateValue());
        assertTrue(EventForm.of("Xmas", "Dec 26").validate().containsKey("endDate"));
        assertTrue(EventForm.of("Xmas", "2026-02-30").validate().containsKey("endDate"));
    }

    /** Type defaults to standard and unknown values fall back to it. */
    @Test
    void type() {
        assertEquals(EventType.STANDARD, EventForm.of("Xmas", null).type());
        assertEquals(EventType.STANDARD, EventForm.of("Xmas", null, "bogus", null).type());
        assertEquals(EventType.EXCHANGE, EventForm.of("Xmas", null, " exchange ", null).type());
        assertEquals(EventType.EXCHANGE, EventType.fromDb("exchange"));
        assertEquals("exchange", EventType.EXCHANGE.dbValue());
    }

    /** Budget is optional, non-negative with at most two decimals, and only kept for exchanges. */
    @Test
    void budget() {
        assertNull(EventForm.of("Xmas", null, "standard", "25").budget());
        assertNull(EventForm.of("Xmas", null, "exchange", "  ").budgetValue());
        assertEquals(new BigDecimal("25"), EventForm.of("Xmas", null, "exchange", "$25").budgetValue());
        assertEquals(new BigDecimal("1000.50"), EventForm.of("Xmas", null, "exchange", "1,000.50").budgetValue());
        assertTrue(EventForm.of("Xmas", null, "exchange", "25.99").validate().isEmpty());
        assertTrue(EventForm.of("Xmas", null, "exchange", "-5").validate().containsKey("budget"));
        assertTrue(EventForm.of("Xmas", null, "exchange", "2.999").validate().containsKey("budget"));
        assertTrue(EventForm.of("Xmas", null, "exchange", "lots").validate().containsKey("budget"));
        assertTrue(EventForm.of("Xmas", null, "exchange", "100000000").validate().containsKey("budget"));
    }

    /** Dates display as e.g. "Dec 26, 2026". */
    @Test
    void endDateLabel() {
        assertEquals("Dec 26, 2026", EventSummary.format(LocalDate.of(2026, 12, 26)));
        assertNull(EventSummary.format(null));
    }
}
