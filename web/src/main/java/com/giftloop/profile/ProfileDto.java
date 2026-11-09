package com.giftloop.profile;

import java.util.UUID;

/**
 * Public profile of a user, as returned by the API.
 *
 * @param id          auth user id
 * @param username    unique handle
 * @param displayName friendly name
 * @param birthMonth  1–12, or null
 * @param birthDay    1–31, or null
 * @param avatarUrl   relative URL of the avatar, or null
 * @param onboarded   whether onboarding is complete
 */
public record ProfileDto(UUID id, String username, String displayName, Integer birthMonth, Integer birthDay,
                         String avatarUrl, boolean onboarded) {

    /**
     * Maps a profile to its API shape.
     *
     * @param p profile
     * @return DTO
     */
    public static ProfileDto of(Profile p) {
        return new ProfileDto(p.id(), p.username(), p.displayName(), p.birthMonth(), p.birthDay(),
                p.avatarUrl(), p.onboarded());
    }
}
