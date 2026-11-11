package com.giftloop.profile;

import java.util.UUID;

/**
 * A user's profile as stored in {@code "Profile"}.
 *
 * @param id          auth user id
 * @param username    unique handle
 * @param displayName friendly name (required once onboarded)
 * @param birthMonth  1–12, or null
 * @param birthDay    1–31, or null
 * @param avatarPath  storage path {@code <id>/<file>}, or null
 * @param onboarded   whether onboarding is complete
 */
public record Profile(UUID id, String username, String displayName, Integer birthMonth, Integer birthDay,
                      String avatarPath, boolean onboarded) {

    /**
     * Avatar URL served by {@code AvatarResource}, versioned by file name for caching.
     *
     * @return URL path, or null if no avatar
     */
    public String avatarUrl() {
        return avatarUrl(id, avatarPath);
    }

    /**
     * Avatar URL for any user, versioned by file name for caching.
     *
     * @param userId     auth user id
     * @param avatarPath storage path {@code <id>/<file>}, or null
     * @return URL path, or null if no avatar
     */
    public static String avatarUrl(UUID userId, String avatarPath) {
        if (avatarPath == null) {
            return null;
        }
        String file = avatarPath.substring(avatarPath.indexOf('/') + 1);
        return "/avatars/" + userId + "?v=" + file;
    }

    /**
     * Birthday like "March 14".
     *
     * @return formatted birthday, or null if unset
     */
    public String birthdayLabel() {
        if (birthMonth == null || birthDay == null) {
            return null;
        }
        return Months.name(birthMonth) + " " + birthDay;
    }
}
