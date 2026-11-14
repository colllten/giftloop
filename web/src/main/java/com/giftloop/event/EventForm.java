package com.giftloop.event;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event fields as submitted by a form, trimmed. Mirrors the {@code "Event"} constraints.
 *
 * @param name    trimmed name, or null if blank
 * @param endDate raw end date ({@code yyyy-MM-dd} from a date input), or null if blank
 */
public record EventForm(String name, String endDate) {

    static final int NAME_MAX = 100;

    /**
     * Builds a form from raw request values.
     *
     * @param name    raw name
     * @param endDate raw end date
     * @return normalized form
     */
    public static EventForm of(String name, String endDate) {
        return new EventForm(blankToNull(name), blankToNull(endDate));
    }

    /**
     * Validates the fields.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null) {
            errors.put("name", "Please give the event a name.");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "Please use " + NAME_MAX + " characters or fewer.");
        }
        if (endDate != null && parse(endDate) == null) {
            errors.put("endDate", "Please enter a date like 2026-12-25.");
        }
        return errors;
    }

    /**
     * Parsed end date. Call after {@link #validate()} passes.
     *
     * @return date, or null if none was given
     */
    public LocalDate endDateValue() {
        return endDate == null ? null : parse(endDate);
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
