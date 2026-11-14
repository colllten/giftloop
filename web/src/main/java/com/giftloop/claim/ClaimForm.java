package com.giftloop.claim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claim fields as submitted by a form. Mirrors {@code "Claim".quantity > 0}; the total claimed may
 * exceed what the owner wants, so there is no cap against the gift's quantity.
 *
 * @param quantity  raw quantity, or null if blank
 * @param anonymous whether to hide the caller's name from people not on the claim
 */
public record ClaimForm(String quantity, boolean anonymous) {

    /** Upper bound for one claim, matching gift quantities. */
    static final int QUANTITY_MAX = 999;

    /**
     * Builds a form from raw request values.
     *
     * @param quantity  raw quantity
     * @param anonymous raw checkbox value ({@code "on"}/{@code "true"} when checked)
     * @return normalized form
     */
    public static ClaimForm of(String quantity, String anonymous) {
        String q = quantity == null || quantity.isBlank() ? null : quantity.strip();
        return new ClaimForm(q, "on".equals(anonymous) || "true".equals(anonymous));
    }

    /**
     * Validates the fields.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        Integer q = parse(quantity);
        if (q == null || q < 1 || q > QUANTITY_MAX) {
            errors.put("quantity", "Please enter a number from 1 to " + QUANTITY_MAX + ".");
        }
        return errors;
    }

    /**
     * Parsed quantity. Call after {@link #validate()} passes.
     *
     * @return quantity
     */
    public int quantityValue() {
        return parse(quantity);
    }

    private static Integer parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
