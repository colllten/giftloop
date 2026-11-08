-- Behavioural RLS tests. Self-contained fixtures; does not depend on seed.sql.
-- Cast: owner (O), admin (A, creates group), member (M, claims), co-buyer (C,
-- anonymous), bystander (B, member not on claim), outsider (X, no group).
begin;
create extension if not exists pgtap with schema extensions;
select plan(25);

-- Acts as the given user for subsequent statements (null = back to postgres).
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

-- ---------------------------------------------------------------- fixtures
insert into auth.users (id, email, raw_user_meta_data) values
  ('11111111-0000-0000-0000-00000000000a', 'test-owner@example.com',     '{"username":"t_owner"}'),
  ('11111111-0000-0000-0000-00000000000b', 'test-admin@example.com',     '{"username":"t_admin"}'),
  ('11111111-0000-0000-0000-00000000000c', 'test-member@example.com',    '{"username":"t_member"}'),
  ('11111111-0000-0000-0000-00000000000d', 'test-cobuyer@example.com',   '{"username":"t_cobuyer"}'),
  ('11111111-0000-0000-0000-00000000000e', 'test-bystander@example.com', '{"username":"t_bystander"}'),
  ('11111111-0000-0000-0000-00000000000f', 'Weird.Name+x@example.com',   '{}');

select is(
  (select count(*)::int from public."Profile" where id::text like '11111111-%'),
  6, 'signup trigger creates a Profile per auth user'
);
select ok(
  (select username::text from public."Profile" where id = '11111111-0000-0000-0000-00000000000f') ~ '^weirdnamex_[0-9a-f]{8}$',
  'username falls back to sanitized email local part + id suffix'
);

-- Admin creates the group through RLS (exercises INSERT ... RETURNING).
select pg_temp.act_as('11111111-0000-0000-0000-00000000000b');
select lives_ok(
  $$ insert into public."Group" (id, name) values ('22222222-0000-0000-0000-000000000001', 'Test Family') returning id $$,
  'authenticated user can create a group (with RETURNING)'
);
select is(
  (select role::text from public."GroupUser"
   where group_id = '22222222-0000-0000-0000-000000000001' and user_id = '11111111-0000-0000-0000-00000000000b'),
  'admin', 'group creator becomes admin'
);
select lives_ok(
  $$ insert into public."GroupUser" (group_id, user_id) values
       ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000a'),
       ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000c'),
       ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000d'),
       ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000e') $$,
  'admin can add members'
);
select lives_ok(
  $$ insert into public."Event" (id, group_id, name, end_date)
     values ('33333333-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001', 'Holiday', current_date + 30) $$,
  'member can create an event'
);

-- Non-admin cannot add members.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000c');
select throws_ok(
  $$ insert into public."GroupUser" (group_id, user_id)
     values ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000f') $$,
  '42501', null, 'non-admin cannot add members'
);

-- Owner builds a wishlist.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000a');
select lives_ok(
  $$ insert into public."Wishlist" (id, event_id) values ('44444444-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000001');
     insert into public."Gift" (id, wishlist_id, name, quantity) values ('55555555-0000-0000-0000-000000000001', '44444444-0000-0000-0000-000000000001', 'Kettle', 1) $$,
  'owner can create wishlist and gift'
);
select throws_ok(
  $$ insert into public."Claim" (gift_id) values ('55555555-0000-0000-0000-000000000001') $$,
  '23514', null, 'owner cannot claim own gift (trigger fires before RLS check)'
);

-- Member claims, adds co-buyer anonymously.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000c');
select lives_ok(
  $$ insert into public."Claim" (id, gift_id, quantity) values ('66666666-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000001', 1) returning id, quantity $$,
  'member can claim (with RETURNING)'
);
select lives_ok(
  $$ insert into public."ClaimParticipant" (claim_id, user_id, is_anonymous)
     values ('66666666-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000d', true) $$,
  'claim creator can add anonymous co-buyer'
);
select throws_ok(
  $$ insert into public."ClaimParticipant" (claim_id, user_id)
     values ('66666666-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000a') $$,
  '23514', null, 'wishlist owner cannot be added as participant'
);
select throws_ok(
  $$ insert into public."ClaimParticipant" (claim_id, user_id)
     values ('66666666-0000-0000-0000-000000000001', '11111111-0000-0000-0000-00000000000f') $$,
  '23514', null, 'non-member cannot be added as participant'
);
select is((select count(*)::int from public."ClaimParticipant"), 2, 'creator sees both participants');
select throws_ok(
  $$ select created_by from public."Claim" $$,
  '42501', null, 'Claim.created_by cannot be selected'
);

-- Co-buyer (participant) sees everyone on the claim.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000d');
select is((select count(*)::int from public."ClaimParticipant"), 2, 'anonymous participant sees fellow participants');

-- Bystander sees the claim but not the anonymous co-buyer.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000e');
select is((select count(*)::int from public."Claim"), 1, 'bystander sees the claim');
select is(
  (select array_agg(user_id) from public."ClaimParticipant"),
  array['11111111-0000-0000-0000-00000000000c'::uuid],
  'bystander does not see anonymous participant'
);

-- Owner sees nothing before the event ends.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000a');
select is((select count(*)::int from public."Claim"), 0, 'owner cannot see claims before event ends');
select is((select count(*)::int from public."GiftClaimSummary"), 0, 'owner summary is empty before event ends');

-- Admin flips the group reveal flag.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000b');
update public."Group" set is_claims_visible_to_owner = true where id = '22222222-0000-0000-0000-000000000001';
select pg_temp.act_as('11111111-0000-0000-0000-00000000000a');
select is((select count(*)::int from public."Claim"), 1, 'owner sees claims when group reveal is on');
select is((select count(*)::int from public."ClaimParticipant"), 1, 'owner still cannot see anonymous participant');

-- Reveal off, event ended.
select pg_temp.act_as(null);
update public."Group" set is_claims_visible_to_owner = false where id = '22222222-0000-0000-0000-000000000001';
update public."Event" set end_date = current_date - 1 where id = '33333333-0000-0000-0000-000000000001';
select pg_temp.act_as('11111111-0000-0000-0000-00000000000a');
select is((select count(*)::int from public."Claim"), 1, 'owner sees claims after event ends');

-- Outsider sees nothing and cannot join an event's wishlists.
select pg_temp.act_as('11111111-0000-0000-0000-00000000000f');
select is(
  (select count(*)::int from public."Group") + (select count(*)::int from public."Gift") + (select count(*)::int from public."Claim"),
  0, 'outsider sees no groups, gifts or claims'
);
select throws_ok(
  $$ insert into public."Wishlist" (event_id) values ('33333333-0000-0000-0000-000000000001') $$,
  '23514', null, 'outsider cannot create a wishlist in the event (trigger fires before RLS check)'
);

select pg_temp.act_as(null);
select * from finish();
rollback;
