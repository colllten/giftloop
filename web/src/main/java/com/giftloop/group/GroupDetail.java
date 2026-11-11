package com.giftloop.group;

import java.util.List;
import java.util.UUID;

/**
 * A group as seen by one of its members.
 *
 * @param id       group id
 * @param name     group name
 * @param myRole   caller's role
 * @param members  members, owner first, then admins, then by name
 * @param joinCode join code, or null when the caller may not see it (plain members)
 */
public record GroupDetail(UUID id, String name, GroupRole myRole, List<GroupMember> members, String joinCode) {

    /**
     * Join code for display, e.g. {@code ABCD-EFGH}.
     *
     * @return formatted code, or null
     */
    public String formattedJoinCode() {
        return JoinCode.format(joinCode);
    }

    /**
     * Whether the caller owns the group.
     *
     * @return true for the owner
     */
    public boolean callerIsOwner() {
        return myRole == GroupRole.OWNER;
    }

    /**
     * Whether the caller may remove this member.
     *
     * @param member a member of this group
     * @return true if allowed (never for the caller themself; leaving is separate)
     */
    public boolean canRemove(GroupMember member) {
        return !member.me() && myRole.canRemove(member.role());
    }

    /**
     * Whether the caller may change this member's role or hand them ownership.
     *
     * @param member a member of this group
     * @return true if the caller is owner and the member is not
     */
    public boolean canManageRole(GroupMember member) {
        return myRole == GroupRole.OWNER && member.role() != GroupRole.OWNER;
    }

    /**
     * Whether any per-member action is available, so the UI can skip empty menus.
     *
     * @param member a member of this group
     * @return true if the caller can act on this member
     */
    public boolean hasActions(GroupMember member) {
        return canRemove(member) || canManageRole(member);
    }
}
