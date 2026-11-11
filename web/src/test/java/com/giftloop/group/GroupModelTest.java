package com.giftloop.group;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Name validation and the UI permission rules (which mirror the RLS policies).
 */
class GroupModelTest {

    private static GroupMember member(GroupRole role, boolean me) {
        return new GroupMember(UUID.randomUUID(), "u", null, null, role, me);
    }

    private static GroupDetail as(GroupRole role) {
        return new GroupDetail(UUID.randomUUID(), "G", role, List.of(), null);
    }

    /** Names are trimmed and must be 1–100 characters. */
    @Test
    void groupForm() {
        assertEquals("Fam", GroupForm.of("  Fam ").name());
        assertTrue(GroupForm.of("Fam").validate().isEmpty());
        assertTrue(GroupForm.of(" ").validate().containsKey("name"));
        assertTrue(GroupForm.of(null).validate().containsKey("name"));
        assertTrue(GroupForm.of("x".repeat(100)).validate().isEmpty());
        assertTrue(GroupForm.of("x".repeat(101)).validate().containsKey("name"));
    }

    /** Owner removes anyone else; admin removes members only; nobody removes themself here. */
    @Test
    void removalRules() {
        assertTrue(as(GroupRole.OWNER).canRemove(member(GroupRole.ADMIN, false)));
        assertFalse(as(GroupRole.OWNER).canRemove(member(GroupRole.OWNER, true)));
        assertTrue(as(GroupRole.ADMIN).canRemove(member(GroupRole.MEMBER, false)));
        assertFalse(as(GroupRole.ADMIN).canRemove(member(GroupRole.ADMIN, false)));
        assertFalse(as(GroupRole.ADMIN).canRemove(member(GroupRole.OWNER, false)));
        assertFalse(as(GroupRole.MEMBER).canRemove(member(GroupRole.MEMBER, false)));
        assertFalse(as(GroupRole.ADMIN).canRemove(member(GroupRole.MEMBER, true)));
    }

    /** Only the owner manages roles, and never their own. */
    @Test
    void roleRules() {
        assertTrue(as(GroupRole.OWNER).canManageRole(member(GroupRole.MEMBER, false)));
        assertFalse(as(GroupRole.OWNER).canManageRole(member(GroupRole.OWNER, true)));
        assertFalse(as(GroupRole.ADMIN).canManageRole(member(GroupRole.MEMBER, false)));
        assertEquals("Owner", GroupRole.OWNER.label());
        assertEquals(GroupRole.ADMIN, GroupRole.fromDb("admin"));
    }

    /** Members without a display name fall back to username; initial is uppercase. */
    @Test
    void memberNames() {
        GroupMember m = new GroupMember(UUID.randomUUID(), "zed", null, null, GroupRole.MEMBER, false);
        assertEquals("zed", m.name());
        assertEquals("Z", m.initial());
    }
}
