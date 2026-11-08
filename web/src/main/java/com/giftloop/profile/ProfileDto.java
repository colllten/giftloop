package com.giftloop.profile;

import java.util.UUID;

/**
 * Public profile of a user.
 *
 * @param id          auth user id
 * @param username    unique handle
 * @param displayName optional friendly name
 */
public record ProfileDto(UUID id, String username, String displayName) {
}
