package com.giftloop.claim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "How many have you bought?" as submitted by a form. Only checks the shape (0 to
 * {@link ClaimForm#QUANTITY_MAX}); the database caps it at the claim's quantity.
 *
 * @param purchased raw count, or null if blank
 */
public record PurchasedForm(String purchased) {

    /**
     * Builds a form from the raw request value.
     *
     * @param purchased raw count
     * @return normalized form
     */
    public static PurchasedForm of(String purchased) {
        return new PurchasedForm(purchased == null || purchased.isBlank() ? null : purchased.strip());
    }

    /**
     * Validates the field.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        Integer p = parse();
        if (p == null || p < 0 || p > ClaimForm.QUANTITY_MAX) {
            errors.put("purchased", "Please enter a number from 0 to " + ClaimForm.QUANTITY_MAX + ".");
        }
        return errors;
    }

    /**
     * Parsed count. Call after {@link #validate()} passes.
     *
     * @return units bought
     */
    public int value() {
        return parse();
    }

    private Integer parse() {
        if (purchased == null) {
            return null;
        }
        try {
            return Integer.valueOf(purchased);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
