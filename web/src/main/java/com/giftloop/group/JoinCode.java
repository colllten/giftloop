package com.giftloop.group;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Group join codes: 8 characters without look-alikes (no I, O, 0, 1), shown as {@code ABCD-EFGH}.
 * Mirrors the {@code "GroupJoinCode".code} check constraint.
 */
public final class JoinCode {

    private static final Pattern VALID = Pattern.compile("^[A-HJ-NP-Z2-9]{8}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");

    private JoinCode() {
    }

    /**
     * Uppercases and strips spaces and dashes, so "abcd-efgh" and "ABCD EFGH" both match.
     *
     * @param raw user input
     * @return normalized code, or empty string for null input
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return SEPARATORS.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /**
     * Whether a normalized code has the right shape (not whether it exists).
     *
     * @param normalized output of {@link #normalize(String)}
     * @return true if well-formed
     */
    public static boolean isWellFormed(String normalized) {
        return normalized != null && VALID.matcher(normalized).matches();
    }

    /**
     * Display form with a dash in the middle.
     *
     * @param code stored code
     * @return e.g. {@code ABCD-EFGH}, or the input unchanged if not 8 characters
     */
    public static String format(String code) {
        if (code == null || code.length() != 8) {
            return code;
        }
        return code.substring(0, 4) + "-" + code.substring(4);
    }
}
