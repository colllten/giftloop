package com.giftloop.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Normalization and validation of profile form input.
 */
class ProfileFormTest {

    /** Blanks become null and values are trimmed. */
    @Test
    void normalizes() {
        ProfileForm form = ProfileForm.of("  jo_1 ", "  Jo  ", "", " ");
        assertEquals("jo_1", form.username());
        assertEquals("Jo", form.displayName());
        assertNull(form.birthMonth());
        assertNull(form.birthDay());
    }

    /** A fully valid form has no errors; Feb 29 is allowed. */
    @Test
    void validForm() {
        assertTrue(ProfileForm.of("jo_1", "Jo", "2", "29").validate().isEmpty());
    }

    /** Each rule produces a field error. */
    @Test
    void invalidFields() {
        var errors = ProfileForm.of("a b", null, "4", "31").validate();
        assertTrue(errors.containsKey("username"));
        assertTrue(errors.containsKey("displayName"));
        assertTrue(errors.containsKey("birthday"));

        assertTrue(ProfileForm.of("ab", "Jo", null, null).validate().containsKey("username"));
        assertTrue(ProfileForm.of("abc", "x".repeat(65), null, null).validate().containsKey("displayName"));
        assertTrue(ProfileForm.of("abc", "Jo", "13", "1").validate().containsKey("birthday"));
        assertTrue(ProfileForm.of("abc", "Jo", "x", "1").validate().containsKey("birthday"));
        assertTrue(ProfileForm.of("abc", "Jo", null, "1").validate().containsKey("birthday"));
    }
}
