package com.giftloop.group;

import java.util.Map;

/**
 * Group name as submitted by a form, trimmed. Mirrors the {@code "Group".name} constraint.
 *
 * @param name trimmed name, or null if blank
 */
public record GroupForm(String name) {

    static final int NAME_MAX = 100;

    /**
     * Builds a form from the raw request value.
     *
     * @param name raw name
     * @return normalized form
     */
    public static GroupForm of(String name) {
        return new GroupForm(name == null || name.isBlank() ? null : name.strip());
    }

    /**
     * Validates the name.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        if (name == null) {
            return Map.of("name", "Please give your group a name.");
        }
        if (name.length() > NAME_MAX) {
            return Map.of("name", "Please use " + NAME_MAX + " characters or fewer.");
        }
        return Map.of();
    }
}
