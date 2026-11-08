package com.giftloop.profile;

import java.util.List;
import java.util.Map;

/**
 * Data for the shared profile form fragment (onboarding and profile edit).
 *
 * @param onboarding true on the onboarding page, false when editing
 * @param next       where onboarding continues to
 * @param form       field values to show
 * @param errors     field name → message ({@code username}, {@code displayName}, {@code birthday}, {@code avatar})
 * @param profile    current profile (for avatar preview)
 * @param saved      confirmation message, or null
 */
public record ProfileFormView(boolean onboarding, String next, ProfileForm form, Map<String, String> errors,
                              Profile profile, String saved) {

    /**
     * Month options for the birthday select.
     *
     * @return January–December
     */
    public List<Months.Option> months() {
        return Months.OPTIONS;
    }

    /**
     * Error for a field.
     *
     * @param field field key
     * @return message or null
     */
    public String error(String field) {
        return errors.get(field);
    }
}
