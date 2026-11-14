-- Gift visibility: everyone vs. an owner-picked allowlist (GiftViewer).
-- Cast: owner (O), viewer (V), claimer (C), hidden member (M), outsider (X).
-- Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

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
  select code from public."GroupJoinCode" where group_id = 'a5a5a5a5-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- Viewer rows for a user, read as postgres (bypasses RLS).
create function pg_temp.viewer_rows(p_user uuid) returns int
language sql security definer as $$
  select count(*)::int from public."GiftViewer" where user_id = p_user;
$$;
grant execute on function pg_temp.viewer_rows(uuid) to authenticated;

-- Number of rows of the given gift the current user can see.
create function pg_temp.sees(p_gift uuid) returns int
language sql as $$
  select count(*)::int from public."Gift" where id = p_gift;
$$;
grant execute on function pg_temp.sees(uuid) to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a5a5a5a5-0000-0000-0000-00000000000a', 'gv-owner@example.com',    '{"username":"gv_owner"}'),
  ('a5a5a5a5-0000-0000-0000-00000000000b', 'gv-viewer@example.com',   '{"username":"gv_viewer"}'),
  ('a5a5a5a5-0000-0000-0000-00000000000c', 'gv-claimer@example.com',  '{"username":"gv_claimer"}'),
  ('a5a5a5a5-0000-0000-0000-00000000000d', 'gv-member@example.com',   '{"username":"gv_member"}'),
  ('a5a5a5a5-0000-0000-0000-00000000000e', 'gv-outsider@example.com', '{"username":"gv_outsider"}');

-- ------------------------------------------------------------ fixtures
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('a5a5a5a5-0000-0000-0000-000000000001', 'GV Family');

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000a');
insert into public."Event" (id, group_id, name)
values ('a5a5a5a5-0000-0000-0000-000000000002', 'a5a5a5a5-0000-0000-0000-000000000001', 'GV Holiday');
insert into public."Wishlist" (id, event_id)
values ('a5a5a5a5-0000-0000-0000-000000000003', 'a5a5a5a5-0000-0000-0000-000000000002');
insert into public."Gift" (id, wishlist_id, name)
values ('a5a5a5a5-0000-0000-0000-0000000000f1', 'a5a5a5a5-0000-0000-0000-000000000003', 'For everyone');

select lives_ok(
  $$ insert into public."Gift" (id, wishlist_id, name, is_visible_to_all)
     values ('a5a5a5a5-0000-0000-0000-0000000000f2', 'a5a5a5a5-0000-0000-0000-000000000003', 'Restricted', false)
     returning id $$,
  'owner can insert a restricted gift with RETURNING'
);
insert into public."GiftViewer" (gift_id, user_id) values
  ('a5a5a5a5-0000-0000-0000-0000000000f2', 'a5a5a5a5-0000-0000-0000-00000000000b'),
  ('a5a5a5a5-0000-0000-0000-0000000000f2', 'a5a5a5a5-0000-0000-0000-00000000000c');

-- ------------------------------------------------------------ reading gifts
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f2'), 1, 'owner sees restricted gift');
select is((select count(*)::int from public."GiftViewer" where gift_id = 'a5a5a5a5-0000-0000-0000-0000000000f2'),
  2, 'owner reads the viewer list');

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000d');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f1'), 1, 'member sees an everyone gift');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f2'), 0, 'unlisted member cannot see restricted gift');

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000e');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f1'), 0, 'outsider cannot see an everyone gift');

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000b');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f2'), 1, 'listed viewer sees restricted gift');
select is((select count(*)::int from public."GiftViewer"), 0, 'viewer cannot read the viewer list');
select throws_ok(
  $$ insert into public."GiftViewer" (gift_id, user_id)
     values ('a5a5a5a5-0000-0000-0000-0000000000f2', 'a5a5a5a5-0000-0000-0000-00000000000d') $$,
  '42501', null, 'non-owner cannot add viewers'
);

-- ------------------------------------------------------------ claims
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000d');
select throws_ok(
  $$ insert into public."Claim" (gift_id, quantity) values ('a5a5a5a5-0000-0000-0000-0000000000f2', 1) $$,
  '23514', null, 'hidden member cannot claim restricted gift (trigger fires before RLS)'
);

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000c');
select lives_ok(
  $$ insert into public."Claim" (id, gift_id, quantity)
     values ('a5a5a5a5-0000-0000-0000-0000000000c1', 'a5a5a5a5-0000-0000-0000-0000000000f2', 1) $$,
  'listed viewer can claim restricted gift'
);
select throws_ok(
  $$ insert into public."ClaimParticipant" (claim_id, user_id)
     values ('a5a5a5a5-0000-0000-0000-0000000000c1', 'a5a5a5a5-0000-0000-0000-00000000000d') $$,
  '23514', null, 'cannot add a co-participant who cannot see the gift'
);

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000d');
select is((select count(*)::int from public."Claim" where gift_id = 'a5a5a5a5-0000-0000-0000-0000000000f2'),
  0, 'hidden member cannot see claims on restricted gift');
select is((select count(*)::int from public."GiftClaimSummary" where gift_id = 'a5a5a5a5-0000-0000-0000-0000000000f2'),
  0, 'hidden member cannot see claim summary of restricted gift');

select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000b');
select is((select count(*)::int from public."Claim" where gift_id = 'a5a5a5a5-0000-0000-0000-0000000000f2'),
  1, 'listed viewer sees claims on restricted gift');

-- ------------------------------------------------------------ owner edits
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000a');
select throws_ok(
  $$ insert into public."GiftViewer" (gift_id, user_id)
     values ('a5a5a5a5-0000-0000-0000-0000000000f2', 'a5a5a5a5-0000-0000-0000-00000000000a') $$,
  '23514', null, 'owner cannot be a viewer of their own gift'
);
select throws_ok(
  $$ insert into public."GiftViewer" (gift_id, user_id)
     values ('a5a5a5a5-0000-0000-0000-0000000000f2', 'a5a5a5a5-0000-0000-0000-00000000000e') $$,
  '23514', null, 'outsider cannot be a viewer'
);

delete from public."GiftViewer"
where gift_id = 'a5a5a5a5-0000-0000-0000-0000000000f2' and user_id = 'a5a5a5a5-0000-0000-0000-00000000000c';
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000c');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f2'), 1, 'claimer keeps seeing gift after removal from viewers');

-- ------------------------------------------------------------ leaving
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000b');
delete from public."GroupUser"
where group_id = 'a5a5a5a5-0000-0000-0000-000000000001' and user_id = 'a5a5a5a5-0000-0000-0000-00000000000b';
select is(pg_temp.viewer_rows('a5a5a5a5-0000-0000-0000-00000000000b'), 0, 'leaving the group drops viewer rows');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f2'), 0, 'former member cannot see restricted gift');

-- ------------------------------------------------------------ widen
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000a');
update public."Gift" set is_visible_to_all = true where id = 'a5a5a5a5-0000-0000-0000-0000000000f2';
select pg_temp.act_as('a5a5a5a5-0000-0000-0000-00000000000d');
select is(pg_temp.sees('a5a5a5a5-0000-0000-0000-0000000000f2'), 1, 'sharing with everyone shows gift to all members');

select pg_temp.act_as(null);
select * from finish();
rollback;
