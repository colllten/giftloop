-- =============================================================================
-- Migration: account_deletion
-- Why: Users can delete their account (private.delete_my_account). Deleting an
-- auth user by any route (app, Studio, Admin API) must not hurt other members:
--   - Groups they own pass to the oldest admin, else the oldest member; a
--     group with no other members is deleted. The app lets the owner pick
--     first via transfer_group_ownership.
--   - Claims they started with co-buyers pass to the earliest co-buyer; solo
--     claims are released.
--   - Their wishlists are removed like private.remove_wishlist: claimed gifts
--     stay as removed shells (owner_id null) so claimers keep seeing them.
-- A before-delete trigger on Profile does this, since Profile cascades from
-- auth.users. Removed shells can now be ownerless, so owner comparisons below
-- use `is distinct from` (a null `<>` would hide them from claimers).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Schema
-- -----------------------------------------------------------------------------

alter table public."Wishlist"
  alter column owner_id drop not null,
  drop constraint "Wishlist_owner_id_fkey",
  add constraint "Wishlist_owner_id_fkey"
    foreign key (owner_id) references public."Profile" (id) on delete set null,
  add constraint wishlist_ownerless_only_removed
    check (owner_id is not null or removed_at is not null);

-- Orders co-buyers when a claim's creator deletes their account.
alter table public."ClaimParticipant"
  add column joined_at timestamptz not null default now();

update public."ClaimParticipant" cp
set joined_at = c.created_at
from public."Claim" c
where c.id = cp.claim_id;

-- -----------------------------------------------------------------------------
-- Ownerless removed wishlists
-- -----------------------------------------------------------------------------

-- The FK's set null fires this trigger; an ownerless (removed) row is fine.
create or replace function private.validate_wishlist_owner()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.owner_id is null then
    return new;
  end if;
  if not exists (
    select 1
    from public."Event" e
    join public."GroupUser" gu on gu.group_id = e.group_id
    where e.id = new.event_id and gu.user_id = new.owner_id
  ) then
    raise exception 'Wishlist owner must be a member of the event''s group'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;

create or replace function private.can_view_claims(p_gift_id uuid)
returns boolean
language sql
stable security definer
set search_path = ''
as $$
  select private.can_view_gift(p_gift_id) and exists (
    select 1
    from public."Gift" g
    join public."Wishlist" w   on w.id = g.wishlist_id
    join public."Event" e      on e.id = w.event_id
    join public."Group" gr     on gr.id = e.group_id
    join public."GroupUser" gu on gu.group_id = gr.id and gu.user_id = (select auth.uid())
    where g.id = p_gift_id
      and (
        w.owner_id is distinct from (select auth.uid())
        or gr.is_claims_visible_to_owner
        or (e.end_date is not null and e.end_date < current_date)
      )
  );
$$;

drop policy "Wishlist: read as member" on public."Wishlist";
create policy "Wishlist: read as member" on public."Wishlist"
  for select to authenticated
  using (
    private.is_event_member(event_id)
    and (removed_at is null or owner_id is distinct from (select auth.uid()))
  );

-- -----------------------------------------------------------------------------
-- Group succession
-- -----------------------------------------------------------------------------

-- Who inherits the group if its owner leaves without choosing: the oldest
-- admin, else the oldest member. Null when the owner is alone.
create function private.pick_group_successor(p_group_id uuid)
returns uuid
language sql stable security definer
set search_path = ''
as $$
  select user_id
  from public."GroupUser"
  where group_id = p_group_id and role <> 'owner'
  order by (role = 'admin') desc, joined_at, user_id
  limit 1;
$$;

-- pick_group_successor for the owner's deletion preview; null for anyone else.
create function private.group_successor(p_group_id uuid)
returns uuid
language sql stable security definer
set search_path = ''
as $$
  select case when private.is_group_owner(p_group_id)
              then private.pick_group_successor(p_group_id) end;
$$;

-- -----------------------------------------------------------------------------
-- Wishlist removal without the caller check
-- -----------------------------------------------------------------------------

-- Deletes unclaimed gifts, marks claimed ones removed, and keeps the wishlist
-- as a removed shell only if gifts remain. Callers check permission.
create function private.remove_wishlist_unchecked(p_wishlist_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
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

create or replace function private.remove_wishlist(p_wishlist_id uuid)
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
  perform private.remove_wishlist_unchecked(p_wishlist_id);
end;
$$;

-- -----------------------------------------------------------------------------
-- Account deletion
-- -----------------------------------------------------------------------------

-- Hands off what other members rely on before the profile's cascades run.
create function private.handle_profile_deleted()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_group_id  uuid;
  v_successor uuid;
  v_claim_id  uuid;
  v_wishlist  uuid;
begin
  -- 1. Owned groups: hand off, or delete when nobody else is in them.
  for v_group_id in
    select group_id from public."GroupUser" where user_id = old.id and role = 'owner'
  loop
    v_successor := private.pick_group_successor(v_group_id);
    if v_successor is null then
      delete from public."Group" where id = v_group_id;
    else
      -- Demote first: groupuser_one_owner allows only one owner at a time.
      update public."GroupUser" set role = 'admin'
      where group_id = v_group_id and user_id = old.id;
      update public."GroupUser" set role = 'owner'
      where group_id = v_group_id and user_id = v_successor;
      raise log 'Account % deleted: group % passed to %', old.id, v_group_id, v_successor;
    end if;
  end loop;

  -- 2. Claims they started: pass to the earliest co-buyer, else release.
  for v_claim_id in
    select id from public."Claim" where created_by = old.id
  loop
    select user_id into v_successor
    from public."ClaimParticipant"
    where claim_id = v_claim_id and user_id <> old.id
    order by joined_at, user_id
    limit 1;

    if v_successor is null then
      delete from public."Claim" where id = v_claim_id;
    else
      update public."Claim" set created_by = v_successor where id = v_claim_id;
    end if;
  end loop;

  -- 3. Live wishlists: removed like the owner would (shells keep owner_id via
  --    set null once the profile is gone).
  for v_wishlist in
    select id from public."Wishlist" where owner_id = old.id and removed_at is null
  loop
    perform private.remove_wishlist_unchecked(v_wishlist);
  end loop;

  return old;
end;
$$;

create trigger handle_profile_deleted
  before delete on public."Profile"
  for each row execute function private.handle_profile_deleted();

-- Deletes the caller's account after they confirm their username. The
-- Profile trigger above hands off groups, claims and wishlists.
create function private.delete_my_account(p_username text)
returns void
language plpgsql
volatile security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
begin
  if v_user_id is null then
    raise exception 'Not signed in' using errcode = 'insufficient_privilege';
  end if;

  if not exists (
    select 1 from public."Profile"
    where id = v_user_id
      and username operator(extensions.=) coalesce(p_username, '')::extensions.citext
  ) then
    raise exception 'Username does not match' using errcode = 'check_violation';
  end if;

  delete from auth.users where id = v_user_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants
-- -----------------------------------------------------------------------------

revoke execute on function
  private.pick_group_successor(uuid),
  private.group_successor(uuid),
  private.remove_wishlist_unchecked(uuid),
  private.handle_profile_deleted(),
  private.delete_my_account(text)
from public;

grant execute on function
  private.group_successor(uuid),
  private.delete_my_account(text)
to authenticated;
