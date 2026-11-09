-- =============================================================================
-- Migration: init_rls_policies
-- Why: RLS is the enforcement layer (ADR 001): Quarkus queries as the
-- `authenticated` role with the caller's JWT. Key rules:
--   - Everything is scoped to group membership.
--   - Wishlist owners cannot see claims on their own gifts until the day after
--     Event.end_date, unless Group.is_claims_visible_to_owner is true.
--   - Anonymous ClaimParticipant rows are visible only to participants of the
--     same claim. Claim.created_by is never readable via the API.
-- Helpers are security definer to avoid recursive RLS between tables.
-- `anon` gets nothing.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Access helpers
-- -----------------------------------------------------------------------------

-- True if the current user belongs to the group.
create function private.is_group_member(p_group_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."GroupUser"
    where group_id = p_group_id and user_id = (select auth.uid())
  );
$$;

-- True if the current user is an admin of the group.
create function private.is_group_admin(p_group_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."GroupUser"
    where group_id = p_group_id and user_id = (select auth.uid()) and role = 'admin'
  );
$$;

-- True if the current user shares at least one group with p_user_id.
create function private.shares_group_with(p_user_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."GroupUser" mine
    join public."GroupUser" theirs on theirs.group_id = mine.group_id
    where mine.user_id = (select auth.uid()) and theirs.user_id = p_user_id
  );
$$;

-- True if the current user belongs to the event's group.
create function private.is_event_member(p_event_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Event" e
    join public."GroupUser" gu on gu.group_id = e.group_id
    where e.id = p_event_id and gu.user_id = (select auth.uid())
  );
$$;

-- True if the current user belongs to the wishlist's group.
create function private.is_wishlist_member(p_wishlist_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Wishlist" w
    join public."Event" e on e.id = w.event_id
    join public."GroupUser" gu on gu.group_id = e.group_id
    where w.id = p_wishlist_id and gu.user_id = (select auth.uid())
  );
$$;

-- True if the current user owns the wishlist.
create function private.is_wishlist_owner(p_wishlist_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."Wishlist"
    where id = p_wishlist_id and owner_id = (select auth.uid())
  );
$$;

-- True if the current user may see claims on the gift: a group member who is
-- not the owner, or the owner once the event has ended / group reveal is on.
create function private.can_view_claims(p_gift_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
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

-- True if the current user is a group member for the gift and not its owner.
create function private.can_claim_gift(p_gift_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public."Gift" g
    join public."Wishlist" w   on w.id = g.wishlist_id
    join public."Event" e      on e.id = w.event_id
    join public."GroupUser" gu on gu.group_id = e.group_id and gu.user_id = (select auth.uid())
    where g.id = p_gift_id and w.owner_id <> (select auth.uid())
  );
$$;

-- True if the current user may see claims on the claim's gift.
create function private.can_view_claim(p_claim_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select private.can_view_claims((select gift_id from public."Claim" where id = p_claim_id));
$$;

-- True if the current user created the claim.
create function private.is_claim_creator(p_claim_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."Claim"
    where id = p_claim_id and created_by = (select auth.uid())
  );
$$;

-- True if the current user participates in the claim.
create function private.is_claim_participant(p_claim_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."ClaimParticipant"
    where claim_id = p_claim_id and user_id = (select auth.uid())
  );
$$;

revoke execute on all functions in schema private from public;
grant execute on function
  private.is_group_member(uuid),
  private.is_group_admin(uuid),
  private.shares_group_with(uuid),
  private.is_event_member(uuid),
  private.is_wishlist_member(uuid),
  private.is_wishlist_owner(uuid),
  private.can_view_claims(uuid),
  private.can_claim_gift(uuid),
  private.can_view_claim(uuid),
  private.is_claim_creator(uuid),
  private.is_claim_participant(uuid)
to authenticated;

-- -----------------------------------------------------------------------------
-- Grants (explicit; nothing is auto-exposed, anon gets nothing)
-- -----------------------------------------------------------------------------

revoke all on all tables in schema public from anon, authenticated;

grant select on public."Profile" to authenticated;
grant update (username, display_name) on public."Profile" to authenticated;

grant select, insert, delete on public."Group" to authenticated;
grant update (name, is_claims_visible_to_owner) on public."Group" to authenticated;

grant select, insert, delete on public."GroupUser" to authenticated;
grant update (role) on public."GroupUser" to authenticated;

grant select, insert, delete on public."Event" to authenticated;
grant update (name, end_date) on public."Event" to authenticated;

grant select, insert, delete on public."Wishlist" to authenticated;

grant select, insert, delete on public."Gift" to authenticated;
grant update (name, price, links, quantity, notes) on public."Gift" to authenticated;

-- created_by deliberately not selectable: it would de-anonymize claim creators.
grant select (id, gift_id, quantity, created_at) on public."Claim" to authenticated;
grant insert, delete on public."Claim" to authenticated;
grant update (quantity) on public."Claim" to authenticated;

grant select, insert, delete on public."ClaimParticipant" to authenticated;
grant update (is_anonymous) on public."ClaimParticipant" to authenticated;

-- -----------------------------------------------------------------------------
-- RLS
-- -----------------------------------------------------------------------------

alter table public."Profile"          enable row level security;
alter table public."Group"            enable row level security;
alter table public."GroupUser"        enable row level security;
alter table public."Event"            enable row level security;
alter table public."Wishlist"         enable row level security;
alter table public."Gift"             enable row level security;
alter table public."Claim"            enable row level security;
alter table public."ClaimParticipant" enable row level security;

-- Profile: self and co-members. Inserted by signup trigger only.
create policy "Profile: read self and co-members" on public."Profile"
  for select to authenticated
  using (id = (select auth.uid()) or private.shares_group_with(id));

create policy "Profile: update self" on public."Profile"
  for update to authenticated
  using (id = (select auth.uid()))
  with check (id = (select auth.uid()));

-- Group: members read; admins manage. Creator clause lets INSERT ... RETURNING
-- succeed before the creator's GroupUser row exists.
create policy "Group: read as member" on public."Group"
  for select to authenticated
  using (private.is_group_member(id) or created_by = (select auth.uid()));

create policy "Group: create as self" on public."Group"
  for insert to authenticated
  with check (created_by = (select auth.uid()));

create policy "Group: update as admin" on public."Group"
  for update to authenticated
  using (private.is_group_admin(id))
  with check (private.is_group_admin(id));

create policy "Group: delete as admin" on public."Group"
  for delete to authenticated
  using (private.is_group_admin(id));

-- GroupUser: members read; admins manage; anyone may leave.
create policy "GroupUser: read as member" on public."GroupUser"
  for select to authenticated
  using (private.is_group_member(group_id));

create policy "GroupUser: add as admin" on public."GroupUser"
  for insert to authenticated
  with check (private.is_group_admin(group_id));

create policy "GroupUser: update as admin" on public."GroupUser"
  for update to authenticated
  using (private.is_group_admin(group_id))
  with check (private.is_group_admin(group_id));

create policy "GroupUser: remove as admin or leave" on public."GroupUser"
  for delete to authenticated
  using (private.is_group_admin(group_id) or user_id = (select auth.uid()));

-- Event: members read and create; creator or admin manage.
create policy "Event: read as member" on public."Event"
  for select to authenticated
  using (private.is_group_member(group_id));

create policy "Event: create as member" on public."Event"
  for insert to authenticated
  with check (private.is_group_member(group_id) and created_by = (select auth.uid()));

create policy "Event: update as creator or admin" on public."Event"
  for update to authenticated
  using (created_by = (select auth.uid()) or private.is_group_admin(group_id))
  with check (created_by = (select auth.uid()) or private.is_group_admin(group_id));

create policy "Event: delete as creator or admin" on public."Event"
  for delete to authenticated
  using (created_by = (select auth.uid()) or private.is_group_admin(group_id));

-- Wishlist: members read; owner creates/deletes their own.
create policy "Wishlist: read as member" on public."Wishlist"
  for select to authenticated
  using (private.is_event_member(event_id));

create policy "Wishlist: create own" on public."Wishlist"
  for insert to authenticated
  with check (owner_id = (select auth.uid()) and private.is_event_member(event_id));

create policy "Wishlist: delete own" on public."Wishlist"
  for delete to authenticated
  using (owner_id = (select auth.uid()));

-- Gift: members read; wishlist owner writes.
create policy "Gift: read as member" on public."Gift"
  for select to authenticated
  using (private.is_wishlist_member(wishlist_id));

create policy "Gift: create as owner" on public."Gift"
  for insert to authenticated
  with check (private.is_wishlist_owner(wishlist_id));

create policy "Gift: update as owner" on public."Gift"
  for update to authenticated
  using (private.is_wishlist_owner(wishlist_id))
  with check (private.is_wishlist_owner(wishlist_id));

create policy "Gift: delete as owner" on public."Gift"
  for delete to authenticated
  using (private.is_wishlist_owner(wishlist_id));

-- Claim: hidden from owner until reveal; non-owner members create; creator manages.
create policy "Claim: read when visible" on public."Claim"
  for select to authenticated
  using (private.can_view_claims(gift_id));

create policy "Claim: create as non-owner member" on public."Claim"
  for insert to authenticated
  with check (created_by = (select auth.uid()) and private.can_claim_gift(gift_id));

create policy "Claim: update as creator" on public."Claim"
  for update to authenticated
  using (created_by = (select auth.uid()))
  with check (created_by = (select auth.uid()));

create policy "Claim: delete as creator" on public."Claim"
  for delete to authenticated
  using (created_by = (select auth.uid()));

-- ClaimParticipant: anonymous rows only visible to fellow participants.
create policy "ClaimParticipant: read when visible" on public."ClaimParticipant"
  for select to authenticated
  using (
    private.can_view_claim(claim_id)
    and (not is_anonymous or private.is_claim_participant(claim_id))
  );

create policy "ClaimParticipant: add as claim creator" on public."ClaimParticipant"
  for insert to authenticated
  with check (private.is_claim_creator(claim_id));

create policy "ClaimParticipant: update as creator or self" on public."ClaimParticipant"
  for update to authenticated
  using (private.is_claim_creator(claim_id) or user_id = (select auth.uid()))
  with check (private.is_claim_creator(claim_id) or user_id = (select auth.uid()));

create policy "ClaimParticipant: remove as creator or leave" on public."ClaimParticipant"
  for delete to authenticated
  using (private.is_claim_creator(claim_id) or user_id = (select auth.uid()));

-- -----------------------------------------------------------------------------
-- Views
-- -----------------------------------------------------------------------------

-- Per-gift claim totals. security_invoker applies Claim RLS, so owners see
-- nothing before reveal and anonymous claims count without exposing identity.
create view public."GiftClaimSummary"
with (security_invoker = true)
as
select
  gift_id,
  count(*)::integer      as claim_count,
  sum(quantity)::integer as claimed_quantity
from public."Claim"
group by gift_id;

revoke all on public."GiftClaimSummary" from anon, authenticated;
grant select on public."GiftClaimSummary" to authenticated;
