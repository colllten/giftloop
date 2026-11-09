package com.giftloop.profile;

import java.time.Month;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Editable profile fields as submitted by a form, normalized (trimmed; blanks → null).
 * Validation mirrors the database constraints so users get friendly messages first.
 *
 * @param username    required, 3–32 letters/numbers/underscores
 * @param displayName required, 1–64 characters
 * @param birthMonth  optional, 1–12 (requires birthDay)
 * @param birthDay    optional, valid for the month (requires birthMonth)
 */
public record ProfileForm(String username, String displayName, Integer birthMonth, Integer birthDay) {

    static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    static final int DISPLAY_NAME_MAX = 64;

    /**
     * Builds a form from raw request values.
     *
     * @param username    raw username
     * @param displayName raw display name
     * @param birthMonth  raw month ("" or 1–12)
     * @param birthDay    raw day ("" or 1–31)
     * @return normalized form; unparsable numbers become out-of-range values that fail validation
     */
    public static ProfileForm of(String username, String displayName, String birthMonth, String birthDay) {
        return new ProfileForm(blankToNull(username), blankToNull(displayName),
                parseOrInvalid(birthMonth), parseOrInvalid(birthDay));
    }

    /**
     * Form pre-filled from an existing profile.
     *
     * @param profile current profile
     * @return form
     */
    public static ProfileForm from(Profile profile) {
        return new ProfileForm(profile.username(), profile.displayName(), profile.birthMonth(), profile.birthDay());
    }

    /**
     * Validates all fields.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        String usernameError = usernameError(username);
        if (usernameError != null) {
            errors.put("username", usernameError);
        }
        if (displayName == null) {
            errors.put("displayName", "Please enter the name your group will see.");
        } else if (displayName.length() > DISPLAY_NAME_MAX) {
            errors.put("displayName", "Please use " + DISPLAY_NAME_MAX + " characters or fewer.");
        }
        String birthdayError = birthdayError(birthMonth, birthDay);
        if (birthdayError != null) {
            errors.put("birthday", birthdayError);
        }
        return errors;
    }

    /**
     * Checks username format.
     *
     * @param username normalized username
     * @return message, or null if valid
     */
    public static String usernameError(String username) {
        if (username == null) {
            return "Please choose a username.";
        }
        if (!USERNAME.matcher(username).matches()) {
            return "Use 3 to 32 letters, numbers or underscores (_), with no spaces.";
        }
        return null;
    }

    /**
     * Checks birthday month/day (both or neither; Feb 29 allowed).
     *
     * @param month 1–12 or null
     * @param day   1–31 or null
     * @return message, or null if valid
     */
    static String birthdayError(Integer month, Integer day) {
        if (month == null && day == null) {
            return null;
        }
        if (month == null || day == null) {
            return "Please choose both a month and a day, or leave both empty.";
        }
        if (month < 1 || month > 12 || day < 1 || day > Month.of(month).maxLength()) {
            return "That date doesn't exist. Please check the month and day.";
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Integer parseOrInvalid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
