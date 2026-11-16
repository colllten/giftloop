package com.giftloop.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data for the new-event and edit-event forms.
 *
 * @param groupId    group the event belongs to
 * @param groupName  group name, for the page heading
 * @param eventId    event being edited, or null when creating
 * @param name       name field value
 * @param endDate    end date field value ({@code yyyy-MM-dd})
 * @param type       selected event type
 * @param budget     budget field value
 * @param typeLocked whether the type can no longer change (names are drawn)
 * @param errors     field name → message ({@code name}, {@code endDate}, {@code type}, {@code budget})
 */
public record EventFormView(UUID groupId, String groupName, UUID eventId, String name, String endDate,
                            EventType type, String budget, boolean typeLocked, Map<String, String> errors) {

    /**
     * Empty form for a new event.
     *
     * @param groupId   group id
     * @param groupName group name
     * @return view
     */
    public static EventFormView blank(UUID groupId, String groupName) {
        return new EventFormView(groupId, groupName, null, null, null, EventType.STANDARD, null, false, Map.of());
    }

    /**
     * Whether this form edits an existing event.
     *
     * @return true when editing, false when creating
     */
    public boolean isEdit() {
        return eventId != null;
    }

    /**
     * Types to offer, in display order.
     *
     * @return all event types
     */
    public List<EventType> types() {
        return List.of(EventType.values());
    }

    /**
     * Error for a field.
     *
     * @param field field name
     * @return message, or null
     */
    public String error(String field) {
        return errors.get(field);
    }
}
