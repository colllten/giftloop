package com.giftloop.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Email and code normalization.
 */
class AuthFormsTest {

    /** Plausible emails are trimmed and lower-cased. */
    @Test
    void acceptsValidEmails() {
        assertEquals("bob@example.com", AuthForms.normalizeEmail("  Bob@Example.com "));
        assertEquals("a.b+c@mail.example.co.uk", AuthForms.normalizeEmail("a.b+c@mail.example.co.uk"));
    }

    /** Missing parts, spaces and empty domain labels are rejected. */
    @Test
    void rejectsInvalidEmails() {
        assertNull(AuthForms.normalizeEmail(null));
        assertNull(AuthForms.normalizeEmail("bob"));
        assertNull(AuthForms.normalizeEmail("bob@example"));
        assertNull(AuthForms.normalizeEmail("bob@@example.com"));
        assertNull(AuthForms.normalizeEmail("bo b@example.com"));
        assertNull(AuthForms.normalizeEmail("bob@.example.com"));
        assertNull(AuthForms.normalizeEmail("a@" + "b".repeat(250) + ".com"));
    }

    /**
     * CodeQL's pathological input fails fast (no polynomial backtracking). Hits the pattern
     * directly, since {@code normalizeEmail} rejects long input before matching.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void pathologicalEmailFailsFast() {
        String evil = "!@!." + "!.".repeat(100_000) + "@";
        assertFalse(AuthForms.EMAIL.matcher(evil).matches());
    }

    /** Codes must be exactly 6 digits; whitespace is ignored. */
    @Test
    void normalizesCodes() {
        assertEquals("123456", AuthForms.normalizeCode(" 123 456 "));
        assertNull(AuthForms.normalizeCode("12345"));
        assertNull(AuthForms.normalizeCode(null));
    }
}
