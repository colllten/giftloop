-- Group membership: owner/admin/member permissions, join codes, ownership transfer.
-- Cast: owner (O), admin (A), members (M, N), outsider (X). Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(33);

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

-- Role of p_user in the test group, read as postgres (bypasses RLS).
create function pg_temp.role_of(p_user uuid) returns text
language sql security definer as $$
  select role::text from public."GroupUser"
  where group_id = '99999999-0000-0000-0000-000000000001' and user_id = p_user;
$$;
grant execute on function pg_temp.role_of(uuid) to authenticated;

-- Current join code, read as postgres.
create function pg_temp.code() returns text
language sql security definer as $$
  select code from public."GroupJoinCode" where group_id = '99999999-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('99999999-0000-0000-0000-00000000000a', 'gm-owner@example.com',    '{"username":"gm_owner"}'),
  ('99999999-0000-0000-0000-00000000000b', 'gm-admin@example.com',    '{"username":"gm_admin"}'),
  ('99999999-0000-0000-0000-00000000000c', 'gm-member@example.com',   '{"username":"gm_member"}'),
  ('99999999-0000-0000-0000-00000000000d', 'gm-member2@example.com',  '{"username":"gm_member2"}'),
  ('99999999-0000-0000-0000-00000000000e', 'gm-outsider@example.com', '{"username":"gm_outsider"}');

-- ------------------------------------------------------------ create + code
select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('99999999-0000-0000-0000-000000000001', 'GM Family');

select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000a'), 'owner', 'creator becomes owner');
select ok(
  (select code from public."GroupJoinCode" where group_id = '99999999-0000-0000-0000-000000000001') ~ '^[A-HJ-NP-Z2-9]{8}$',
  'new group gets a join code the owner can read'
);

-- ------------------------------------------------------------ join
select pg_temp.act_as('99999999-0000-0000-0000-00000000000e');
select throws_ok($$ select private.join_group_by_code('ZZZZZZZZ') $$, 'P0002', null, 'unknown code is rejected');
select is((select count(*)::int from public."Group" where id = '99999999-0000-0000-0000-000000000001'),
  0, 'outsider cannot see the group');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
select is(
  private.join_group_by_code(lower(left(pg_temp.code(), 4) || '-' || right(pg_temp.code(), 4))),
  '99999999-0000-0000-0000-000000000001'::uuid,
  'join accepts lowercase and dashes, returns group id'
);
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000b'), 'member', 'joiner becomes member');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select lives_ok($$ select private.join_group_by_code(pg_temp.code()) $$, 'joining twice is a no-op');
select pg_temp.act_as('99999999-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());

select is((select count(*)::int from public."GroupJoinCode"), 0, 'member cannot read the join code');
select throws_ok($$ insert into public."GroupUser" (group_id, user_id)
  values ('99999999-0000-0000-0000-000000000001', '99999999-0000-0000-0000-00000000000e') $$,
  '42501', null, 'members cannot be inserted directly');

-- ------------------------------------------------------------ roles
select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
update public."GroupUser" set role = 'admin'
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000b';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000b'), 'admin', 'owner promotes member to admin');
select throws_ok($$ update public."GroupUser" set role = 'owner'
  where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000c' $$,
  '42501', null, 'owner cannot grant owner by update');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
update public."GroupUser" set role = 'admin'
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000c';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000c'), 'member', 'admin cannot change roles');

-- ------------------------------------------------------------ code management
select is((select count(*)::int from public."GroupJoinCode"), 1, 'admin can read the join code');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000c');
select throws_ok($$ select private.regenerate_join_code('99999999-0000-0000-0000-000000000001') $$,
  '42501', null, 'member cannot regenerate the code');

select pg_temp.act_as(null);
select set_config('test.old_code', pg_temp.code(), true);
select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
select isnt(private.regenerate_join_code('99999999-0000-0000-0000-000000000001'),
  current_setting('test.old_code'), 'admin regenerates the code');
select pg_temp.act_as('99999999-0000-0000-0000-00000000000e');
select throws_ok($$ select private.join_group_by_code(current_setting('test.old_code')) $$,
  'P0002', null, 'old code stops working');
select throws_ok($$ insert into public."GroupJoinCode" (group_id, code)
  values ('99999999-0000-0000-0000-000000000001', 'ABCDEFGH') $$,
  '42501', null, 'join codes cannot be written directly');

-- ------------------------------------------------------------ rename
select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
update public."Group" set name = 'Renamed' where id = '99999999-0000-0000-0000-000000000001';
select pg_temp.act_as('99999999-0000-0000-0000-00000000000c');
update public."Group" set name = 'Hijacked' where id = '99999999-0000-0000-0000-000000000001';
select is((select name from public."Group" where id = '99999999-0000-0000-0000-000000000001'),
  'Renamed', 'admin can rename; member cannot');

-- ------------------------------------------------------------ remove / leave
select pg_temp.act_as('99999999-0000-0000-0000-00000000000c');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000d';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000d'), 'member', 'member cannot remove others');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000d';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000d'), null, 'admin removes a member');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000a';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000a'), 'owner', 'admin cannot remove the owner');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000a';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000a'), 'owner', 'owner cannot leave');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000c');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000c';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000c'), null, 'member can leave');

-- Re-add M and make a second admin N so owner-vs-admin removal is covered.
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('99999999-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
update public."GroupUser" set role = 'admin'
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000d';

select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000d';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000d'), 'admin', 'admin cannot remove another admin');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
delete from public."GroupUser"
where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000d';
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000d'), null, 'owner removes an admin');

-- ------------------------------------------------------------ delete (denied)
select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
delete from public."Group" where id = '99999999-0000-0000-0000-000000000001';
select is((select count(*)::int from public."Group" where id = '99999999-0000-0000-0000-000000000001'),
  1, 'admin cannot delete the group');

-- ------------------------------------------------------------ transfer
select throws_ok($$ select private.transfer_group_ownership('99999999-0000-0000-0000-000000000001', '99999999-0000-0000-0000-00000000000b') $$,
  '42501', null, 'non-owner cannot transfer ownership');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');
select throws_ok($$ select private.transfer_group_ownership('99999999-0000-0000-0000-000000000001', '99999999-0000-0000-0000-00000000000e') $$,
  '23514', null, 'cannot transfer to a non-member');
select lives_ok($$ select private.transfer_group_ownership('99999999-0000-0000-0000-000000000001', '99999999-0000-0000-0000-00000000000b') $$,
  'owner transfers ownership');
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000b'), 'owner', 'new owner is owner');
select is(pg_temp.role_of('99999999-0000-0000-0000-00000000000a'), 'admin', 'previous owner becomes admin');

select pg_temp.act_as(null);
select throws_ok($$ update public."GroupUser" set role = 'owner'
  where group_id = '99999999-0000-0000-0000-000000000001' and user_id = '99999999-0000-0000-0000-00000000000c' $$,
  '23505', null, 'a group has at most one owner');

-- ------------------------------------------------------------ delete
select pg_temp.act_as('99999999-0000-0000-0000-00000000000b');
delete from public."Group" where id = '99999999-0000-0000-0000-000000000001';
select pg_temp.act_as(null);
select is(
  (select count(*)::int from public."GroupUser" where group_id = '99999999-0000-0000-0000-000000000001')
  + (select count(*)::int from public."GroupJoinCode" where group_id = '99999999-0000-0000-0000-000000000001'),
  0, 'owner deletes the group; members and code cascade'
);

select * from finish();
rollback;
