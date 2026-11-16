-- =============================================================================
-- Migration: gift_exchange
-- Why: Secret Santa events. Members opt in (ExchangeParticipant), admins block
--   pairings (ExchangeExclusion, both directions), then an admin draws names
--   with private.draw_exchange. Each giver can read only their own
--   ExchangeAssignment row: nobody else, admins included, can see who gives to
--   whom. The draw runs in the database so no client can pick or read the
--   pairs (ADR 005).
--   - Type and budget are editable by admins until names are drawn.
--   - Switching away from 'exchange' drops participants and exclusions.
--   - Leaving the group or deleting the account removes the participant and,
--     after a draw, their assignments (see DATABASE.md "Known Gaps").
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Schema
-- -----------------------------------------------------------------------------

alter table public."Event"
  add column type public.event_type not null default 'standard',
  add column budget numeric(10,2) check (budget >= 0),
  add column drawn_at timestamptz,
  add constraint event_exchange_fields
    check (type = 'exchange' or (budget is null and drawn_at is null));

create table public."ExchangeParticipant" (
  event_id  uuid not null references public."Event"(id) on delete cascade,
  user_id   uuid not null default auth.uid() references public."Profile"(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (event_id, user_id)
);
create index exchangeparticipant_user_id_idx on public."ExchangeParticipant" (user_id);

-- One row per blocked pair; user_a < user_b so a pair is stored once.
create table public."ExchangeExclusion" (
  event_id   uuid not null,
  user_a     uuid not null,
  user_b     uuid not null,
  created_at timestamptz not null default now(),
  primary key (event_id, user_a, user_b),
  check (user_a < user_b),
  foreign key (event_id, user_a) references public."ExchangeParticipant"(event_id, user_id) on delete cascade,
  foreign key (event_id, user_b) references public."ExchangeParticipant"(event_id, user_id) on delete cascade
);

create table public."ExchangeAssignment" (
  event_id    uuid not null,
  giver_id    uuid not null,
  receiver_id uuid not null,
  primary key (event_id, giver_id),
  unique (event_id, receiver_id),
  check (giver_id <> receiver_id),
  foreign key (event_id, giver_id) references public."ExchangeParticipant"(event_id, user_id) on delete cascade,
  foreign key (event_id, receiver_id) references public."ExchangeParticipant"(event_id, user_id) on delete cascade
);

-- -----------------------------------------------------------------------------
-- Helpers
-- -----------------------------------------------------------------------------

-- True if the current user is an owner/admin of the event's group.
create function private.is_event_admin(p_event_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."Event" e
    where e.id = p_event_id and private.is_group_admin(e.group_id)
  );
$$;

-- True if the event is an exchange whose names haven't been drawn.
create function private.is_exchange_open(p_event_id uuid)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from public."Event"
    where id = p_event_id and type = 'exchange' and drawn_at is null
  );
$$;

-- -----------------------------------------------------------------------------
-- Triggers
-- -----------------------------------------------------------------------------

-- Budget only applies to exchanges; drawn_at is set only by draw functions;
-- the type is fixed once drawn; leaving 'exchange' drops its participants.
create function private.guard_event_exchange_fields()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.type <> 'exchange' then
    new.budget := null;
  end if;

  if tg_op = 'INSERT' then
    new.drawn_at := null;
    return new;
  end if;

  if new.type is distinct from old.type then
    if old.drawn_at is not null then
      raise exception 'The event type cannot change after names are drawn'
        using errcode = '23514', detail = 'type_locked';
    end if;
    if old.type = 'exchange' then
      delete from public."ExchangeParticipant" where event_id = old.id;
    end if;
  end if;
  return new;
end;
$$;

create trigger guard_event_exchange_fields
  before insert or update on public."Event"
  for each row execute function private.guard_event_exchange_fields();

-- Participants must belong to the event's group.
create function private.validate_exchange_participant()
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
    where e.id = new.event_id and gu.user_id = new.user_id
  ) then
    raise exception 'Exchange participants must be group members'
      using errcode = '23514', detail = 'not_member';
  end if;
  return new;
end;
$$;

create trigger validate_exchange_participant
  before insert on public."ExchangeParticipant"
  for each row execute function private.validate_exchange_participant();

-- Leaving a group leaves its exchanges (cascades exclusions and assignments).
create function private.remove_exchange_participant_on_leave()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from public."ExchangeParticipant" p
  using public."Event" e
  where p.event_id = e.id and e.group_id = old.group_id and p.user_id = old.user_id;
  return old;
end;
$$;

create trigger remove_exchange_participant_on_leave
  after delete on public."GroupUser"
  for each row execute function private.remove_exchange_participant_on_leave();

-- -----------------------------------------------------------------------------
-- Draw
-- -----------------------------------------------------------------------------

-- Admin only. Randomized backtracking over a shuffled participant list: each
-- giver gets one receiver, never themselves or an excluded partner, and each
-- participant receives once. Errors (23514, DETAIL): not_exchange,
-- already_drawn, too_few, no_valid_draw.
create function private.draw_exchange(p_event_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_group   uuid;
  v_type    public.event_type;
  v_drawn   timestamptz;
  v_users   uuid[];
  v_n       int;
  v_allowed boolean[];
  v_opts    int[] := '{}'::int[];
  v_pos     int[];
  v_chosen  int[];
  v_used    boolean[];
  v_found   boolean;
  v_steps   int := 0;
  v_i       int;
  v_r       int;
  v_a       int;
  v_b       int;
  v_pair    record;
begin
  select group_id, type, drawn_at into v_group, v_type, v_drawn
  from public."Event" where id = p_event_id
  for update;

  if not found or not private.is_group_admin(v_group) then
    raise exception 'Only group admins can draw names' using errcode = '42501';
  end if;
  if v_type <> 'exchange' then
    raise exception 'Not a gift exchange' using errcode = '23514', detail = 'not_exchange';
  end if;
  if v_drawn is not null then
    raise exception 'Names are already drawn' using errcode = '23514', detail = 'already_drawn';
  end if;

  select array_agg(user_id order by random()) into v_users
  from public."ExchangeParticipant" where event_id = p_event_id;
  v_n := coalesce(array_length(v_users, 1), 0);
  if v_n < 3 then
    raise exception 'At least 3 participants are needed' using errcode = '23514', detail = 'too_few';
  end if;

  -- v_allowed[(giver - 1) * n + receiver], indexes into v_users.
  v_allowed := array_fill(true, array[v_n * v_n]);
  for k in 1..v_n loop
    v_allowed[(k - 1) * v_n + k] := false;
  end loop;
  for v_pair in
    select user_a, user_b from public."ExchangeExclusion" where event_id = p_event_id
  loop
    v_a := array_position(v_users, v_pair.user_a);
    v_b := array_position(v_users, v_pair.user_b);
    v_allowed[(v_a - 1) * v_n + v_b] := false;
    v_allowed[(v_b - 1) * v_n + v_a] := false;
  end loop;

  -- A random receiver order per giver, flattened like v_allowed.
  for k in 1..v_n loop
    v_opts := v_opts || (select array_agg(x order by random()) from generate_series(1, v_n) x);
  end loop;

  v_pos    := array_fill(0, array[v_n]);
  v_chosen := array_fill(0, array[v_n]);
  v_used   := array_fill(false, array[v_n]);
  v_i := 1;
  while v_i between 1 and v_n loop
    v_steps := v_steps + 1;
    if v_steps > 100000 then
      exit;
    end if;
    if v_chosen[v_i] > 0 then
      v_used[v_chosen[v_i]] := false;
      v_chosen[v_i] := 0;
    end if;
    v_found := false;
    while v_pos[v_i] < v_n loop
      v_pos[v_i] := v_pos[v_i] + 1;
      v_r := v_opts[(v_i - 1) * v_n + v_pos[v_i]];
      if not v_used[v_r] and v_allowed[(v_i - 1) * v_n + v_r] then
        v_chosen[v_i] := v_r;
        v_used[v_r] := true;
        v_found := true;
        exit;
      end if;
    end loop;
    if v_found then
      v_i := v_i + 1;
    else
      v_pos[v_i] := 0;
      v_i := v_i - 1;
    end if;
  end loop;

  if v_i <= v_n then
    raise exception 'No draw fits the exclusions' using errcode = '23514', detail = 'no_valid_draw';
  end if;

  insert into public."ExchangeAssignment" (event_id, giver_id, receiver_id)
  select p_event_id, v_users[g], v_users[v_chosen[g]]
  from generate_series(1, v_n) g;

  update public."Event" set drawn_at = now() where id = p_event_id;
  raise log 'Exchange % drawn for % participants', p_event_id, v_n;
end;
$$;

-- Admin only. Deletes the assignments so names can be drawn again.
create function private.reset_exchange_draw(p_event_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_group uuid;
  v_type  public.event_type;
begin
  select group_id, type into v_group, v_type
  from public."Event" where id = p_event_id
  for update;

  if not found or not private.is_group_admin(v_group) then
    raise exception 'Only group admins can undo a draw' using errcode = '42501';
  end if;
  if v_type <> 'exchange' then
    raise exception 'Not a gift exchange' using errcode = '23514', detail = 'not_exchange';
  end if;

  delete from public."ExchangeAssignment" where event_id = p_event_id;
  update public."Event" set drawn_at = null where id = p_event_id;
  raise log 'Exchange % draw reset', p_event_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants and RLS
-- -----------------------------------------------------------------------------

revoke execute on function
  private.is_event_admin(uuid),
  private.is_exchange_open(uuid),
  private.guard_event_exchange_fields(),
  private.validate_exchange_participant(),
  private.remove_exchange_participant_on_leave(),
  private.draw_exchange(uuid),
  private.reset_exchange_draw(uuid)
from public;
grant execute on function
  private.is_event_admin(uuid),
  private.is_exchange_open(uuid),
  private.draw_exchange(uuid),
  private.reset_exchange_draw(uuid)
to authenticated;

grant update (type, budget) on public."Event" to authenticated;

revoke all on public."ExchangeParticipant", public."ExchangeExclusion", public."ExchangeAssignment"
  from anon, authenticated;
grant select, insert, delete on public."ExchangeParticipant" to authenticated;
grant select, insert, delete on public."ExchangeExclusion" to authenticated;
grant select on public."ExchangeAssignment" to authenticated;

alter table public."ExchangeParticipant" enable row level security;
alter table public."ExchangeExclusion" enable row level security;
alter table public."ExchangeAssignment" enable row level security;

create policy "ExchangeParticipant: read as member" on public."ExchangeParticipant"
  for select to authenticated
  using (private.is_event_member(event_id));

create policy "ExchangeParticipant: join before draw" on public."ExchangeParticipant"
  for insert to authenticated
  with check (user_id = (select auth.uid()) and private.is_event_member(event_id)
              and private.is_exchange_open(event_id));

create policy "ExchangeParticipant: leave or remove before draw" on public."ExchangeParticipant"
  for delete to authenticated
  using ((user_id = (select auth.uid()) or private.is_event_admin(event_id))
         and private.is_exchange_open(event_id));

create policy "ExchangeExclusion: read as admin" on public."ExchangeExclusion"
  for select to authenticated
  using (private.is_event_admin(event_id));

create policy "ExchangeExclusion: add as admin before draw" on public."ExchangeExclusion"
  for insert to authenticated
  with check (private.is_event_admin(event_id) and private.is_exchange_open(event_id));

create policy "ExchangeExclusion: remove as admin before draw" on public."ExchangeExclusion"
  for delete to authenticated
  using (private.is_event_admin(event_id) and private.is_exchange_open(event_id));

create policy "ExchangeAssignment: read own as giver" on public."ExchangeAssignment"
  for select to authenticated
  using (giver_id = (select auth.uid()));
