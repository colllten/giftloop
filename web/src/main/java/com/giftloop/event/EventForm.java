package com.giftloop.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event fields as submitted by a form, trimmed. Mirrors the {@code "Event"} constraints.
 *
 * @param name    trimmed name, or null if blank
 * @param endDate raw end date ({@code yyyy-MM-dd} from a date input), or null if blank
 * @param type    event type ({@link EventType#STANDARD} when missing or unknown)
 * @param budget  raw budget, or null if blank or the type isn't an exchange
 */
public record EventForm(String name, String endDate, EventType type, String budget) {

    static final int NAME_MAX = 100;
    static final BigDecimal BUDGET_MAX = new BigDecimal("99999999.99");

    /**
     * Builds a standard-event form from raw request values.
     *
     * @param name    raw name
     * @param endDate raw end date
     * @return normalized form
     */
    public static EventForm of(String name, String endDate) {
        return of(name, endDate, null, null);
    }

    /**
     * Builds a form from raw request values. The budget is dropped unless the type is an exchange.
     *
     * @param name    raw name
     * @param endDate raw end date
     * @param type    raw type ({@code standard} or {@code exchange})
     * @param budget  raw budget; a leading {@code $} and thousands commas are allowed
     * @return normalized form
     */
    public static EventForm of(String name, String endDate, String type, String budget) {
        EventType parsed = EventType.fromForm(type);
        return new EventForm(blankToNull(name), blankToNull(endDate), parsed,
                parsed.isExchange() ? blankToNull(budget) : null);
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
        if (endDate != null && parseDate(endDate) == null) {
            errors.put("endDate", "Please enter a date like 2026-12-25.");
        }
        if (budget != null && budgetValue() == null) {
            errors.put("budget", "Please enter an amount like 25 or 25.00.");
        }
        return errors;
    }

    /**
     * Parsed end date. Call after {@link #validate()} passes.
     *
     * @return date, or null if none was given
     */
    public LocalDate endDateValue() {
        return endDate == null ? null : parseDate(endDate);
    }

    /**
     * Parsed budget: non-negative, at most two decimals and {@link #BUDGET_MAX}.
     *
     * @return amount, or null if none was given or it is invalid
     */
    public BigDecimal budgetValue() {
        if (budget == null) {
            return null;
        }
        String cleaned = budget.replace(",", "");
        if (cleaned.startsWith("$")) {
            cleaned = cleaned.substring(1).strip();
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            boolean valid = value.signum() >= 0 && value.scale() <= 2 && value.compareTo(BUDGET_MAX) <= 0;
            return valid ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
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
