-- Account deletion: owned groups pass to the oldest admin (else oldest member)
-- or are deleted when solo; shared claims pass to a co-buyer; wishlists are
-- removed, leaving ownerless shells for claimers.
-- Cast: deleting user (X), member (M), admin (A), Studio-deleted owner (Y).
-- Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(30);

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

create function pg_temp.code(p_group uuid) returns text
language sql security definer as $$
  select code from public."GroupJoinCode" where group_id = p_group;
$$;
grant execute on function pg_temp.code(uuid) to authenticated;

create function pg_temp.owner_of(p_group uuid) returns uuid
language sql as $$
  select user_id from public."GroupUser" where group_id = p_group and role = 'owner';
$$;

-- Rows of the gift / wishlist / gift's claims the current user can see.
create function pg_temp.sees_gift(p_gift uuid) returns int
language sql as $$ select count(*)::int from public."Gift" where id = p_gift; $$;
create function pg_temp.sees_wishlist(p_wishlist uuid) returns int
language sql as $$ select count(*)::int from public."Wishlist" where id = p_wishlist; $$;
create function pg_temp.sees_claims(p_gift uuid) returns int
language sql as $$ select count(*)::int from public."Claim" where gift_id = p_gift; $$;
grant execute on function pg_temp.sees_gift(uuid), pg_temp.sees_wishlist(uuid), pg_temp.sees_claims(uuid)
  to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('b8b8b8b8-0000-0000-0000-00000000000a', 'ad-deleter@example.com', '{"username":"ad_deleter"}'),
  ('b8b8b8b8-0000-0000-0000-00000000000b', 'ad-member@example.com',  '{"username":"ad_member"}'),
  ('b8b8b8b8-0000-0000-0000-00000000000c', 'ad-admin@example.com',   '{"username":"ad_admin"}'),
  ('b8b8b8b8-0000-0000-0000-00000000000d', 'ad-studio@example.com',  '{"username":"ad_studio"}');

-- ------------------------------------------------------------ fixtures
-- G1: X owns; M joined first, A joined later and is admin → A inherits.
-- G2: X owns; only M → M inherits.
-- G3: X alone → deleted.
-- G4: X owns, transfers to M before deleting → M stays owner.
-- G5: Y owns, A is the only other member; Y is deleted from Studio.
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values
  ('b8b8b8b8-0000-0000-0000-000000000001', 'AD One'),
  ('b8b8b8b8-0000-0000-0000-000000000002', 'AD Two'),
  ('b8b8b8b8-0000-0000-0000-000000000003', 'AD Solo'),
  ('b8b8b8b8-0000-0000-0000-000000000004', 'AD Chosen');
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000d');
insert into public."Group" (id, name) values ('b8b8b8b8-0000-0000-0000-000000000005', 'AD Studio');

select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code('b8b8b8b8-0000-0000-0000-000000000001'));
select private.join_group_by_code(pg_temp.code('b8b8b8b8-0000-0000-0000-000000000002'));
select private.join_group_by_code(pg_temp.code('b8b8b8b8-0000-0000-0000-000000000004'));
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code('b8b8b8b8-0000-0000-0000-000000000001'));
select private.join_group_by_code(pg_temp.code('b8b8b8b8-0000-0000-0000-000000000004'));
select private.join_group_by_code(pg_temp.code('b8b8b8b8-0000-0000-0000-000000000005'));

select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
update public."GroupUser" set role = 'admin'
where group_id = 'b8b8b8b8-0000-0000-0000-000000000001' and user_id = 'b8b8b8b8-0000-0000-0000-00000000000c';

-- One transaction shares now(), so set join order explicitly: M before A.
select pg_temp.act_as(null);
update public."GroupUser" set joined_at = now() - interval '2 days'
where user_id = 'b8b8b8b8-0000-0000-0000-00000000000b';
update public."GroupUser" set joined_at = now() - interval '1 day'
where user_id = 'b8b8b8b8-0000-0000-0000-00000000000c';

-- In G1's event: A's wishlist WA (ga1 solo-claimed by X, ga2 claimed by X with
-- M), and X's wishlist WX (gx1 unclaimed, gx2 claimed by M).
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
insert into public."Event" (id, group_id, name)
values ('b8b8b8b8-0000-0000-0000-0000000000e1', 'b8b8b8b8-0000-0000-0000-000000000001', 'AD Event');
insert into public."Wishlist" (id, event_id)
values ('b8b8b8b8-0000-0000-0000-0000000000a2', 'b8b8b8b8-0000-0000-0000-0000000000e1');
insert into public."Gift" (id, wishlist_id, name) values
  ('b8b8b8b8-0000-0000-0000-0000000000b1', 'b8b8b8b8-0000-0000-0000-0000000000a2', 'X unclaimed'),
  ('b8b8b8b8-0000-0000-0000-0000000000b2', 'b8b8b8b8-0000-0000-0000-0000000000a2', 'X claimed');

select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000c');
insert into public."Wishlist" (id, event_id)
values ('b8b8b8b8-0000-0000-0000-0000000000a1', 'b8b8b8b8-0000-0000-0000-0000000000e1');
insert into public."Gift" (id, wishlist_id, name) values
  ('b8b8b8b8-0000-0000-0000-0000000000f1', 'b8b8b8b8-0000-0000-0000-0000000000a1', 'Solo claim'),
  ('b8b8b8b8-0000-0000-0000-0000000000f2', 'b8b8b8b8-0000-0000-0000-0000000000a1', 'Shared claim');

select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
insert into public."Claim" (id, gift_id) values
  ('b8b8b8b8-0000-0000-0000-0000000000c1', 'b8b8b8b8-0000-0000-0000-0000000000f1'),
  ('b8b8b8b8-0000-0000-0000-0000000000c2', 'b8b8b8b8-0000-0000-0000-0000000000f2');
insert into public."ClaimParticipant" (claim_id, user_id)
values ('b8b8b8b8-0000-0000-0000-0000000000c2', 'b8b8b8b8-0000-0000-0000-00000000000b');

select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000b');
insert into public."Claim" (id, gift_id)
values ('b8b8b8b8-0000-0000-0000-0000000000c3', 'b8b8b8b8-0000-0000-0000-0000000000b2');

-- ------------------------------------------------------------ successor preview
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
select is(private.group_successor('b8b8b8b8-0000-0000-0000-000000000001'),
  'b8b8b8b8-0000-0000-0000-00000000000c'::uuid, 'an admin is preferred over an older member');
select is(private.group_successor('b8b8b8b8-0000-0000-0000-000000000002'),
  'b8b8b8b8-0000-0000-0000-00000000000b'::uuid, 'the oldest member inherits when there are no admins');
select is(private.group_successor('b8b8b8b8-0000-0000-0000-000000000003'),
  null, 'a solo group has no successor');
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000b');
select is(private.group_successor('b8b8b8b8-0000-0000-0000-000000000001'),
  null, 'non-owners get no successor preview');

-- ------------------------------------------------------------ guards
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
select throws_ok($$ select private.delete_my_account('someone_else') $$,
  '23514', 'Username does not match', 'wrong username is refused');
select throws_ok($$ select private.delete_my_account(null) $$,
  '23514', 'Username does not match', 'missing username is refused');
select pg_temp.act_as(null);
select throws_ok($$ select private.delete_my_account('ad_deleter') $$,
  '42501', 'Not signed in', 'signed-out caller is refused');
set local role anon;
select throws_ok($$ select private.delete_my_account('ad_deleter') $$,
  '42501', null, 'anon cannot call delete_my_account');
reset role;
select is((select count(*)::int from auth.users where id = 'b8b8b8b8-0000-0000-0000-00000000000a'),
  1, 'refused attempts delete nothing');

-- ------------------------------------------------------------ delete X
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000a');
select private.transfer_group_ownership('b8b8b8b8-0000-0000-0000-000000000004',
  'b8b8b8b8-0000-0000-0000-00000000000b');
select lives_ok($$ select private.delete_my_account('AD_Deleter') $$,
  'user deletes their own account (username ignores case)');

select pg_temp.act_as(null);
select is((select count(*)::int from auth.users where id = 'b8b8b8b8-0000-0000-0000-00000000000a'),
  0, 'auth user is gone');
select is((select count(*)::int from public."Profile" where id = 'b8b8b8b8-0000-0000-0000-00000000000a'),
  0, 'profile is gone');

-- Groups
select is(pg_temp.owner_of('b8b8b8b8-0000-0000-0000-000000000001'),
  'b8b8b8b8-0000-0000-0000-00000000000c'::uuid, 'G1 passes to the admin');
select is(pg_temp.owner_of('b8b8b8b8-0000-0000-0000-000000000002'),
  'b8b8b8b8-0000-0000-0000-00000000000b'::uuid, 'G2 passes to the oldest member');
select is((select count(*)::int from public."Group" where id = 'b8b8b8b8-0000-0000-0000-000000000003'),
  0, 'solo group is deleted');
select is(pg_temp.owner_of('b8b8b8b8-0000-0000-0000-000000000004'),
  'b8b8b8b8-0000-0000-0000-00000000000b'::uuid, 'chosen owner keeps G4');
select is((select role::text from public."GroupUser"
           where group_id = 'b8b8b8b8-0000-0000-0000-000000000004'
             and user_id = 'b8b8b8b8-0000-0000-0000-00000000000c'),
  'member', 'other G4 members keep their role');
select is((select count(*)::int from public."GroupUser" where user_id = 'b8b8b8b8-0000-0000-0000-00000000000a'),
  0, 'memberships are gone');

-- Claims X made
select is((select count(*)::int from public."Claim" where id = 'b8b8b8b8-0000-0000-0000-0000000000c1'),
  0, 'solo claim is released');
select is((select created_by from public."Claim" where id = 'b8b8b8b8-0000-0000-0000-0000000000c2'),
  'b8b8b8b8-0000-0000-0000-00000000000b'::uuid, 'shared claim passes to the co-buyer');
select is((select count(*)::int from public."ClaimParticipant" where claim_id = 'b8b8b8b8-0000-0000-0000-0000000000c2'),
  1, 'only the co-buyer is left on the shared claim');

-- X's wishlist
select is((select count(*)::int from public."Gift" where id = 'b8b8b8b8-0000-0000-0000-0000000000b1'),
  0, 'unclaimed gift is deleted');
select isnt((select removed_at from public."Gift" where id = 'b8b8b8b8-0000-0000-0000-0000000000b2'),
  null, 'claimed gift is marked removed');
select is((select owner_id from public."Wishlist" where id = 'b8b8b8b8-0000-0000-0000-0000000000a2'),
  null, 'wishlist shell is ownerless');
select isnt((select removed_at from public."Wishlist" where id = 'b8b8b8b8-0000-0000-0000-0000000000a2'),
  null, 'wishlist shell is removed');

select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000b');
select is(pg_temp.sees_wishlist('b8b8b8b8-0000-0000-0000-0000000000a2'), 1, 'claimer sees the ownerless shell');
select is(pg_temp.sees_gift('b8b8b8b8-0000-0000-0000-0000000000b2'), 1, 'claimer sees the removed gift');
select is(pg_temp.sees_claims('b8b8b8b8-0000-0000-0000-0000000000b2'), 1, 'claimer sees their claim on it');
select pg_temp.act_as('b8b8b8b8-0000-0000-0000-00000000000c');
select is(pg_temp.sees_gift('b8b8b8b8-0000-0000-0000-0000000000b2'), 0, 'non-claimers do not see the removed gift');

-- ------------------------------------------------------------ Studio path
select pg_temp.act_as(null);
delete from auth.users where id = 'b8b8b8b8-0000-0000-0000-00000000000d';
select is(pg_temp.owner_of('b8b8b8b8-0000-0000-0000-000000000005'),
  'b8b8b8b8-0000-0000-0000-00000000000c'::uuid, 'deleting the auth user directly still hands off the group');

select * from finish();
rollback;
