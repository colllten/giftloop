package com.giftloop.account;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and checks for the account deletion form.
 */
class AccountDeletionFormTest {

    private static final UUID GROUP_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID GROUP_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    /** Explicit picks are kept; "auto" groups are left out. */
    @Test
    void parsesSuccessors() {
        AccountDeletionForm form = AccountDeletionForm.parse(" me ",
                List.of(GROUP_A + ":" + MEMBER, GROUP_B + ":auto"));
        assertEquals("me", form.username());
        assertEquals(Map.of(GROUP_A, MEMBER), form.successors());
        assertTrue(form.chose(GROUP_A, MEMBER));
        assertFalse(form.chose(GROUP_B, MEMBER));
    }

    /** Missing fields parse to an empty form. */
    @Test
    void parsesMissingFields() {
        AccountDeletionForm form = AccountDeletionForm.parse(null, null);
        assertEquals("", form.username());
        assertTrue(form.successors().isEmpty());
    }

    /** Malformed successor values are a bad request. */
    @Test
    void rejectsMalformedSuccessors() {
        assertThrows(BadRequestException.class, () -> AccountDeletionForm.parse("me", List.of("nope")));
        assertThrows(BadRequestException.class, () -> AccountDeletionForm.parse("me", List.of(GROUP_A + ":nope")));
        assertThrows(BadRequestException.class, () -> AccountDeletionForm.parse("me", List.of("x:" + MEMBER)));
    }

    /** The username must match, ignoring case. */
    @Test
    void checksUsername() {
        assertNull(AccountDeletionForm.parse("Grp_Me", null).usernameError("grp_me"));
        assertEquals("Type your username to confirm.", AccountDeletionForm.parse("", null).usernameError("grp_me"));
        assertEquals("That isn't your username. Type grp_me to confirm.",
                AccountDeletionForm.parse("someone", null).usernameError("grp_me"));
    }
}
