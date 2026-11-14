-- Gift and wishlist removal: claimed rows are soft-removed, and the owner
-- can't tell removed from deleted. Claim creators release instead of leaving.
-- Cast: owner (O), claimer (C, creates claims), co-buyer (D), bystander (B).
-- Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(37);

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
  select code from public."GroupJoinCode" where group_id = 'a6a6a6a6-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- Rows of the gift the current user can see.
create function pg_temp.sees(p_gift uuid) returns int
language sql as $$
  select count(*)::int from public."Gift" where id = p_gift;
$$;
grant execute on function pg_temp.sees(uuid) to authenticated;

-- Rows the current user's rename of the gift touches.
create function pg_temp.rename(p_gift uuid) returns int
language plpgsql as $$
declare
  n int;
begin
  update public."Gift" set name = 'Renamed' where id = p_gift;
  get diagnostics n = row_count;
  return n;
end;
$$;
grant execute on function pg_temp.rename(uuid) to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a6a6a6a6-0000-0000-0000-00000000000a', 'gr-owner@example.com',     '{"username":"gr_owner"}'),
  ('a6a6a6a6-0000-0000-0000-00000000000b', 'gr-bystander@example.com', '{"username":"gr_bystander"}'),
  ('a6a6a6a6-0000-0000-0000-00000000000c', 'gr-claimer@example.com',   '{"username":"gr_claimer"}'),
  ('a6a6a6a6-0000-0000-0000-00000000000d', 'gr-cobuyer@example.com',   '{"username":"gr_cobuyer"}');

-- ------------------------------------------------------------ fixtures
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('a6a6a6a6-0000-0000-0000-000000000001', 'GR Family');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());

-- Event E1 / wishlist W1: g1 unclaimed, g2 claimed by C with D, g3 claimed by C.
-- Event E2 / wishlist W2: h1 unclaimed, h2 claimed by C.
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
insert into public."Event" (id, group_id, name) values
  ('a6a6a6a6-0000-0000-0000-0000000000e1', 'a6a6a6a6-0000-0000-0000-000000000001', 'GR One'),
  ('a6a6a6a6-0000-0000-0000-0000000000e2', 'a6a6a6a6-0000-0000-0000-000000000001', 'GR Two');
insert into public."Wishlist" (id, event_id) values
  ('a6a6a6a6-0000-0000-0000-0000000000a1', 'a6a6a6a6-0000-0000-0000-0000000000e1'),
  ('a6a6a6a6-0000-0000-0000-0000000000a2', 'a6a6a6a6-0000-0000-0000-0000000000e2');
insert into public."Gift" (id, wishlist_id, name) values
  ('a6a6a6a6-0000-0000-0000-0000000000f1', 'a6a6a6a6-0000-0000-0000-0000000000a1', 'Unclaimed'),
  ('a6a6a6a6-0000-0000-0000-0000000000f2', 'a6a6a6a6-0000-0000-0000-0000000000a1', 'Shared claim'),
  ('a6a6a6a6-0000-0000-0000-0000000000f3', 'a6a6a6a6-0000-0000-0000-0000000000a1', 'Solo claim'),
  ('a6a6a6a6-0000-0000-0000-0000000000b1', 'a6a6a6a6-0000-0000-0000-0000000000a2', 'W2 unclaimed'),
  ('a6a6a6a6-0000-0000-0000-0000000000b2', 'a6a6a6a6-0000-0000-0000-0000000000a2', 'W2 claimed');
insert into public."GiftViewer" (gift_id, user_id)
values ('a6a6a6a6-0000-0000-0000-0000000000f2', 'a6a6a6a6-0000-0000-0000-00000000000b');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
insert into public."Claim" (id, gift_id) values
  ('a6a6a6a6-0000-0000-0000-0000000000c2', 'a6a6a6a6-0000-0000-0000-0000000000f2'),
  ('a6a6a6a6-0000-0000-0000-0000000000c3', 'a6a6a6a6-0000-0000-0000-0000000000f3'),
  ('a6a6a6a6-0000-0000-0000-0000000000c4', 'a6a6a6a6-0000-0000-0000-0000000000b2');
insert into public."ClaimParticipant" (claim_id, user_id)
values ('a6a6a6a6-0000-0000-0000-0000000000c2', 'a6a6a6a6-0000-0000-0000-00000000000d');

-- ------------------------------------------------------------ direct deletes
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
select throws_ok($$ delete from public."Gift" where id = 'a6a6a6a6-0000-0000-0000-0000000000f1' $$,
  '42501', null, 'owner cannot delete a gift directly');
select throws_ok($$ delete from public."Wishlist" where id = 'a6a6a6a6-0000-0000-0000-0000000000a1' $$,
  '42501', null, 'owner cannot delete a wishlist directly');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
select throws_ok($$ select private.remove_gift('a6a6a6a6-0000-0000-0000-0000000000f1') $$,
  '42501', null, 'non-owner cannot remove a gift');

-- ------------------------------------------------------------ removing gifts
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
select lives_ok($$ select private.remove_gift('a6a6a6a6-0000-0000-0000-0000000000f1') $$,
  'owner removes an unclaimed gift');
select lives_ok($$ select private.remove_gift('a6a6a6a6-0000-0000-0000-0000000000f2') $$,
  'owner removes a claimed gift');

select pg_temp.act_as(null);
select is((select count(*)::int from public."Gift" where id = 'a6a6a6a6-0000-0000-0000-0000000000f1'),
  0, 'unclaimed gift is deleted');
select isnt((select removed_at from public."Gift" where id = 'a6a6a6a6-0000-0000-0000-0000000000f2'),
  null, 'claimed gift is marked removed');
select is((select count(*)::int from public."GiftViewer" where gift_id = 'a6a6a6a6-0000-0000-0000-0000000000f2'),
  0, 'removed gift drops its viewer rows');

-- The owner sees removed and deleted gifts the same way.
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
select is(pg_temp.sees('a6a6a6a6-0000-0000-0000-0000000000f2'), 0, 'owner cannot see a removed gift');
select is(pg_temp.rename('a6a6a6a6-0000-0000-0000-0000000000f2'), 0, 'owner update skips a removed gift');
select is(pg_temp.rename('a6a6a6a6-0000-0000-0000-0000000000f1'), 0, 'owner update skips a deleted gift');
select throws_ok($$ select private.remove_gift('a6a6a6a6-0000-0000-0000-0000000000f2') $$,
  '42501', null, 'removing a removed gift again fails');
select throws_ok($$ select private.remove_gift('a6a6a6a6-0000-0000-0000-0000000000f1') $$,
  '42501', null, 'removing a deleted gift fails the same way');
select throws_ok($$ insert into public."GiftViewer" (gift_id, user_id)
                    values ('a6a6a6a6-0000-0000-0000-0000000000f2', 'a6a6a6a6-0000-0000-0000-00000000000b') $$,
  '42501', null, 'owner cannot add viewers to a removed gift');
select throws_ok($$ insert into public."GiftViewer" (gift_id, user_id)
                    values ('a6a6a6a6-0000-0000-0000-0000000000f1', 'a6a6a6a6-0000-0000-0000-00000000000b') $$,
  '42501', null, 'owner cannot add viewers to a deleted gift (same error)');
select throws_ok($$ insert into public."Claim" (gift_id) values ('a6a6a6a6-0000-0000-0000-0000000000f2') $$,
  '23514', 'This gift can no longer be claimed', 'owner claiming a removed gift gets the generic error');
select throws_ok($$ insert into public."Claim" (gift_id) values ('a6a6a6a6-0000-0000-0000-0000000000f1') $$,
  '23514', 'This gift can no longer be claimed', 'owner claiming a deleted gift gets the same error');

-- Claimers keep seeing it; nobody else does.
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
select is(pg_temp.sees('a6a6a6a6-0000-0000-0000-0000000000f2'), 1, 'claim creator sees the removed gift');
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000d');
select is(pg_temp.sees('a6a6a6a6-0000-0000-0000-0000000000f2'), 1, 'co-buyer sees the removed gift');
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000b');
select is(pg_temp.sees('a6a6a6a6-0000-0000-0000-0000000000f2'), 0, 'bystander cannot see the removed gift');
select throws_ok($$ insert into public."Claim" (gift_id) values ('a6a6a6a6-0000-0000-0000-0000000000f2') $$,
  '23514', 'This gift can no longer be claimed', 'bystander cannot claim a removed gift');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
select throws_ok($$ insert into public."Claim" (gift_id) values ('a6a6a6a6-0000-0000-0000-0000000000f2') $$,
  '23514', 'This gift can no longer be claimed', 'claimer cannot add a new claim on a removed gift');
select throws_ok($$ insert into public."ClaimParticipant" (claim_id, user_id)
                    values ('a6a6a6a6-0000-0000-0000-0000000000c2', 'a6a6a6a6-0000-0000-0000-00000000000b') $$,
  '23514', null, 'cannot add co-buyers to a claim on a removed gift');

-- ------------------------------------------------------------ leaving and releasing
select throws_ok($$ delete from public."ClaimParticipant"
                    where claim_id = 'a6a6a6a6-0000-0000-0000-0000000000c2'
                      and user_id = 'a6a6a6a6-0000-0000-0000-00000000000c' $$,
  '23514', null, 'claim creator cannot leave their own claim');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000d');
select lives_ok($$ delete from public."ClaimParticipant"
                   where claim_id = 'a6a6a6a6-0000-0000-0000-0000000000c2'
                     and user_id = 'a6a6a6a6-0000-0000-0000-00000000000d' $$,
  'co-buyer can leave a claim');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
select lives_ok($$ delete from public."Claim" where id = 'a6a6a6a6-0000-0000-0000-0000000000c2' $$,
  'creator releases the claim (cascading their own participant row)');

select pg_temp.act_as(null);
select is((select count(*)::int from public."Gift" where id = 'a6a6a6a6-0000-0000-0000-0000000000f2'),
  0, 'removed gift is purged once its last claim is released');

-- ------------------------------------------------------------ removing wishlists
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
select lives_ok($$ select private.remove_wishlist('a6a6a6a6-0000-0000-0000-0000000000a2') $$,
  'owner removes a wishlist with a claimed gift');

select pg_temp.act_as(null);
select is((select count(*)::int from public."Gift" where id = 'a6a6a6a6-0000-0000-0000-0000000000b1'),
  0, 'wishlist removal deletes unclaimed gifts');
select isnt((select removed_at from public."Gift" where id = 'a6a6a6a6-0000-0000-0000-0000000000b2'),
  null, 'wishlist removal marks claimed gifts removed');
select isnt((select removed_at from public."Wishlist" where id = 'a6a6a6a6-0000-0000-0000-0000000000a2'),
  null, 'wishlist with claimed gifts becomes a removed shell');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
select is((select count(*)::int from public."Wishlist" where event_id = 'a6a6a6a6-0000-0000-0000-0000000000e2'),
  0, 'owner cannot see the removed wishlist');
select lives_ok($$ insert into public."Wishlist" (event_id) values ('a6a6a6a6-0000-0000-0000-0000000000e2') returning id $$,
  'owner can create a new wishlist for the same event');
select is((select count(*)::int from public."Wishlist"
           where event_id = 'a6a6a6a6-0000-0000-0000-0000000000e2'
             and id <> 'a6a6a6a6-0000-0000-0000-0000000000a2'),
  1, 'the new wishlist has a fresh id');

select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000c');
delete from public."Claim" where id = 'a6a6a6a6-0000-0000-0000-0000000000c4';

select pg_temp.act_as(null);
select is((select count(*)::int from public."Wishlist" where id = 'a6a6a6a6-0000-0000-0000-0000000000a2'),
  0, 'removed wishlist is purged once its last gift is purged');

-- The new (empty) wishlist has nothing claimed, so removal deletes it.
select pg_temp.act_as('a6a6a6a6-0000-0000-0000-00000000000a');
select private.remove_wishlist(
  (select id from public."Wishlist" where event_id = 'a6a6a6a6-0000-0000-0000-0000000000e2'));
select pg_temp.act_as(null);
select is((select count(*)::int from public."Wishlist" where event_id = 'a6a6a6a6-0000-0000-0000-0000000000e2'),
  0, 'wishlist without claims is deleted outright');

-- ------------------------------------------------------------ account deletion
select lives_ok($$ delete from auth.users where id = 'a6a6a6a6-0000-0000-0000-00000000000c' $$,
  'deleting a claim creator''s account still cascades');

select pg_temp.act_as(null);
select * from finish();
rollback;
