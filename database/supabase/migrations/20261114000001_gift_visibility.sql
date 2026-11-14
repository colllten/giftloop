-- =============================================================================
-- Migration: gift_visibility
-- Why: Wishlist owners need to choose who in the group can see each gift.
--   A gift is either visible to every group member (Gift.is_visible_to_all,
--   the default) or only to members listed in "GiftViewer".
--   - Only the owner can read or edit a gift's viewer list.
--   - Anyone with a claim on a gift keeps seeing it after the owner narrows
--     the list; blocking the change would reveal the claim to the owner.
--   - Hidden gifts cannot be claimed, and their claims stay invisible.
--   - Leaving a group drops the user's viewer rows, so rejoining does not
--     silently restore access.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tables
-- -----------------------------------------------------------------------------

alter table public."Gift"
  add column is_visible_to_all boolean not null default true;

-- Allowlist for gifts with is_visible_to_all = false.
create table public."GiftViewer" (
  gift_id uuid not null references public."Gift" (id) on delete cascade,
  user_id uuid not null references public."Profile" (id) on delete cascade,
  primary key (gift_id, user_id)
);

create index giftviewer_user_id on public."GiftViewer" (user_id);

-- -----------------------------------------------------------------------------
-- Helpers
-- -----------------------------------------------------------------------------

-- True if the current user owns the gift's wishlist.
create function private.is_gift_owner(p_gift_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Gift" g
    join public."Wishlist" w on w.id = g.wishlist_id
    where g.id = p_gift_id and w.owner_id = (select auth.uid())
  );
$$;

-- True if p_user_id is a member of the gift's group and is its owner, is
-- allowed by is_visible_to_all / GiftViewer, or takes part in a claim on it.
-- Internal: takes an arbitrary user, so it is not granted to API roles.
create function private.gift_visible_to(p_gift_id uuid, p_user_id uuid)
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
        w.owner_id = p_user_id
        or g.is_visible_to_all
        or exists (
          select 1 from public."GiftViewer" v
          where v.gift_id = g.id and v.user_id = p_user_id
        )
        or exists (
          select 1
          from public."Claim" c
          join public."ClaimParticipant" cp on cp.claim_id = c.id
          where c.gift_id = g.id and cp.user_id = p_user_id
        )
      )
  );
$$;

-- True if the current user may see the gift.
create function private.can_view_gift(p_gift_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select private.gift_visible_to(p_gift_id, (select auth.uid()));
$$;

-- Claims on a gift are visible only to those who can see the gift (plus the
-- existing owner-reveal rule).
create or replace function private.can_view_claims(p_gift_id uuid)
returns boolean
language sql stable security definer
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
        w.owner_id <> (select auth.uid())
        or gr.is_claims_visible_to_owner
        or (e.end_date is not null and e.end_date < current_date)
      )
  );
$$;

-- Only non-owner members who can see the gift may claim it.
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
  );
$$;

-- Raises unless p_user_id is a group member for the gift, not its wishlist
-- owner, and allowed to see it. The visibility check stops a claimer from
-- adding a co-participant the owner excluded (which would grant visibility).
create or replace function private.assert_can_purchase_gift(p_gift_id uuid, p_user_id uuid)
returns void
language plpgsql
security definer
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
  where g.id = p_gift_id;

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

revoke execute on function
  private.is_gift_owner(uuid),
  private.gift_visible_to(uuid, uuid),
  private.can_view_gift(uuid)
from public;
grant execute on function
  private.is_gift_owner(uuid),
  private.can_view_gift(uuid)
to authenticated;

-- -----------------------------------------------------------------------------
-- Triggers
-- -----------------------------------------------------------------------------

-- Viewers must be members of the gift's group and not the wishlist owner.
create function private.validate_gift_viewer()
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
  where g.id = new.gift_id;

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

create trigger validate_gift_viewer
  before insert or update on public."GiftViewer"
  for each row execute function private.validate_gift_viewer();

-- Drops a departing member's viewer rows for gifts in that group.
create function private.remove_gift_viewers_on_leave()
returns trigger
language plpgsql security definer
set search_path = ''
as $$
begin
  delete from public."GiftViewer" v
  using public."Gift" g
  join public."Wishlist" w on w.id = g.wishlist_id
  join public."Event" e on e.id = w.event_id
  where v.gift_id = g.id
    and v.user_id = old.user_id
    and e.group_id = old.group_id;
  return old;
end;
$$;

create trigger remove_gift_viewers_on_leave
  after delete on public."GroupUser"
  for each row execute function private.remove_gift_viewers_on_leave();

revoke execute on function
  private.validate_gift_viewer(),
  private.remove_gift_viewers_on_leave()
from public;

-- -----------------------------------------------------------------------------
-- Grants and RLS
-- -----------------------------------------------------------------------------

grant update (is_visible_to_all) on public."Gift" to authenticated;

-- New tables inherit Supabase's default grants; start from nothing.
revoke all on public."GiftViewer" from anon, authenticated;
grant select, insert, delete on public."GiftViewer" to authenticated;
alter table public."GiftViewer" enable row level security;

-- Gift: owner always; members when shared with everyone; otherwise per helper.
-- Owner and is_visible_to_all are checked inline so INSERT ... RETURNING works
-- (a helper querying "Gift" cannot see the row being inserted).
drop policy "Gift: read as member" on public."Gift";
create policy "Gift: read when visible" on public."Gift"
  for select to authenticated
  using (
    private.is_wishlist_owner(wishlist_id)
    or (private.is_wishlist_member(wishlist_id)
        and (is_visible_to_all or private.can_view_gift(id)))
  );

-- GiftViewer: the viewer list is private to the gift owner.
create policy "GiftViewer: read as owner" on public."GiftViewer"
  for select to authenticated
  using (private.is_gift_owner(gift_id));

create policy "GiftViewer: add as owner" on public."GiftViewer"
  for insert to authenticated
  with check (private.is_gift_owner(gift_id));

create policy "GiftViewer: remove as owner" on public."GiftViewer"
  for delete to authenticated
  using (private.is_gift_owner(gift_id));
