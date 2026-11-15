-- =============================================================================
-- Migration: off_list_gifts
-- Why: Givers buy things that aren't on the list. Recording them on the
--   recipient's wishlist stops others buying the same thing.
--   - An off-list gift is a Gift row with is_off_list = true, created only by
--     private.add_off_list_gift, which also claims it for the caller. The giver
--     is the claim creator (Claim.created_by stays unreadable, so anonymity
--     works as for any claim). It reuses co-buyers, "bought" and My claims.
--   - The wishlist owner sees it only once claims are revealed to them (same
--     rule as claims). Until then every owner path treats it as missing.
--   - Owners can't create, edit or remove off-list gifts; the claim creator
--     edits the text fields via private.update_off_list_gift.
--   - One claim per off-list gift. Releasing it deletes the gift.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Schema
-- -----------------------------------------------------------------------------

alter table public."Gift"
  add column is_off_list boolean not null default false,
  add constraint gift_off_list_shared_with_all check (not is_off_list or is_visible_to_all);

-- -----------------------------------------------------------------------------
-- Helpers
-- -----------------------------------------------------------------------------

-- True if the wishlist's owner may see claims: the event ended (UTC) or the
-- group shows them early. Internal (no grant).
create function private.claims_revealed_to_owner(p_wishlist_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Wishlist" w
    join public."Event" e  on e.id = w.event_id
    join public."Group" gr on gr.id = e.group_id
    where w.id = p_wishlist_id
      and (gr.is_claims_visible_to_owner or (e.end_date is not null and e.end_date < current_date))
  );
$$;

-- Owner: live gifts, plus off-list ones once revealed. Everyone else: live
-- shared/allowlisted gifts, or any gift they have a claim on.
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
          (w.owner_id = p_user_id
            and (not g.is_off_list or private.claims_revealed_to_owner(w.id)))
          or (w.owner_id is distinct from p_user_id and (
            g.is_visible_to_all
            or exists (
              select 1 from public."GiftViewer" v
              where v.gift_id = g.id and v.user_id = p_user_id
            )
          ))
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

-- Off-list gifts aren't the owner's: no remove, viewers or photos.
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
      and g.removed_at is null and w.removed_at is null and not g.is_off_list
  );
$$;

-- Off-list gifts carry only their giver's claim.
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
    where g.id = p_gift_id and w.owner_id <> (select auth.uid())
      and g.removed_at is null and not g.is_off_list
  );
$$;

-- -----------------------------------------------------------------------------
-- Triggers
-- -----------------------------------------------------------------------------

-- Claim creator: member, not the owner; off-list gifts allow one claim.
create or replace function private.validate_claim()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.assert_can_purchase_gift(new.gift_id, new.created_by);
  if exists (select 1 from public."Gift" where id = new.gift_id and is_off_list)
     and exists (select 1 from public."Claim" where gift_id = new.gift_id and id <> new.id) then
    raise exception 'Off-list gifts have a single claim'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;

-- Purges a removed or off-list gift once its last claim is gone, then its
-- removed wishlist once that has no gifts left.
create or replace function private.purge_released_removed_gift()
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
    and (g.removed_at is not null or g.is_off_list)
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

-- -----------------------------------------------------------------------------
-- Off-list gift functions
-- -----------------------------------------------------------------------------

-- Records a gift the caller is giving that isn't on the list, claimed by them.
-- Missing, removed, own or other-group wishlists all raise the same 42501.
create function private.add_off_list_gift(
  p_wishlist_id uuid, p_name text, p_price numeric, p_links text[], p_notes text)
returns uuid
language plpgsql
volatile security definer
set search_path = ''
as $$
declare
  v_owner_id uuid;
  v_gift_id  uuid;
begin
  -- Share lock: a concurrent remove_wishlist waits for (or blocks) this insert.
  select w.owner_id into v_owner_id
  from public."Wishlist" w
  join public."Event" e      on e.id = w.event_id
  join public."GroupUser" gu on gu.group_id = e.group_id and gu.user_id = (select auth.uid())
  where w.id = p_wishlist_id and w.removed_at is null
  for share of w;

  if v_owner_id is null or v_owner_id = (select auth.uid()) then
    raise exception 'Not allowed to add an off-list gift here'
      using errcode = 'insufficient_privilege';
  end if;

  insert into public."Gift" (wishlist_id, name, price, links, notes, is_off_list)
  values (p_wishlist_id, p_name, p_price, p_links, p_notes, true)
  returning id into v_gift_id;

  -- created_by defaults to auth.uid(); the trigger adds the caller as participant.
  insert into public."Claim" (gift_id) values (v_gift_id);
  return v_gift_id;
end;
$$;

-- Edits an off-list gift's text fields. Its claim creator only (42501).
create function private.update_off_list_gift(
  p_gift_id uuid, p_name text, p_price numeric, p_links text[], p_notes text)
returns void
language plpgsql
volatile security definer
set search_path = ''
as $$
begin
  update public."Gift" g
  set name = p_name, price = p_price, links = p_links, notes = p_notes
  where g.id = p_gift_id and g.is_off_list and g.removed_at is null
    and exists (
      select 1 from public."Claim" c
      where c.gift_id = g.id and c.created_by = (select auth.uid())
    );

  if not found then
    raise exception 'Not allowed to edit this gift'
      using errcode = 'insufficient_privilege';
  end if;
end;
$$;

revoke execute on function
  private.claims_revealed_to_owner(uuid),
  private.add_off_list_gift(uuid, text, numeric, text[], text),
  private.update_off_list_gift(uuid, text, numeric, text[], text)
from public;
grant execute on function
  private.add_off_list_gift(uuid, text, numeric, text[], text),
  private.update_off_list_gift(uuid, text, numeric, text[], text)
to authenticated;

-- -----------------------------------------------------------------------------
-- RLS
-- -----------------------------------------------------------------------------

-- Off-list gifts always go through the helper, which hides them from the
-- owner until reveal (the inline owner/member clauses would show them).
drop policy "Gift: read when visible" on public."Gift";
create policy "Gift: read when visible" on public."Gift"
  for select to authenticated
  using (
    (removed_at is null and not is_off_list and (
      private.is_wishlist_owner(wishlist_id)
      or (private.is_wishlist_member(wishlist_id) and is_visible_to_all)
    ))
    or (private.is_wishlist_member(wishlist_id) and private.can_view_gift(id))
  );

drop policy "Gift: create as owner" on public."Gift";
create policy "Gift: create as owner" on public."Gift"
  for insert to authenticated
  with check (private.is_wishlist_owner(wishlist_id) and not is_off_list);

drop policy "Gift: update as owner" on public."Gift";
create policy "Gift: update as owner" on public."Gift"
  for update to authenticated
  using (removed_at is null and not is_off_list and private.is_wishlist_owner(wishlist_id))
  with check (removed_at is null and not is_off_list and private.is_wishlist_owner(wishlist_id));
