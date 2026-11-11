package com.giftloop.group;

import java.util.List;
import java.util.UUID;

/**
 * A group as returned by the API.
 *
 * @param id       group id
 * @param name     group name
 * @param myRole   caller's role
 * @param members  members, owner first
 * @param joinCode join code (no dash) for owner/admins, null for members
 */
public record GroupDto(UUID id, String name, GroupRole myRole, List<Member> members, String joinCode) {

    /**
     * A member as returned by the API.
     *
     * @param userId      auth user id
     * @param username    unique handle
     * @param displayName friendly name
     * @param avatarUrl   relative avatar URL, or null
     * @param role        role in the group
     */
    public record Member(UUID userId, String username, String displayName, String avatarUrl, GroupRole role) {
    }

    /**
     * Maps a group to its API shape.
     *
     * @param g group
     * @return DTO
     */
    public static GroupDto of(GroupDetail g) {
        List<Member> members = g.members().stream()
                .map(m -> new Member(m.userId(), m.username(), m.displayName(), m.avatarUrl(), m.role()))
                .toList();
        return new GroupDto(g.id(), g.name(), g.myRole(), members, g.joinCode());
    }
}
