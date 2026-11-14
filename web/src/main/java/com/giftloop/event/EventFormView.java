package com.giftloop.event;

import java.util.Map;
import java.util.UUID;

/**
 * Data for the new-event form.
 *
 * @param groupId   group the event is created in
 * @param groupName group name, for the page heading
 * @param name      name field value
 * @param endDate   end date field value ({@code yyyy-MM-dd})
 * @param errors    field name → message ({@code name}, {@code endDate})
 */
public record EventFormView(UUID groupId, String groupName, String name, String endDate,
                            Map<String, String> errors) {

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
