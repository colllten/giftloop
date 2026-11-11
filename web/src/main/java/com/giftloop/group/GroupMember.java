package com.giftloop.group;

import com.giftloop.profile.Profile;

import java.util.UUID;

/**
 * A member of a group as shown on the group page.
 *
 * @param userId      auth user id
 * @param username    unique handle
 * @param displayName friendly name, or null if not onboarded
 * @param avatarPath  storage path, or null
 * @param role        role in the group
 * @param me          whether this is the caller
 */
public record GroupMember(UUID userId, String username, String displayName, String avatarPath,
                          GroupRole role, boolean me) {

    /**
     * Name to show: display name, falling back to username.
     *
     * @return non-null name
     */
    public String name() {
        return displayName != null ? displayName : username;
    }

    /**
     * First letter of the name, shown when there is no avatar.
     *
     * @return one uppercase code point
     */
    public String initial() {
        String n = name();
        return n.isEmpty() ? "?" : new String(Character.toChars(Character.toUpperCase(n.codePointAt(0))));
    }

    /**
     * Avatar URL served by {@code AvatarResource}.
     *
     * @return URL path, or null if no avatar
     */
    public String avatarUrl() {
        return Profile.avatarUrl(userId, avatarPath);
    }
}
