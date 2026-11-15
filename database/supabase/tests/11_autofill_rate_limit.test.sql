-- Auto-fill rate limit: 20 link previews per user per hour, then PT429.
-- Cast: user (U), other user (V). Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

create function pg_temp.act_as(p_user uuid) returns void language plpgsql as $$
begin
  if p_user is null then
    reset role;
    perform set_config('request.jwt.claims', '', true);
  else
    perform set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
    set local role authenticated;
  end if;
end;
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated;

-- Attempts recorded for p_user, read as postgres.
create function pg_temp.attempts(p_user uuid) returns int
language sql security definer as $$
  select count(*)::int from public."LinkPreviewAttempt" where user_id = p_user;
$$;
grant execute on function pg_temp.attempts(uuid) to authenticated;

-- DETAIL of the PT429 raised for the caller (seconds until retry), or null if not blocked.
create function pg_temp.retry_detail() returns text language plpgsql as $$
declare
  v_detail text;
begin
  perform private.record_link_preview();
  return null;
exception when sqlstate 'PT429' then
  get stacked diagnostics v_detail = pg_exception_detail;
  return v_detail;
end;
$$;
grant execute on function pg_temp.retry_detail() to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('99999999-0000-0000-0000-00000000000a', 'af-user@example.com',  '{"username":"af_user"}'),
  ('99999999-0000-0000-0000-00000000000b', 'af-other@example.com', '{"username":"af_other"}');

-- ------------------------------------------------------------ anonymous
select throws_ok($$ select private.record_link_preview() $$, '42501', null,
  'anonymous callers are refused');

-- ------------------------------------------------------------ limit
select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
select lives_ok($$ select private.record_link_preview() from generate_series(1, 20) $$,
  '20 auto-fills within the hour are allowed');
select is(pg_temp.attempts('99999999-0000-0000-0000-00000000000a'), 20, 'each attempt is recorded');

select throws_ok($$ select private.record_link_preview() $$,
  'PT429', 'Too many auto-fills', '21st attempt within the window is blocked');
select is(pg_temp.retry_detail()::int, 3600, 'DETAIL gives seconds until the oldest attempt expires');
select is(pg_temp.attempts('99999999-0000-0000-0000-00000000000a'), 20, 'blocked attempts are not recorded');

select throws_ok($$ select * from public."LinkPreviewAttempt" $$, '42501', null,
  'users cannot read attempts');
select throws_ok($$ delete from public."LinkPreviewAttempt" $$, '42501', null,
  'users cannot clear their own attempts');

-- ------------------------------------------------------------ per user
select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
select lives_ok($$ select private.record_link_preview() $$, 'other users are not affected');

-- ------------------------------------------------------------ window
-- Age the oldest attempt past the 1-hour window.
select pg_temp.act_as(null);
update public."LinkPreviewAttempt" set attempted_at = now() - interval '61 minutes'
where id = (select min(id) from public."LinkPreviewAttempt" where user_id = '99999999-0000-0000-0000-00000000000a');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
select lives_ok($$ select private.record_link_preview() $$,
  'allowed again once the oldest attempt leaves the window');

select * from finish();
rollback;
