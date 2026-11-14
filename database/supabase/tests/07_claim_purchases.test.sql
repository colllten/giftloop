-- Claim purchases: any participant marks 0..quantity units bought; visibility
-- follows the claim (owner only after reveal).
-- Cast: owner (O), claimer (C, creates claims), co-buyer (D), bystander (B).
-- Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

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
  select code from public."GroupJoinCode" where group_id = 'a7a7a7a7-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- Purchased units on the claim as the current user sees it (null if hidden).
create function pg_temp.bought(p_claim uuid) returns int
language sql as $$
  select purchased_quantity from public."Claim" where id = p_claim;
$$;
grant execute on function pg_temp.bought(uuid) to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a7a7a7a7-0000-0000-0000-00000000000a', 'cp-owner@example.com',     '{"username":"cp_owner"}'),
  ('a7a7a7a7-0000-0000-0000-00000000000b', 'cp-bystander@example.com', '{"username":"cp_bystander"}'),
  ('a7a7a7a7-0000-0000-0000-00000000000c', 'cp-claimer@example.com',   '{"username":"cp_claimer"}'),
  ('a7a7a7a7-0000-0000-0000-00000000000d', 'cp-cobuyer@example.com',   '{"username":"cp_cobuyer"}');

-- ------------------------------------------------------------ fixtures
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('a7a7a7a7-0000-0000-0000-000000000001', 'CP Family');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());

-- g1: C claims 2 with D. g2: C claims 1 (later removed by the owner).
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
insert into public."Event" (id, group_id, name)
values ('a7a7a7a7-0000-0000-0000-0000000000e1', 'a7a7a7a7-0000-0000-0000-000000000001', 'CP Event');
insert into public."Wishlist" (id, event_id)
values ('a7a7a7a7-0000-0000-0000-0000000000a1', 'a7a7a7a7-0000-0000-0000-0000000000e1');
insert into public."Gift" (id, wishlist_id, name, quantity) values
  ('a7a7a7a7-0000-0000-0000-0000000000f1', 'a7a7a7a7-0000-0000-0000-0000000000a1', 'Socks', 3),
  ('a7a7a7a7-0000-0000-0000-0000000000f2', 'a7a7a7a7-0000-0000-0000-0000000000a1', 'Book', 1);

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000c');
insert into public."Claim" (id, gift_id, quantity) values
  ('a7a7a7a7-0000-0000-0000-0000000000c1', 'a7a7a7a7-0000-0000-0000-0000000000f1', 2),
  ('a7a7a7a7-0000-0000-0000-0000000000c2', 'a7a7a7a7-0000-0000-0000-0000000000f2', 1);
insert into public."ClaimParticipant" (claim_id, user_id)
values ('a7a7a7a7-0000-0000-0000-0000000000c1', 'a7a7a7a7-0000-0000-0000-00000000000d');

-- ------------------------------------------------------------ marking purchases
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c1'), 0, 'new claims start with nothing bought');
select lives_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c1', 1) $$,
  'claim creator marks units bought');
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c1'), 1, 'creator sees the purchased count');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000d');
select lives_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c1', 2) $$,
  'co-buyer marks units bought');
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c1'), 2, 'co-buyer sees the purchased count');
update public."Claim" set quantity = 5 where id = 'a7a7a7a7-0000-0000-0000-0000000000c1';
select is((select quantity from public."Claim" where id = 'a7a7a7a7-0000-0000-0000-0000000000c1'), 2,
  'co-buyer still cannot change the claimed quantity (RLS skips the row)');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000b');
select throws_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c1', 0) $$,
  '42501', null, 'member not on the claim cannot mark it');
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c1'), 2, 'other members see the purchased count');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
select throws_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c1', 0) $$,
  '42501', null, 'wishlist owner cannot mark it');
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c1'), null, 'owner cannot see it before reveal');
update public."Group" set is_claims_visible_to_owner = true where id = 'a7a7a7a7-0000-0000-0000-000000000001';
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c1'), 2, 'owner sees it after reveal');

-- ------------------------------------------------------------ range
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000c');
select throws_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c1', 3) $$,
  '23514', null, 'cannot mark more than claimed');
select throws_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c1', -1) $$,
  '23514', null, 'cannot mark a negative amount');
select throws_ok($$ update public."Claim" set quantity = 1 where id = 'a7a7a7a7-0000-0000-0000-0000000000c1' $$,
  '23514', null, 'creator cannot lower the claim below what is bought');
select throws_ok($$ update public."Claim" set purchased_quantity = 0 where id = 'a7a7a7a7-0000-0000-0000-0000000000c1' $$,
  '42501', null, 'no direct update of purchased_quantity');

-- ------------------------------------------------------------ removed gifts
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
select private.remove_gift('a7a7a7a7-0000-0000-0000-0000000000f2');
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000c');
select lives_ok($$ select private.set_claim_purchased('a7a7a7a7-0000-0000-0000-0000000000c2', 1) $$,
  'claimers can still mark a removed gift bought');
select is(pg_temp.bought('a7a7a7a7-0000-0000-0000-0000000000c2'), 1, 'removed gift purchase is recorded');

select * from finish();
rollback;
