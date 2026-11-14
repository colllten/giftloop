package com.giftloop.group;

import com.giftloop.event.EventSummary;

import java.util.List;
import java.util.Map;

/**
 * Data for the group page body fragment.
 *
 * @param group     the group
 * @param events    the group's events
 * @param nameValue value for the rename field (submitted value when it had errors)
 * @param errors    field name → message ({@code name})
 * @param notice    confirmation to show, or null
 */
public record GroupBodyView(GroupDetail group, List<EventSummary> events, String nameValue, Map<String, String> errors, String notice) {

    /**
     * View with no errors or notice.
     *
     * @param group  the group
     * @param events the group's events
     * @return view
     */
    public static GroupBodyView of(GroupDetail group, List<EventSummary> events) {
        return new GroupBodyView(group, events, group.name(), Map.of(), null);
    }

    /**
     * View with a confirmation message.
     *
     * @param group  the group
     * @param events the group's events
     * @param notice message
     * @return view
     */
    public static GroupBodyView withNotice(GroupDetail group, List<EventSummary> events, String notice) {
        return new GroupBodyView(group, events, group.name(), Map.of(), notice);
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
