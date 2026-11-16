package com.giftloop.event;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * An event in a group's event list.
 *
 * @param id      event id
 * @param name    event name
 * @param endDate last day of the event, or null if open-ended
 * @param type    event type
 */
public record EventSummary(UUID id, String name, LocalDate endDate, EventType type) {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    /**
     * End date for display, e.g. {@code Dec 26, 2026}.
     *
     * @param endDate date, or null
     * @return formatted date, or null
     */
    static String format(LocalDate endDate) {
        return endDate == null ? null : DISPLAY.format(endDate);
    }

    /**
     * End date for display.
     *
     * @return formatted date, or null if open-ended
     */
    public String endDateLabel() {
        return format(endDate);
    }
}
