package com.giftloop.event;

/**
 * An admin tried to change an exchange's type after names were drawn ({@code 23514}, DETAIL
 * {@code type_locked}). The edit form shows it as a field error.
 */
public class EventTypeLockedException extends RuntimeException {

    /**
     * Creates the exception.
     */
    public EventTypeLockedException() {
        super("Event type cannot change after names are drawn");
    }
}
