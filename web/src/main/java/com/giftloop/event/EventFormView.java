package com.giftloop.event;

import java.util.Map;
import java.util.UUID;

/**
 * Data for the new-event and edit-event forms.
 *
 * @param groupId   group the event belongs to
 * @param groupName group name, for the page heading
 * @param eventId   event being edited, or null when creating
 * @param name      name field value
 * @param endDate   end date field value ({@code yyyy-MM-dd})
 * @param errors    field name → message ({@code name}, {@code endDate})
 */
public record EventFormView(UUID groupId, String groupName, UUID eventId, String name, String endDate,
                            Map<String, String> errors) {

    /**
     * Whether this form edits an existing event.
     *
     * @return true when editing, false when creating
     */
    public boolean isEdit() {
        return eventId != null;
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
