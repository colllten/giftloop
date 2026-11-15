-- =============================================================================
-- Migration: autofill_rate_limit
-- Why: Gift auto-fill fetches arbitrary pages on the user's behalf. Its limit
--   was 20 per minute held in app memory, which resets on restart and
--   multiplies across instances. record_link_preview now allows 20 previews
--   per user in a sliding 1-hour window, stored in the DB.
--   - Every attempt counts (successful or not); blocked attempts are not
--     recorded. Raises PT429 with DETAIL = seconds until retry.
--   - The links themselves are not stored.
-- =============================================================================

create table public."LinkPreviewAttempt" (
  id           bigint generated always as identity primary key,
  user_id      uuid not null references auth.users (id) on delete cascade,
  attempted_at timestamptz not null default now()
);

create index linkpreviewattempt_user_attempted_at on public."LinkPreviewAttempt" (user_id, attempted_at);

-- Only record_link_preview (security definer) touches this table. RLS is on
-- with no policies on purpose: deny all to API roles.
revoke all on public."LinkPreviewAttempt" from anon, authenticated;
alter table public."LinkPreviewAttempt" enable row level security;

-- Records one auto-fill attempt for the caller.
-- 20 attempts within 1 hour: raises PT429 with DETAIL = seconds until retry.
create or replace function private.record_link_preview()
returns void
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  c_limit constant int := 20;
  c_window constant interval := interval '1 hour';
  v_user_id uuid := (select auth.uid());
  v_attempts int;
  v_oldest timestamptz;
begin
  if v_user_id is null then
    raise exception 'Not signed in' using errcode = 'insufficient_privilege';
  end if;

  -- Serialize this user's attempts so parallel requests can't overshoot the limit.
  perform pg_advisory_xact_lock(hashtextextended('record_link_preview:' || v_user_id::text, 0));

  delete from public."LinkPreviewAttempt"
  where user_id = v_user_id and attempted_at <= now() - c_window;

  select count(*), min(attempted_at) into v_attempts, v_oldest
  from public."LinkPreviewAttempt"
  where user_id = v_user_id;

  if v_attempts >= c_limit then
    raise exception 'Too many auto-fills'
      using errcode = 'PT429',
            detail = greatest(1, ceil(extract(epoch from (v_oldest + c_window - now()))))::int::text;
  end if;

  insert into public."LinkPreviewAttempt" (user_id) values (v_user_id);
end;
$$;

revoke execute on function private.record_link_preview() from public;
grant execute on function private.record_link_preview() to authenticated;
