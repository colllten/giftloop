-- =============================================================================
-- Migration: init_core_tables
-- Why: First version of the GiftLoop data model.
--   Group -> Event -> Wishlist (one per user per event) -> Gift -> Claim
--   Claim -> ClaimParticipant (co-purchasers, each optionally anonymous)
-- Integrity rules that must hold regardless of caller (incl. service role) are
-- enforced here with constraints and triggers. Access control lives in the
-- RLS migration that follows.
-- Note: claimed quantity may intentionally exceed Gift.quantity (no cap).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tables
-- -----------------------------------------------------------------------------

-- Public profile for each auth user. Created automatically on signup.
create table public."Profile" (
  id           uuid primary key references auth.users (id) on delete cascade,
  username     extensions.citext not null unique
               check (username ~ '^[A-Za-z0-9_]{3,32}$'),
  display_name text check (char_length(display_name) between 1 and 64),
  created_at   timestamptz not null default now()
);

create table public."Group" (
  id                         uuid primary key default gen_random_uuid(),
  name                       text not null check (char_length(name) between 1 and 100),
  -- When true, wishlist owners can see claims on their own lists before the event ends.
  is_claims_visible_to_owner boolean not null default false,
  created_by                 uuid default auth.uid() references public."Profile" (id) on delete set null,
  created_at                 timestamptz not null default now()
);
create index on public."Group" (created_by);

create table public."GroupUser" (
  group_id  uuid not null references public."Group" (id) on delete cascade,
  user_id   uuid not null references public."Profile" (id) on delete cascade,
  role      public.group_role not null default 'member',
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);
create index on public."GroupUser" (user_id);

create table public."Event" (
  id         uuid primary key default gen_random_uuid(),
  group_id   uuid not null references public."Group" (id) on delete cascade,
  name       text not null check (char_length(name) between 1 and 100),
  -- Claims are revealed to owners the day after end_date. Null = never auto-reveal.
  end_date   date,
  created_by uuid default auth.uid() references public."Profile" (id) on delete set null,
  created_at timestamptz not null default now()
);
create index on public."Event" (group_id);
create index on public."Event" (created_by);

create table public."Wishlist" (
  id         uuid primary key default gen_random_uuid(),
  event_id   uuid not null references public."Event" (id) on delete cascade,
  owner_id   uuid not null default auth.uid() references public."Profile" (id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (event_id, owner_id)
);
create index on public."Wishlist" (owner_id);

create table public."Gift" (
  id          uuid primary key default gen_random_uuid(),
  wishlist_id uuid not null references public."Wishlist" (id) on delete cascade,
  name        text not null check (char_length(name) between 1 and 200),
  price       numeric(10, 2) check (price >= 0),
  links       text[] check (cardinality(links) <= 10),
  quantity    integer not null default 1 check (quantity > 0),
  notes       text check (char_length(notes) <= 1000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index on public."Gift" (wishlist_id);

create table public."Claim" (
  id         uuid primary key default gen_random_uuid(),
  gift_id    uuid not null references public."Gift" (id) on delete cascade,
  -- Hidden from API callers (column grant); identity is exposed via ClaimParticipant.
  created_by uuid not null default auth.uid() references public."Profile" (id) on delete cascade,
  quantity   integer not null default 1 check (quantity > 0),
  created_at timestamptz not null default now()
);
create index on public."Claim" (gift_id);
create index on public."Claim" (created_by);

create table public."ClaimParticipant" (
  claim_id     uuid not null references public."Claim" (id) on delete cascade,
  user_id      uuid not null references public."Profile" (id) on delete cascade,
  is_anonymous boolean not null default false,
  primary key (claim_id, user_id)
);
create index on public."ClaimParticipant" (user_id);

-- -----------------------------------------------------------------------------
-- Trigger functions (security definer: must see rows regardless of caller RLS)
-- -----------------------------------------------------------------------------

-- Creates a Profile for each new auth user. Username comes from signup metadata
-- (`username`), else is derived from the email local part plus an id suffix.
create function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_base text;
begin
  v_base := left(regexp_replace(lower(split_part(coalesce(new.email, ''), '@', 1)), '[^a-z0-9_]', '', 'g'), 20);
  if v_base = '' then
    v_base := 'user';
  end if;

  insert into public."Profile" (id, username, display_name)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'username',
             v_base || '_' || left(replace(new.id::text, '-', ''), 8)),
    new.raw_user_meta_data ->> 'display_name'
  );
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

-- Makes the creator of a Group its first admin.
create function private.add_group_creator_as_admin()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.created_by is not null then
    insert into public."GroupUser" (group_id, user_id, role)
    values (new.id, new.created_by, 'admin');
  end if;
  return new;
end;
$$;

create trigger on_group_created
  after insert on public."Group"
  for each row execute function private.add_group_creator_as_admin();

-- A Wishlist owner must be a member of the event's group.
create function private.validate_wishlist_owner()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
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

create trigger validate_wishlist_owner
  before insert or update of event_id, owner_id on public."Wishlist"
  for each row execute function private.validate_wishlist_owner();

-- Raises unless p_user_id is a group member for the gift and not its wishlist owner.
-- Shared by Claim and ClaimParticipant validation.
create function private.assert_can_purchase_gift(p_gift_id uuid, p_user_id uuid)
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
end;
$$;

-- Validates the claim creator (member, not the owner).
create function private.validate_claim()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.assert_can_purchase_gift(new.gift_id, new.created_by);
  return new;
end;
$$;

create trigger validate_claim
  before insert or update of gift_id, created_by on public."Claim"
  for each row execute function private.validate_claim();

-- Adds the claim creator as the first (non-anonymous) participant.
create function private.add_claim_creator_as_participant()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public."ClaimParticipant" (claim_id, user_id)
  values (new.id, new.created_by)
  on conflict do nothing;
  return new;
end;
$$;

create trigger on_claim_created
  after insert on public."Claim"
  for each row execute function private.add_claim_creator_as_participant();

-- Validates each participant (member, not the owner).
create function private.validate_claim_participant()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.assert_can_purchase_gift(
    (select gift_id from public."Claim" where id = new.claim_id),
    new.user_id
  );
  return new;
end;
$$;

create trigger validate_claim_participant
  before insert or update of claim_id, user_id on public."ClaimParticipant"
  for each row execute function private.validate_claim_participant();

create trigger set_gift_updated_at
  before update on public."Gift"
  for each row execute function private.set_updated_at();

revoke execute on all functions in schema private from public;
