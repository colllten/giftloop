-- =============================================================================
-- Migration: event_type_enum
-- Why: Events get a type: 'standard' (everyone keeps a wishlist and claims) or
--   'exchange' (Secret Santa: members opt in and an admin draws names). The
--   enum lives alone so later values can be added the same way group_role's
--   'owner' was; gift_exchange uses it.
-- =============================================================================

create type public.event_type as enum ('standard', 'exchange');
