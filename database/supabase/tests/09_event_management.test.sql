-- Event management: only the group owner/admin edit or delete events (not the
-- creator); deleting cascades wishlists, gifts and claims.
-- Cast: owner (O), admin (A), member/creator (M), co-buyer (D), outsider (X).
-- Self-contained fixtures.
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

create function pg_temp.code() returns text
language sql security definer as $$
  select code from public."GroupJoinCode" where group_id = 'e9e9e9e9-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- Event name read as postgres (bypasses RLS); null when the event is gone.
create function pg_temp.event_name(p_event uuid) returns text
language sql security definer as $$
  select name from public."Event" where id = p_event;
$$;
grant execute on function pg_temp.event_name(uuid) to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('e9e9e9e9-0000-0000-0000-00000000000a', 'em-owner@example.com',    '{"username":"em_owner"}'),
  ('e9e9e9e9-0000-0000-0000-00000000000b', 'em-admin@example.com',    '{"username":"em_admin"}'),
  ('e9e9e9e9-0000-0000-0000-00000000000c', 'em-member@example.com',   '{"username":"em_member"}'),
  ('e9e9e9e9-0000-0000-0000-00000000000d', 'em-cobuyer@example.com',  '{"username":"em_cobuyer"}'),
  ('e9e9e9e9-0000-0000-0000-00000000000e', 'em-outsider@example.com', '{"username":"em_outsider"}');

-- ------------------------------------------------------------ fixtures
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('e9e9e9e9-0000-0000-0000-000000000001', 'EM Family');

select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());

select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000a');
update public."GroupUser" set role = 'admin'
where group_id = 'e9e9e9e9-0000-0000-0000-000000000001' and user_id = 'e9e9e9e9-0000-0000-0000-00000000000b';

-- M creates the event; the owner has a wishlist on it.
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000c');
insert into public."Event" (id, group_id, name)
values ('e9e9e9e9-0000-0000-0000-0000000000e1', 'e9e9e9e9-0000-0000-0000-000000000001', 'EM Event');

select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000a');
insert into public."Wishlist" (id, event_id)
values ('e9e9e9e9-0000-0000-0000-0000000000a1', 'e9e9e9e9-0000-0000-0000-0000000000e1');
insert into public."Gift" (id, wishlist_id, name, quantity) values
  ('e9e9e9e9-0000-0000-0000-0000000000f1', 'e9e9e9e9-0000-0000-0000-0000000000a1', 'Socks', 3),
  ('e9e9e9e9-0000-0000-0000-0000000000f2', 'e9e9e9e9-0000-0000-0000-0000000000a1', 'Book', 1);

-- M claims socks with co-buyer D, and the book (which the owner then removes).
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000c');
insert into public."Claim" (id, gift_id, quantity) values
  ('e9e9e9e9-0000-0000-0000-0000000000c1', 'e9e9e9e9-0000-0000-0000-0000000000f1', 2),
  ('e9e9e9e9-0000-0000-0000-0000000000c2', 'e9e9e9e9-0000-0000-0000-0000000000f2', 1);
insert into public."ClaimParticipant" (claim_id, user_id)
values ('e9e9e9e9-0000-0000-0000-0000000000c1', 'e9e9e9e9-0000-0000-0000-00000000000d');

select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000a');
select private.remove_gift('e9e9e9e9-0000-0000-0000-0000000000f2');

-- ------------------------------------------------------------ creator / outsider denied
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000c');
update public."Event" set name = 'Renamed by creator' where id = 'e9e9e9e9-0000-0000-0000-0000000000e1';
select is(pg_temp.event_name('e9e9e9e9-0000-0000-0000-0000000000e1'), 'EM Event',
  'creator who is a plain member cannot edit the event');
delete from public."Event" where id = 'e9e9e9e9-0000-0000-0000-0000000000e1';
select is(pg_temp.event_name('e9e9e9e9-0000-0000-0000-0000000000e1'), 'EM Event',
  'creator who is a plain member cannot delete the event');

select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000e');
select is((select count(*)::int from public."Event" where id = 'e9e9e9e9-0000-0000-0000-0000000000e1'), 0,
  'outsider cannot see the event');
update public."Event" set name = 'Renamed by outsider' where id = 'e9e9e9e9-0000-0000-0000-0000000000e1';
delete from public."Event" where id = 'e9e9e9e9-0000-0000-0000-0000000000e1';
select is(pg_temp.event_name('e9e9e9e9-0000-0000-0000-0000000000e1'), 'EM Event',
  'outsider cannot edit or delete the event');

-- ------------------------------------------------------------ admin / owner allowed
select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000b');
update public."Event" set name = 'Renamed by admin', end_date = '2030-01-01'
where id = 'e9e9e9e9-0000-0000-0000-0000000000e1';
select is(pg_temp.event_name('e9e9e9e9-0000-0000-0000-0000000000e1'), 'Renamed by admin', 'admin renames the event');
select is((select end_date from public."Event" where id = 'e9e9e9e9-0000-0000-0000-0000000000e1'),
  '2030-01-01'::date, 'admin changes the end date');
select throws_ok(
  $$ update public."Event" set group_id = gen_random_uuid() where id = 'e9e9e9e9-0000-0000-0000-0000000000e1' $$,
  '42501', null, 'admin cannot move the event to another group');

select pg_temp.act_as('e9e9e9e9-0000-0000-0000-00000000000a');
update public."Event" set name = 'Renamed by owner' where id = 'e9e9e9e9-0000-0000-0000-0000000000e1';
select is(pg_temp.event_name('e9e9e9e9-0000-0000-0000-0000000000e1'), 'Renamed by owner', 'owner renames the event');

select lives_ok(
  $$ delete from public."Event" where id = 'e9e9e9e9-0000-0000-0000-0000000000e1' $$,
  'owner deletes an event with shared claims and a removed gift');
select is(pg_temp.event_name('e9e9e9e9-0000-0000-0000-0000000000e1'), null, 'event is gone');

select pg_temp.act_as(null);
select is(
  (select count(*)::int from public."Wishlist" where event_id = 'e9e9e9e9-0000-0000-0000-0000000000e1')
  + (select count(*)::int from public."Gift" where wishlist_id = 'e9e9e9e9-0000-0000-0000-0000000000a1'),
  0, 'wishlists and gifts (incl. removed) cascade');
select is(
  (select count(*)::int from public."Claim" where id in
    ('e9e9e9e9-0000-0000-0000-0000000000c1', 'e9e9e9e9-0000-0000-0000-0000000000c2'))
  + (select count(*)::int from public."ClaimParticipant" where claim_id in
    ('e9e9e9e9-0000-0000-0000-0000000000c1', 'e9e9e9e9-0000-0000-0000-0000000000c2')),
  0, 'claims and participants cascade');

select * from finish();
rollback;
