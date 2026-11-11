-- Join code rate limit: 5 failed codes per user per 15 minutes, then PT429.
-- Cast: joiner (J), other user (K), owner (O). Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(12);

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

-- Current join code, read as postgres.
create function pg_temp.code() returns text
language sql security definer as $$
  select code from public."GroupJoinCode" where group_id = '88888888-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- Failures recorded for p_user, read as postgres.
create function pg_temp.failures(p_user uuid) returns int
language sql security definer as $$
  select count(*)::int from public."JoinCodeFailure" where user_id = p_user;
$$;
grant execute on function pg_temp.failures(uuid) to authenticated;

-- DETAIL of the PT429 raised for the caller (seconds until retry), or null if not blocked.
create function pg_temp.retry_detail() returns text language plpgsql as $$
declare
  v_detail text;
begin
  perform private.join_group_by_code('ZZZZZZZZ');
  return null;
exception when sqlstate 'PT429' then
  get stacked diagnostics v_detail = pg_exception_detail;
  return v_detail;
end;
$$;
grant execute on function pg_temp.retry_detail() to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('88888888-0000-0000-0000-00000000000a', 'rl-owner@example.com',  '{"username":"rl_owner"}'),
  ('88888888-0000-0000-0000-00000000000b', 'rl-joiner@example.com', '{"username":"rl_joiner"}'),
  ('88888888-0000-0000-0000-00000000000c', 'rl-other@example.com',  '{"username":"rl_other"}');

select pg_temp.act_as('88888888-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('88888888-0000-0000-0000-000000000001', 'RL Family');

-- ------------------------------------------------------------ failures
select pg_temp.act_as('88888888-0000-0000-0000-00000000000b');
select is(private.join_group_by_code('ZZZZZZZZ'), null, 'unknown code returns null');
select private.join_group_by_code('ZZZZZZZZ') from generate_series(1, 4);
select is(pg_temp.failures('88888888-0000-0000-0000-00000000000b'), 5, 'each unknown code is recorded');

select throws_ok($$ select private.join_group_by_code('ZZZZZZZZ') $$,
  'PT429', 'Too many incorrect join codes', '6th attempt within the window is blocked');
select throws_ok($$ select private.join_group_by_code(pg_temp.code()) $$,
  'PT429', 'Too many incorrect join codes', 'correct code is blocked too while locked out');
select is(pg_temp.retry_detail()::int, 900, 'DETAIL gives seconds until the oldest failure expires');
select is(pg_temp.failures('88888888-0000-0000-0000-00000000000b'), 5, 'blocked attempts are not recorded');

select throws_ok($$ select * from public."JoinCodeFailure" $$, '42501', null,
  'users cannot read failures');
select throws_ok($$ delete from public."JoinCodeFailure" $$, '42501', null,
  'users cannot clear their own failures');

-- ------------------------------------------------------------ per user
select pg_temp.act_as('88888888-0000-0000-0000-00000000000c');
select is(private.join_group_by_code(pg_temp.code()), '88888888-0000-0000-0000-000000000001'::uuid,
  'other users are not affected');
select is(pg_temp.failures('88888888-0000-0000-0000-00000000000c'), 0, 'successful join records nothing');

-- ------------------------------------------------------------ window
-- Age the oldest failure past the 15-minute window.
select pg_temp.act_as(null);
update public."JoinCodeFailure" set failed_at = now() - interval '16 minutes'
where id = (select min(id) from public."JoinCodeFailure" where user_id = '88888888-0000-0000-0000-00000000000b');

select pg_temp.act_as('88888888-0000-0000-0000-00000000000b');
select is(private.join_group_by_code(pg_temp.code()), '88888888-0000-0000-0000-000000000001'::uuid,
  'allowed again once the oldest failure leaves the window');
select is(pg_temp.failures('88888888-0000-0000-0000-00000000000b'), 4, 'expired failures are pruned');

select * from finish();
rollback;
