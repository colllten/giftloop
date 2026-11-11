package com.giftloop.group;

import java.util.UUID;

/**
 * One of the caller's groups, for lists.
 *
 * @param id          group id
 * @param name        group name
 * @param myRole      caller's role in the group
 * @param memberCount number of members
 */
public record GroupSummary(UUID id, String name, GroupRole myRole, int memberCount) {
}
