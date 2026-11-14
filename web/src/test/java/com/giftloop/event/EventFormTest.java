package com.giftloop.event;

import org.junit.jupiter.api.Test;

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

    /** Dates display as e.g. "Dec 26, 2026". */
    @Test
    void endDateLabel() {
        assertEquals("Dec 26, 2026", EventSummary.format(LocalDate.of(2026, 12, 26)));
        assertNull(EventSummary.format(null));
    }
}
