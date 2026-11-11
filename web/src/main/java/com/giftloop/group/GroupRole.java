package com.giftloop.group;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * A member's role in a group (database enum {@code group_role}).
 * Permissions are enforced by RLS; the methods here only decide what the UI offers.
 */
public enum GroupRole {
    /** Created the group (or received ownership). Exactly one per group. */
    OWNER,
    /** Manages the join code, renames the group and removes plain members. */
    ADMIN,
    /** Regular member. */
    MEMBER;

    /**
     * Parses the database value.
     *
     * @param value {@code owner}, {@code admin} or {@code member}
     * @return the role
     */
    public static GroupRole fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Value as stored in the database and returned by the API.
     *
     * @return lowercase name
     */
    @JsonValue
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Human label, e.g. "Owner".
     *
     * @return label
     */
    public String label() {
        return name().charAt(0) + dbValue().substring(1);
    }

    /**
     * Whether this role can see/regenerate the join code and rename the group.
     *
     * @return true for owner and admin
     */
    public boolean isAdmin() {
        return this != MEMBER;
    }

    /**
     * Whether someone with this role may remove a member with {@code target}'s role.
     *
     * @param target the other member's role
     * @return true if allowed
     */
    public boolean canRemove(GroupRole target) {
        return switch (this) {
            case OWNER -> target != OWNER;
            case ADMIN -> target == MEMBER;
            case MEMBER -> false;
        };
    }
}
