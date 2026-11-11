package com.giftloop.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Join code normalization and shape checks.
 */
class JoinCodeTest {

    /** Case, dashes and spaces are ignored. */
    @Test
    void normalize() {
        assertEquals("ABCDEFGH", JoinCode.normalize(" abcd-efgh "));
        assertEquals("ABCDEFGH", JoinCode.normalize("AB CD EF GH"));
        assertEquals("", JoinCode.normalize(null));
    }

    /** Only 8 characters from the unambiguous alphabet are well-formed. */
    @Test
    void wellFormed() {
        assertTrue(JoinCode.isWellFormed("GLVRFAM2"));
        assertFalse(JoinCode.isWellFormed("ABCDEFG"));
        assertFalse(JoinCode.isWellFormed("ABCDEFGHJ"));
        assertFalse(JoinCode.isWellFormed("ABCDEFG0"));
        assertFalse(JoinCode.isWellFormed("ABCDEFGI"));
        assertFalse(JoinCode.isWellFormed("abcdefgh"));
        assertFalse(JoinCode.isWellFormed(null));
    }

    /** Display form has a dash in the middle. */
    @Test
    void format() {
        assertEquals("GLVR-FAM2", JoinCode.format("GLVRFAM2"));
        assertEquals("ABC", JoinCode.format("ABC"));
        assertNull(JoinCode.format(null));
    }
}
