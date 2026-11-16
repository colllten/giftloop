-- Gift exchange (Secret Santa): opt-in participants, admin-only exclusions and
-- draw, assignments readable only by their giver, type locked after the draw.
-- Cast: owner (O), admin (A), members M1-M3, outsider (X). Self-contained.
begin;
create extension if not exists pgtap with schema extensions;
select plan(32);

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

create function pg_temp.code() returns text
language sql security definer as $$
  select code from public."GroupJoinCode" where group_id = 'e1e1e1e1-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- All assignments of an event, read as postgres.
create function pg_temp.assignments(p_event uuid) returns table (giver uuid, receiver uuid)
language sql security definer as $$
  select giver_id, receiver_id from public."ExchangeAssignment" where event_id = p_event;
$$;
grant execute on function pg_temp.assignments(uuid) to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('e1e1e1e1-0000-0000-0000-00000000000a', 'gx-owner@example.com',    '{"username":"gx_owner"}'),
  ('e1e1e1e1-0000-0000-0000-00000000000b', 'gx-admin@example.com',    '{"username":"gx_admin"}'),
  ('e1e1e1e1-0000-0000-0000-00000000000c', 'gx-m1@example.com',       '{"username":"gx_m1"}'),
  ('e1e1e1e1-0000-0000-0000-00000000000d', 'gx-m2@example.com',       '{"username":"gx_m2"}'),
  ('e1e1e1e1-0000-0000-0000-00000000000f', 'gx-m3@example.com',       '{"username":"gx_m3"}'),
  ('e1e1e1e1-0000-0000-0000-00000000000e', 'gx-outsider@example.com', '{"username":"gx_outsider"}');

-- ------------------------------------------------------------ fixtures
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('e1e1e1e1-0000-0000-0000-000000000001', 'GX Family');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000f');
select private.join_group_by_code(pg_temp.code());

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000a');
update public."GroupUser" set role = 'admin'
where group_id = 'e1e1e1e1-0000-0000-0000-000000000001' and user_id = 'e1e1e1e1-0000-0000-0000-00000000000b';

-- M1 creates an exchange (x1) and a standard event (s1).
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000c');
insert into public."Event" (id, group_id, name, type, budget, drawn_at) values
  ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-000000000001', 'GX Swap', 'exchange', 25, now());
insert into public."Event" (id, group_id, name, budget) values
  ('e1e1e1e1-0000-0000-0000-0000000000e2', 'e1e1e1e1-0000-0000-0000-000000000001', 'GX Standard', 25);

-- ------------------------------------------------------------ event fields
select is((select drawn_at from public."Event" where id = 'e1e1e1e1-0000-0000-0000-0000000000e1'), null,
  'drawn_at cannot be set on insert');
select is((select budget from public."Event" where id = 'e1e1e1e1-0000-0000-0000-0000000000e2'), null,
  'budget is dropped on a standard event');
select is((select type::text from public."Event" where id = 'e1e1e1e1-0000-0000-0000-0000000000e2'), 'standard',
  'type defaults to standard');
select throws_ok(
  $$ update public."Event" set drawn_at = now() where id = 'e1e1e1e1-0000-0000-0000-0000000000e1' $$,
  '42501', null, 'drawn_at is not directly updatable');

-- ------------------------------------------------------------ joining
select lives_ok(
  $$ insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  'member joins an exchange');
select throws_ok(
  $$ insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e2') $$,
  '42501', null, 'cannot join a standard event');
select throws_ok(
  $$ insert into public."ExchangeParticipant" (event_id, user_id)
     values ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000d') $$,
  '42501', null, 'cannot sign someone else up');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000e');
select throws_ok(
  $$ insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  '23514', null, 'outsider cannot join');
select is((select count(*)::int from public."ExchangeParticipant"
           where event_id = 'e1e1e1e1-0000-0000-0000-0000000000e1'), 0,
  'outsider cannot see participants');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000d');
insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e1');
select is((select count(*)::int from public."ExchangeParticipant"
           where event_id = 'e1e1e1e1-0000-0000-0000-0000000000e1'), 2,
  'members see who joined');

-- ------------------------------------------------------------ draw preconditions
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000b');
select throws_ok($$ select private.draw_exchange('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  '23514', null, 'two participants cannot be drawn');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000b');
insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e1');
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000f');
insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e1');

-- ------------------------------------------------------------ exclusions
select throws_ok(
  $$ insert into public."ExchangeExclusion" (event_id, user_a, user_b) values
     ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000c', 'e1e1e1e1-0000-0000-0000-00000000000d') $$,
  '42501', null, 'members cannot add exclusions');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000b');
select lives_ok(
  $$ insert into public."ExchangeExclusion" (event_id, user_a, user_b) values
     ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000c', 'e1e1e1e1-0000-0000-0000-00000000000d') $$,
  'admin excludes M1 and M2 from each other');
select throws_ok(
  $$ insert into public."ExchangeExclusion" (event_id, user_a, user_b) values
     ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000a', 'e1e1e1e1-0000-0000-0000-00000000000c') $$,
  '23503', null, 'exclusions need both people to have joined');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000c');
select is((select count(*)::int from public."ExchangeExclusion"), 0, 'members cannot see exclusions');

-- ------------------------------------------------------------ draw
select throws_ok($$ select private.draw_exchange('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  '42501', null, 'members cannot draw');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000b');
select lives_ok($$ select private.draw_exchange('e1e1e1e1-0000-0000-0000-0000000000e1') $$, 'admin draws names');

select is((select count(*)::int from pg_temp.assignments('e1e1e1e1-0000-0000-0000-0000000000e1')), 4,
  'every participant gives once');
select is((select count(distinct receiver)::int from pg_temp.assignments('e1e1e1e1-0000-0000-0000-0000000000e1')), 4,
  'every participant receives once');
select is((select count(*)::int from pg_temp.assignments('e1e1e1e1-0000-0000-0000-0000000000e1')
           where (giver, receiver) in (
             ('e1e1e1e1-0000-0000-0000-00000000000c'::uuid, 'e1e1e1e1-0000-0000-0000-00000000000d'::uuid),
             ('e1e1e1e1-0000-0000-0000-00000000000d'::uuid, 'e1e1e1e1-0000-0000-0000-00000000000c'::uuid))), 0,
  'excluded pairs are never drawn');

select is((select count(*)::int from public."ExchangeAssignment"), 1, 'admin sees only their own assignment');
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000c');
select is((select count(*)::int from public."ExchangeAssignment"
           where giver_id = 'e1e1e1e1-0000-0000-0000-00000000000c'), 1, 'giver sees their recipient');
select is((select count(*)::int from public."ExchangeAssignment"
           where receiver_id = 'e1e1e1e1-0000-0000-0000-00000000000c'), 0, 'receiver cannot see their giver');
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000a');
select is((select count(*)::int from public."ExchangeAssignment"), 0, 'owner who did not join sees nothing');

-- ------------------------------------------------------------ after the draw
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000a');
select throws_ok(
  $$ insert into public."ExchangeParticipant" (event_id) values ('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  '42501', null, 'cannot join after the draw');
select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000c');
delete from public."ExchangeParticipant" where event_id = 'e1e1e1e1-0000-0000-0000-0000000000e1';
select is((select count(*)::int from public."ExchangeParticipant"
           where event_id = 'e1e1e1e1-0000-0000-0000-0000000000e1'), 4, 'cannot leave after the draw');

select pg_temp.act_as('e1e1e1e1-0000-0000-0000-00000000000b');
select throws_ok(
  $$ update public."Event" set type = 'standard' where id = 'e1e1e1e1-0000-0000-0000-0000000000e1' $$,
  '23514', null, 'type is locked after the draw');
select throws_ok($$ select private.draw_exchange('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  '23514', null, 'cannot draw twice');

-- ------------------------------------------------------------ reset, impossible draw, type switch
select lives_ok($$ select private.reset_exchange_draw('e1e1e1e1-0000-0000-0000-0000000000e1') $$, 'admin undoes the draw');
select is((select count(*)::int from pg_temp.assignments('e1e1e1e1-0000-0000-0000-0000000000e1')), 0,
  'undo deletes assignments');

-- M3 excluded from everyone: no valid draw.
insert into public."ExchangeExclusion" (event_id, user_a, user_b) values
  ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000b', 'e1e1e1e1-0000-0000-0000-00000000000f'),
  ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000c', 'e1e1e1e1-0000-0000-0000-00000000000f'),
  ('e1e1e1e1-0000-0000-0000-0000000000e1', 'e1e1e1e1-0000-0000-0000-00000000000d', 'e1e1e1e1-0000-0000-0000-00000000000f');
select throws_ok($$ select private.draw_exchange('e1e1e1e1-0000-0000-0000-0000000000e1') $$,
  '23514', null, 'impossible exclusions fail the draw');

update public."Event" set type = 'standard' where id = 'e1e1e1e1-0000-0000-0000-0000000000e1';
select pg_temp.act_as(null);
select is((select count(*)::int from public."ExchangeParticipant" where event_id = 'e1e1e1e1-0000-0000-0000-0000000000e1')
          + (select count(*)::int from public."ExchangeExclusion" where event_id = 'e1e1e1e1-0000-0000-0000-0000000000e1'),
  0, 'switching to standard drops participants and exclusions');

select * from finish();
rollback;
