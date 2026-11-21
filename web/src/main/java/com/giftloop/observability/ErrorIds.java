package com.giftloop.observability;

import java.security.SecureRandom;

/**
 * Short, random reference IDs shown to users on error pages and written to the log,
 * so a report ("Reference: 7KQ2M9XH4C") can be matched to its stack trace.
 */
public final class ErrorIds {

    /** Crockford base32: no I, L, O or U, so IDs read back without confusion. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    /** 10 chars × 5 bits = 50 bits: collisions are negligible at our volume. */
    static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ErrorIds() {
    }

    /**
     * Creates a new reference ID.
     *
     * @return {@value #LENGTH} uppercase Crockford base32 characters
     */
    public static String next() {
        char[] id = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            id[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(id);
    }
}
