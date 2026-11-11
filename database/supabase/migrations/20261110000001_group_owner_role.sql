-- =============================================================================
-- Migration: group_owner_role
-- Why: Groups get a single owner (manages admins, deletes the group, transfers
-- ownership). A new enum value can't be used in the transaction that adds it,
-- so it lives alone here; group_membership uses it.
-- =============================================================================

alter type public.group_role add value if not exists 'owner';
