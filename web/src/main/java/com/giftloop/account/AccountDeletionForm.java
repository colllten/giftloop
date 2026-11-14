package com.giftloop.account;

import jakarta.ws.rs.BadRequestException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Submitted account deletion form.
 *
 * @param username   username typed to confirm
 * @param successors chosen new owner per group; groups left on "automatic" are absent
 */
public record AccountDeletionForm(String username, Map<UUID, UUID> successors) {

    /** Value of the "automatic" option in a group's successor select. */
    static final String AUTO = "auto";

    /**
     * Parses the form. Each {@code successor} value is {@code <groupId>:<userId>} or
     * {@code <groupId>:auto}.
     *
     * @param username   typed username (may be null)
     * @param successors successor select values (may be null)
     * @return parsed form
     * @throws BadRequestException if a successor value is malformed
     */
    public static AccountDeletionForm parse(String username, List<String> successors) {
        Map<UUID, UUID> chosen = new LinkedHashMap<>();
        for (String value : successors == null ? List.<String>of() : successors) {
            String[] parts = value == null ? new String[0] : value.split(":", 2);
            if (parts.length != 2) {
                throw new BadRequestException("Malformed successor");
            }
            try {
                UUID group = UUID.fromString(parts[0]);
                if (!AUTO.equals(parts[1])) {
                    chosen.put(group, UUID.fromString(parts[1]));
                }
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Malformed successor");
            }
        }
        return new AccountDeletionForm(username == null ? "" : username.strip(), Map.copyOf(chosen));
    }

    /**
     * Checks the typed username against the caller's (ignoring case, like the database).
     *
     * @param expected the caller's username
     * @return error message, or null if it matches
     */
    public String usernameError(String expected) {
        if (username.toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return username.isEmpty()
                ? "Type your username to confirm."
                : "That isn't your username. Type " + expected + " to confirm.";
    }

    /**
     * Whether a member was chosen as a group's new owner (for re-rendering the form).
     *
     * @param groupId  group id
     * @param memberId member id
     * @return true if selected
     */
    public boolean chose(UUID groupId, UUID memberId) {
        return memberId.equals(successors.get(groupId));
    }
}
