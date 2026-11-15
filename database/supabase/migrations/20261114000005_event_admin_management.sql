-- =============================================================================
-- Migration: event_admin_management
-- Why: The app now lets group owners/admins edit and delete events. Event
--   creators who are plain members lose that right, so only people who manage
--   the group can change an event everyone else's wishlists hang off.
--   private.is_group_admin counts the owner. Deleting still cascades
--   wishlists, gifts and claims (see DATABASE.md "Known Gaps").
-- =============================================================================

drop policy "Event: update as creator or admin" on public."Event";
create policy "Event: update as admin" on public."Event"
  for update to authenticated
  using (private.is_group_admin(group_id))
  with check (private.is_group_admin(group_id));

drop policy "Event: delete as creator or admin" on public."Event";
create policy "Event: delete as admin" on public."Event"
  for delete to authenticated
  using (private.is_group_admin(group_id));
