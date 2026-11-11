-- =============================================================================
-- Migration: join_rate_limit
-- Why: Join codes had no attempt limit, so a signed-in user could script
--   guesses. join_group_by_code now allows 5 failed codes per user in a
--   sliding 15-minute window; further attempts (even correct codes) raise
--   PT429 until the oldest failure ages out.
--   - Failures are recorded in "JoinCodeFailure". Raising would roll the record
--     back, so an unknown code now returns null instead of raising P0002.
--   - The guessed codes are not stored.
-- =============================================================================

create table public."JoinCodeFailure" (
  id        bigint generated always as identity primary key,
  user_id   uuid not null references auth.users (id) on delete cascade,
  failed_at timestamptz not null default now()
);

create index joincodefailure_user_failed_at on public."JoinCodeFailure" (user_id, failed_at);

-- Only join_group_by_code (security definer) touches this table. RLS is on with
-- no policies on purpose: deny all to API roles.
revoke all on public."JoinCodeFailure" from anon, authenticated;
alter table public."JoinCodeFailure" enable row level security;

-- Adds the caller to the group with this code as a member and returns the group
-- id. Accepts lowercase and "-"/space separators. Already a member: no change.
-- Unknown code: records a failure and returns null.
-- 5 failures within 15 minutes: raises PT429 with DETAIL = seconds until retry.
create or replace function private.join_group_by_code(p_code text)
returns uuid
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  c_max_failures constant int := 5;
  c_window constant interval := interval '15 minutes';
  v_user_id uuid := (select auth.uid());
  v_group_id uuid;
  v_failures int;
  v_oldest timestamptz;
begin
  if v_user_id is null then
    raise exception 'Not signed in' using errcode = 'insufficient_privilege';
  end if;

  -- Serialize this user's attempts so parallel requests can't overshoot the limit.
  perform pg_advisory_xact_lock(hashtextextended('join_group_by_code:' || v_user_id::text, 0));

  delete from public."JoinCodeFailure"
  where user_id = v_user_id and failed_at <= now() - c_window;

  select count(*), min(failed_at) into v_failures, v_oldest
  from public."JoinCodeFailure"
  where user_id = v_user_id;

  if v_failures >= c_max_failures then
    raise exception 'Too many incorrect join codes'
      using errcode = 'PT429',
            detail = greatest(1, ceil(extract(epoch from (v_oldest + c_window - now()))))::int::text;
  end if;

  select group_id into v_group_id
  from public."GroupJoinCode"
  where code = upper(regexp_replace(coalesce(p_code, ''), '[\s-]', '', 'g'));

  if v_group_id is null then
    insert into public."JoinCodeFailure" (user_id) values (v_user_id);
    return null;
  end if;

  insert into public."GroupUser" (group_id, user_id, role)
  values (v_group_id, v_user_id, 'member')
  on conflict (group_id, user_id) do nothing;

  return v_group_id;
end;
$$;
