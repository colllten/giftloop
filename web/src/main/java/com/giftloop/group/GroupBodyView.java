package com.giftloop.group;

import java.util.Map;

/**
 * Data for the group page body fragment.
 *
 * @param group     the group
 * @param nameValue value for the rename field (submitted value when it had errors)
 * @param errors    field name → message ({@code name})
 * @param notice    confirmation to show, or null
 */
public record GroupBodyView(GroupDetail group, String nameValue, Map<String, String> errors, String notice) {

    /**
     * View with no errors or notice.
     *
     * @param group the group
     * @return view
     */
    public static GroupBodyView of(GroupDetail group) {
        return new GroupBodyView(group, group.name(), Map.of(), null);
    }

    /**
     * View with a confirmation message.
     *
     * @param group  the group
     * @param notice message
     * @return view
     */
    public static GroupBodyView withNotice(GroupDetail group, String notice) {
        return new GroupBodyView(group, group.name(), Map.of(), notice);
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
