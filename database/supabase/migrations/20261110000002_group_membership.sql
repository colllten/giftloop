-- =============================================================================
-- Migration: group_membership
-- Why: Defines how people get into and manage a group.
--   - Exactly one owner per group (creator). Owner manages admins, deletes the
--     group, and must transfer ownership before leaving.
--   - Admins (incl. owner) see/regenerate the group's join code, rename the
--     group, and remove plain members.
--   - Users join by entering a join code (private.join_group_by_code).
--   - Direct GroupUser inserts are closed: admins could previously add any
--     user id and thereby read that user's Profile/avatar.
-- =============================================================================

create extension if not exists pgcrypto with schema extensions;

-- -----------------------------------------------------------------------------
-- Owner role
-- -----------------------------------------------------------------------------

create unique index groupuser_one_owner on public."GroupUser" (group_id) where role = 'owner';

-- Backfill: prefer the creator if admin, else the earliest admin, else earliest member.
update public."GroupUser" gu
set role = 'owner'
from (
  select distinct on (m.group_id) m.group_id, m.user_id
  from public."GroupUser" m
  join public."Group" g on g.id = m.group_id
  order by m.group_id, (m.role = 'admin') desc, (m.user_id = g.created_by) desc, m.joined_at
) pick
where gu.group_id = pick.group_id
  and gu.user_id = pick.user_id
  and not exists (select 1 from public."GroupUser" o where o.group_id = gu.group_id and o.role = 'owner');

-- Admin checks now include the owner (Group/Event policies rely on this).
create or replace function private.is_group_admin(p_group_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."GroupUser"
    where group_id = p_group_id and user_id = (select auth.uid()) and role in ('admin', 'owner')
  );
$$;

-- True if the current user owns the group.
create function private.is_group_owner(p_group_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."GroupUser"
    where group_id = p_group_id and user_id = (select auth.uid()) and role = 'owner'
  );
$$;

-- Creator becomes owner (replaces add_group_creator_as_admin).
drop trigger on_group_created on public."Group";
drop function private.add_group_creator_as_admin();

create function private.add_group_creator_as_owner()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.created_by is not null then
    insert into public."GroupUser" (group_id, user_id, role)
    values (new.id, new.created_by, 'owner');
  end if;
  return new;
end;
$$;

create trigger on_group_created
  after insert on public."Group"
  for each row execute function private.add_group_creator_as_owner();

-- -----------------------------------------------------------------------------
-- Join codes
-- -----------------------------------------------------------------------------

-- 8 chars, no I/O/0/1 (easy to read aloud). 32-char alphabet keeps byte % 32 unbiased.
create table public."GroupJoinCode" (
  group_id   uuid primary key references public."Group" (id) on delete cascade,
  code       text not null unique check (code ~ '^[A-HJ-NP-Z2-9]{8}$'),
  created_at timestamptz not null default now()
);

-- Random join code (not checked for uniqueness; callers retry on conflict).
create function private.new_join_code()
returns text
language plpgsql volatile
set search_path = ''
as $$
declare
  v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_bytes bytea := extensions.gen_random_bytes(8);
  v_code text := '';
begin
  for i in 0..7 loop
    v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, i) % 32) + 1, 1);
  end loop;
  return v_code;
end;
$$;

-- Sets (insert or replace) the group's join code to a fresh unique value.
create function private.reset_join_code(p_group_id uuid)
returns text
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_code text;
begin
  for attempt in 1..10 loop
    begin
      v_code := private.new_join_code();
      insert into public."GroupJoinCode" (group_id, code)
      values (p_group_id, v_code)
      on conflict (group_id) do update set code = excluded.code, created_at = now();
      return v_code;
    exception when unique_violation then
      null;  -- code collided with another group's; try again
    end;
  end loop;
  raise exception 'Could not generate a unique join code' using errcode = 'unique_violation';
end;
$$;

-- Every new group gets a join code.
create function private.create_group_join_code()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.reset_join_code(new.id);
  return new;
end;
$$;

create trigger on_group_created_join_code
  after insert on public."Group"
  for each row execute function private.create_group_join_code();

select private.reset_join_code(id) from public."Group";

-- Replaces the group's join code; the old one stops working. Admins/owner only.
create function private.regenerate_join_code(p_group_id uuid)
returns text
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  if not private.is_group_admin(p_group_id) then
    raise exception 'Only group admins can regenerate the join code'
      using errcode = 'insufficient_privilege';
  end if;
  return private.reset_join_code(p_group_id);
end;
$$;

-- Adds the caller to the group with this code as a member and returns the group
-- id. Accepts lowercase and "-"/space separators. Already a member: no change.
-- Unknown code: raises no_data_found (P0002).
create function private.join_group_by_code(p_code text)
returns uuid
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
  v_group_id uuid;
begin
  if v_user_id is null then
    raise exception 'Not signed in' using errcode = 'insufficient_privilege';
  end if;

  select group_id into v_group_id
  from public."GroupJoinCode"
  where code = upper(regexp_replace(coalesce(p_code, ''), '[\s-]', '', 'g'));

  if v_group_id is null then
    raise exception 'Join code not found' using errcode = 'no_data_found';
  end if;

  insert into public."GroupUser" (group_id, user_id, role)
  values (v_group_id, v_user_id, 'member')
  on conflict (group_id, user_id) do nothing;

  return v_group_id;
end;
$$;

-- Makes p_new_owner (an existing member) the owner; the caller becomes admin.
create function private.transfer_group_ownership(p_group_id uuid, p_new_owner uuid)
returns void
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
begin
  if not private.is_group_owner(p_group_id) then
    raise exception 'Only the group owner can transfer ownership'
      using errcode = 'insufficient_privilege';
  end if;
  if p_new_owner = v_user_id or not exists (
    select 1 from public."GroupUser" where group_id = p_group_id and user_id = p_new_owner
  ) then
    raise exception 'New owner must be another member of the group'
      using errcode = 'check_violation';
  end if;

  -- Demote first: groupuser_one_owner allows only one owner at a time.
  update public."GroupUser" set role = 'admin'
  where group_id = p_group_id and user_id = v_user_id;
  update public."GroupUser" set role = 'owner'
  where group_id = p_group_id and user_id = p_new_owner;
end;
$$;

revoke execute on function
  private.is_group_owner(uuid),
  private.add_group_creator_as_owner(),
  private.new_join_code(),
  private.reset_join_code(uuid),
  private.create_group_join_code(),
  private.regenerate_join_code(uuid),
  private.join_group_by_code(text),
  private.transfer_group_ownership(uuid, uuid)
from public;

grant execute on function
  private.is_group_owner(uuid),
  private.regenerate_join_code(uuid),
  private.join_group_by_code(text),
  private.transfer_group_ownership(uuid, uuid)
to authenticated;

-- -----------------------------------------------------------------------------
-- Grants and RLS
-- -----------------------------------------------------------------------------

-- New tables inherit Supabase's default grants; start from nothing.
revoke all on public."GroupJoinCode" from anon, authenticated;
grant select on public."GroupJoinCode" to authenticated;
alter table public."GroupJoinCode" enable row level security;

create policy "GroupJoinCode: read as admin" on public."GroupJoinCode"
  for select to authenticated
  using (private.is_group_admin(group_id));

-- Membership is created only by the creator trigger and join_group_by_code.
drop policy "GroupUser: add as admin" on public."GroupUser";
revoke insert on public."GroupUser" from authenticated;

-- Owner changes roles between admin and member; ownership moves only by transfer.
drop policy "GroupUser: update as admin" on public."GroupUser";
create policy "GroupUser: update role as owner" on public."GroupUser"
  for update to authenticated
  using (private.is_group_owner(group_id) and role <> 'owner')
  with check (private.is_group_owner(group_id) and role in ('admin', 'member'));

-- Leave (except owner); owner removes anyone else; admins remove plain members.
drop policy "GroupUser: remove as admin or leave" on public."GroupUser";
create policy "GroupUser: leave or remove" on public."GroupUser"
  for delete to authenticated
  using (
    (user_id = (select auth.uid()) and role <> 'owner')
    or (private.is_group_owner(group_id) and role <> 'owner')
    or (private.is_group_admin(group_id) and role = 'member')
  );

drop policy "Group: delete as admin" on public."Group";
create policy "Group: delete as owner" on public."Group"
  for delete to authenticated
  using (private.is_group_owner(id));
