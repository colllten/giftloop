-- =============================================================================
-- Migration: gift_removal
-- Why: Deleting a gift (or wishlist) cascaded its claims, so claimers lost
--   track of gifts they may already have bought. Owners also must not learn
--   which gifts were claimed, so removal can't behave differently for them.
--   - Owners remove through private.remove_gift / remove_wishlist (direct
--     DELETE is revoked). Unclaimed rows are deleted; claimed ones get
--     removed_at and stay visible to their claimers only.
--   - Every owner path (read, update, GiftViewer, re-creating a wishlist)
--     treats removed rows exactly like deleted ones.
--   - A removed gift is purged once its last claim is released, and a
--     removed wishlist once its last gift is purged.
--   - A claim creator releases the whole claim instead of leaving it, so
--     every claim keeps someone who can manage it.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tables
-- -----------------------------------------------------------------------------

alter table public."Gift" add column removed_at timestamptz;
alter table public."Wishlist" add column removed_at timestamptz;

-- One live wishlist per member per event. Removed shells don't block a new
-- one, and the new one gets a fresh id (reusing the old id would leak).
alter table public."Wishlist" drop constraint "Wishlist_event_id_owner_id_key";
create unique index wishlist_one_live_per_owner
  on public."Wishlist" (event_id, owner_id) where removed_at is null;

-- -----------------------------------------------------------------------------
-- Helpers (removal-aware)
-- -----------------------------------------------------------------------------

-- True if the current user owns the live wishlist.
create or replace function private.is_wishlist_owner(p_wishlist_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."Wishlist"
    where id = p_wishlist_id and owner_id = (select auth.uid()) and removed_at is null
  );
$$;

-- True if the current user owns the live gift.
create or replace function private.is_gift_owner(p_gift_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Gift" g
    join public."Wishlist" w on w.id = g.wishlist_id
    where g.id = p_gift_id and w.owner_id = (select auth.uid())
      and g.removed_at is null and w.removed_at is null
  );
$$;

-- Owner and allowlist paths need a live gift; claim participants keep seeing
-- removed gifts so they know not to buy (or to return) them.
create or replace function private.gift_visible_to(p_gift_id uuid, p_user_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Gift" g
    join public."Wishlist" w   on w.id = g.wishlist_id
    join public."Event" e      on e.id = w.event_id
    join public."GroupUser" gu on gu.group_id = e.group_id and gu.user_id = p_user_id
    where g.id = p_gift_id
      and (
        (g.removed_at is null and (
          w.owner_id = p_user_id
          or g.is_visible_to_all
          or exists (
            select 1 from public."GiftViewer" v
            where v.gift_id = g.id and v.user_id = p_user_id
          )
        ))
        or exists (
          select 1
          from public."Claim" c
          join public."ClaimParticipant" cp on cp.claim_id = c.id
          where c.gift_id = g.id and cp.user_id = p_user_id
        )
      )
  );
$$;

-- Only non-owner members who can see a live gift may claim it.
create or replace function private.can_claim_gift(p_gift_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select private.can_view_gift(p_gift_id) and exists (
    select 1
    from public."Gift" g
    join public."Wishlist" w   on w.id = g.wishlist_id
    join public."Event" e      on e.id = w.event_id
    join public."GroupUser" gu on gu.group_id = e.group_id and gu.user_id = (select auth.uid())
    where g.id = p_gift_id and w.owner_id <> (select auth.uid()) and g.removed_at is null
  );
$$;

-- Raises unless p_user_id is a group member for a live gift, not its wishlist
-- owner, and allowed to see it.
create or replace function private.assert_can_purchase_gift(p_gift_id uuid, p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_owner_id   uuid;
  v_group_id   uuid;
  v_removed_at timestamptz;
begin
  select w.owner_id, e.group_id, g.removed_at
    into v_owner_id, v_group_id, v_removed_at
  from public."Gift" g
  join public."Wishlist" w on w.id = g.wishlist_id
  join public."Event" e on e.id = w.event_id
  where g.id = p_gift_id;

  -- Missing and removed gifts get the same error, so nobody can tell them apart.
  if v_owner_id is null or v_removed_at is not null then
    raise exception 'This gift can no longer be claimed'
      using errcode = 'check_violation';
  end if;

  if v_owner_id = p_user_id then
    raise exception 'Wishlist owners cannot claim or co-purchase their own gifts'
      using errcode = 'check_violation';
  end if;

  if not exists (
    select 1 from public."GroupUser"
    where group_id = v_group_id and user_id = p_user_id
  ) then
    raise exception 'Claim participants must be members of the gift''s group'
      using errcode = 'check_violation';
  end if;

  if not private.gift_visible_to(p_gift_id, p_user_id) then
    raise exception 'Claim participants must be able to see the gift'
      using errcode = 'check_violation';
  end if;
end;
$$;

-- Viewers must be members of the gift's group and not the wishlist owner.
-- Missing or removed gifts are left to RLS (42501), so the error is the same
-- for both; before triggers run ahead of RLS checks.
create or replace function private.validate_gift_viewer()
returns trigger
language plpgsql security definer
set search_path = ''
as $$
declare
  v_owner_id uuid;
  v_group_id uuid;
begin
  select w.owner_id, e.group_id
    into v_owner_id, v_group_id
  from public."Gift" g
  join public."Wishlist" w on w.id = g.wishlist_id
  join public."Event" e on e.id = w.event_id
  where g.id = new.gift_id and g.removed_at is null;

  if v_owner_id is null then
    return new;
  end if;

  if new.user_id = v_owner_id then
    raise exception 'The wishlist owner cannot be a gift viewer'
      using errcode = 'check_violation';
  end if;

  if not exists (
    select 1 from public."GroupUser"
    where group_id = v_group_id and user_id = new.user_id
  ) then
    raise exception 'Gift viewers must be members of the gift''s group'
      using errcode = 'check_violation';
  end if;

  return new;
end;
$$;

-- -----------------------------------------------------------------------------
-- Removal functions (the only way owners delete gifts and wishlists)
-- -----------------------------------------------------------------------------

-- Deletes the caller's gift, or marks it removed if anyone claimed it.
-- Same result and errors either way, so the owner learns nothing.
create function private.remove_gift(p_gift_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not private.is_gift_owner(p_gift_id) then
    raise exception 'Not allowed to remove this gift'
      using errcode = 'insufficient_privilege';
  end if;

  -- Lock the gift so a concurrent claim can't slip in between check and delete.
  perform 1 from public."Gift" where id = p_gift_id for update;

  if exists (select 1 from public."Claim" where gift_id = p_gift_id) then
    update public."Gift" set removed_at = now() where id = p_gift_id;
    delete from public."GiftViewer" where gift_id = p_gift_id;
  else
    delete from public."Gift" where id = p_gift_id;
  end if;
end;
$$;

-- Deletes the caller's wishlist and its unclaimed gifts. Claimed gifts are
-- marked removed and keep a removed wishlist shell as their parent.
create function private.remove_wishlist(p_wishlist_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not private.is_wishlist_owner(p_wishlist_id) then
    raise exception 'Not allowed to remove this wishlist'
      using errcode = 'insufficient_privilege';
  end if;

  perform 1 from public."Wishlist" where id = p_wishlist_id for update;
  perform 1 from public."Gift" where wishlist_id = p_wishlist_id for update;

  delete from public."Gift" g
  where g.wishlist_id = p_wishlist_id
    and not exists (select 1 from public."Claim" c where c.gift_id = g.id);

  update public."Gift" set removed_at = now()
  where wishlist_id = p_wishlist_id and removed_at is null;

  delete from public."GiftViewer" v
  using public."Gift" g
  where v.gift_id = g.id and g.wishlist_id = p_wishlist_id;

  if exists (select 1 from public."Gift" where wishlist_id = p_wishlist_id) then
    update public."Wishlist" set removed_at = now() where id = p_wishlist_id;
  else
    delete from public."Wishlist" where id = p_wishlist_id;
  end if;
end;
$$;

revoke execute on function
  private.remove_gift(uuid),
  private.remove_wishlist(uuid)
from public;
grant execute on function
  private.remove_gift(uuid),
  private.remove_wishlist(uuid)
to authenticated;

-- -----------------------------------------------------------------------------
-- Triggers
-- -----------------------------------------------------------------------------

-- The claim creator can't leave their own claim (they release it instead).
-- Allowed when the claim or the creator's profile is already gone (cascades).
create function private.prevent_claim_creator_leaving()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if exists (
    select 1 from public."Claim"
    where id = old.claim_id and created_by = old.user_id
  ) and exists (
    select 1 from public."Profile" where id = old.user_id
  ) then
    raise exception 'The claim creator releases the claim instead of leaving it'
      using errcode = 'check_violation';
  end if;
  return old;
end;
$$;

create trigger prevent_claim_creator_leaving
  before delete on public."ClaimParticipant"
  for each row execute function private.prevent_claim_creator_leaving();

-- Purges a removed gift once its last claim is gone, then its removed
-- wishlist once that has no gifts left.
create function private.purge_released_removed_gift()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_wishlist_id uuid;
begin
  delete from public."Gift" g
  where g.id = old.gift_id
    and g.removed_at is not null
    and not exists (select 1 from public."Claim" c where c.gift_id = g.id)
  returning g.wishlist_id into v_wishlist_id;

  if v_wishlist_id is not null then
    delete from public."Wishlist" w
    where w.id = v_wishlist_id
      and w.removed_at is not null
      and not exists (select 1 from public."Gift" g where g.wishlist_id = w.id);
  end if;
  return old;
end;
$$;

create trigger purge_released_removed_gift
  after delete on public."Claim"
  for each row execute function private.purge_released_removed_gift();

revoke execute on function
  private.prevent_claim_creator_leaving(),
  private.purge_released_removed_gift()
from public;

-- -----------------------------------------------------------------------------
-- Grants and RLS
-- -----------------------------------------------------------------------------

revoke delete on public."Gift" from authenticated;
revoke delete on public."Wishlist" from authenticated;
drop policy "Gift: delete as owner" on public."Gift";
drop policy "Wishlist: delete own" on public."Wishlist";

-- Owners never see their removed wishlists; members still can (Java hides
-- them from listings) so claimers' "My claims" can show whose gift it was.
drop policy "Wishlist: read as member" on public."Wishlist";
create policy "Wishlist: read as member" on public."Wishlist"
  for select to authenticated
  using (
    private.is_event_member(event_id)
    and (removed_at is null or owner_id <> (select auth.uid()))
  );

-- Live gifts: owner, or members when shared with everyone (inline so
-- INSERT ... RETURNING works). Otherwise the helper decides, which also lets
-- claimers see removed gifts.
drop policy "Gift: read when visible" on public."Gift";
create policy "Gift: read when visible" on public."Gift"
  for select to authenticated
  using (
    (removed_at is null and (
      private.is_wishlist_owner(wishlist_id)
      or (private.is_wishlist_member(wishlist_id) and is_visible_to_all)
    ))
    or (private.is_wishlist_member(wishlist_id) and private.can_view_gift(id))
  );

drop policy "Gift: update as owner" on public."Gift";
create policy "Gift: update as owner" on public."Gift"
  for update to authenticated
  using (removed_at is null and private.is_wishlist_owner(wishlist_id))
  with check (removed_at is null and private.is_wishlist_owner(wishlist_id));
