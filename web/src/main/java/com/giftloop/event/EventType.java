package com.giftloop.event;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Kind of event (database enum {@code event_type}). Chosen when creating an event; admins may
 * change it until names are drawn.
 */
public enum EventType {
    /** Everyone keeps a wishlist and anyone can claim gifts. */
    STANDARD("Standard",
            "Everyone makes a wishlist and anyone can claim gifts. Wishlist owners see claims after the event."),
    /** Secret Santa: members opt in and an admin draws names. */
    EXCHANGE("Secret Santa / Gift Exchange",
            "Members opt in, then an admin draws names. Each person secretly buys for one other person.");

    private final String label;
    private final String description;

    EventType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /**
     * Parses the database value.
     *
     * @param value {@code standard} or {@code exchange}
     * @return the type
     */
    public static EventType fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Parses a submitted form value, falling back to {@link #STANDARD} when missing or unknown.
     *
     * @param value raw form value, or null
     * @return the type
     */
    public static EventType fromForm(String value) {
        if (value != null) {
            for (EventType type : values()) {
                if (type.dbValue().equals(value.strip())) {
                    return type;
                }
            }
        }
        return STANDARD;
    }

    /**
     * Value as stored in the database and submitted by forms.
     *
     * @return lowercase name
     */
    @JsonValue
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Human label, e.g. "Secret Santa / Gift Exchange".
     *
     * @return label
     */
    public String label() {
        return label;
    }

    /**
     * One-sentence explanation shown when choosing a type.
     *
     * @return description
     */
    public String description() {
        return description;
    }

    /**
     * Whether this is a Secret Santa exchange.
     *
     * @return true for {@link #EXCHANGE}
     */
    public boolean isExchange() {
        return this == EXCHANGE;
    }
}
