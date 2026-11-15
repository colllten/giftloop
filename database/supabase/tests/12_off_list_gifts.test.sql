-- Off-list gifts: a giver records something not on the list. Others see it;
-- the owner only after reveal. Only the giver (claim creator) edits it;
-- releasing the claim deletes it.
-- Cast: owner (O), giver (G), co-buyer (C), bystander (B), outsider (X).
-- Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(29);

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
  select code from public."GroupJoinCode" where group_id = 'a9a9a9a9-0000-0000-0000-000000000001';
$$;
grant execute on function pg_temp.code() to authenticated;

-- Gift ids, as the caller, created by add_off_list_gift (session var).
create function pg_temp.gift() returns uuid
language sql as $$ select current_setting('test.gift')::uuid; $$;
grant execute on function pg_temp.gift() to authenticated;

-- Gift rows / claims the caller can see for the off-list gift.
create function pg_temp.sees_gift() returns bigint
language sql as $$ select count(*) from public."Gift" where id = pg_temp.gift(); $$;
grant execute on function pg_temp.sees_gift() to authenticated;
create function pg_temp.sees_claims() returns bigint
language sql as $$ select count(*) from public."Claim" where gift_id = pg_temp.gift(); $$;
grant execute on function pg_temp.sees_claims() to authenticated;

-- Ground truth, bypassing RLS.
create function pg_temp.gift_exists() returns boolean
language sql security definer as $$ select exists (select 1 from public."Gift" where id = pg_temp.gift()); $$;
grant execute on function pg_temp.gift_exists() to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a9a9a9a9-0000-0000-0000-00000000000a', 'ol-owner@example.com',     '{"username":"ol_owner"}'),
  ('a9a9a9a9-0000-0000-0000-00000000000b', 'ol-bystander@example.com', '{"username":"ol_bystander"}'),
  ('a9a9a9a9-0000-0000-0000-00000000000c', 'ol-cobuyer@example.com',   '{"username":"ol_cobuyer"}'),
  ('a9a9a9a9-0000-0000-0000-00000000000d', 'ol-giver@example.com',     '{"username":"ol_giver"}'),
  ('a9a9a9a9-0000-0000-0000-00000000000e', 'ol-outsider@example.com',  '{"username":"ol_outsider"}');

-- ------------------------------------------------------------ fixtures
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('a9a9a9a9-0000-0000-0000-000000000001', 'OL Family');

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000d');
select private.join_group_by_code(pg_temp.code());

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000a');
insert into public."Event" (id, group_id, name)
values ('a9a9a9a9-0000-0000-0000-0000000000e1', 'a9a9a9a9-0000-0000-0000-000000000001', 'OL Event');
insert into public."Wishlist" (id, event_id)
values ('a9a9a9a9-0000-0000-0000-0000000000a1', 'a9a9a9a9-0000-0000-0000-0000000000e1');

-- ------------------------------------------------------------ adding
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000a');
select throws_ok($$ select private.add_off_list_gift('a9a9a9a9-0000-0000-0000-0000000000a1', 'Mine', null, null, null) $$,
  '42501', null, 'owner cannot add an off-list gift to their own list');
select throws_ok($$ insert into public."Gift" (wishlist_id, name, is_off_list)
                    values ('a9a9a9a9-0000-0000-0000-0000000000a1', 'Sneaky', true) $$,
  '42501', null, 'owner cannot insert an off-list gift directly');

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000e');
select throws_ok($$ select private.add_off_list_gift('a9a9a9a9-0000-0000-0000-0000000000a1', 'Nope', null, null, null) $$,
  '42501', null, 'outsider cannot add an off-list gift');

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000d');
select lives_ok($$ select set_config('test.gift',
    private.add_off_list_gift('a9a9a9a9-0000-0000-0000-0000000000a1', 'Scarf', 25, array['https://example.com/scarf'], 'Red')::text,
    true) $$,
  'member adds an off-list gift');
select is((select is_off_list from public."Gift" where id = pg_temp.gift()), true, 'gift is flagged off-list');
select is((select private.is_claim_creator(id) from public."Claim" where gift_id = pg_temp.gift()), true,
  'giver holds its claim');
select is((select count(*) from public."ClaimParticipant" cp join public."Claim" c on c.id = cp.claim_id
           where c.gift_id = pg_temp.gift() and cp.user_id = 'a9a9a9a9-0000-0000-0000-00000000000d'), 1::bigint,
  'giver is a participant');

-- ------------------------------------------------------------ visibility
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000b');
select is(pg_temp.sees_gift(), 1::bigint, 'bystander sees the off-list gift');
select is(pg_temp.sees_claims(), 1::bigint, 'bystander sees its claim');
select throws_ok($$ insert into public."Claim" (gift_id) values (pg_temp.gift()) $$,
  '23514', null, 'others cannot claim an off-list gift (single claim)');

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000e');
select is(pg_temp.sees_gift(), 0::bigint, 'outsider does not see it');

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000a');
select is(pg_temp.sees_gift(), 0::bigint, 'owner does not see it before reveal');
select is(private.can_view_gift(pg_temp.gift()), false, 'can_view_gift is false for the owner before reveal');
select throws_ok($$ select private.remove_gift(pg_temp.gift()) $$,
  '42501', null, 'owner cannot remove it');
update public."Gift" set name = 'Owner edit' where id = pg_temp.gift();
select throws_ok($$ insert into public."GiftViewer" (gift_id, user_id)
                    values (pg_temp.gift(), 'a9a9a9a9-0000-0000-0000-00000000000b') $$,
  '42501', null, 'owner cannot restrict who sees it');

-- ------------------------------------------------------------ co-buyers and editing
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000d');
select is((select name from public."Gift" where id = pg_temp.gift()), 'Scarf', 'owner update did not apply');
select lives_ok($$ insert into public."ClaimParticipant" (claim_id, user_id)
                   select id, 'a9a9a9a9-0000-0000-0000-00000000000c' from public."Claim" where gift_id = pg_temp.gift() $$,
  'giver adds a co-buyer');
select lives_ok($$ select private.update_off_list_gift(pg_temp.gift(), 'Wool scarf', 30, null, null) $$,
  'giver edits it');
select is((select name from public."Gift" where id = pg_temp.gift()), 'Wool scarf', 'edit saved');
select throws_ok($$ update public."Gift" set is_off_list = false where id = pg_temp.gift() $$,
  '42501', null, 'off-list flag cannot be changed');

select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000c');
select throws_ok($$ select private.update_off_list_gift(pg_temp.gift(), 'Hat', null, null, null) $$,
  '42501', null, 'co-buyer cannot edit it');
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000b');
select throws_ok($$ select private.update_off_list_gift(pg_temp.gift(), 'Hat', null, null, null) $$,
  '42501', null, 'bystander cannot edit it');

-- ------------------------------------------------------------ reveal
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000a');
update public."Group" set is_claims_visible_to_owner = true where id = 'a9a9a9a9-0000-0000-0000-000000000001';
select is(pg_temp.sees_gift(), 1::bigint, 'owner sees it after reveal');
select is(pg_temp.sees_claims(), 1::bigint, 'owner sees its claim after reveal');
select throws_ok($$ select private.update_off_list_gift(pg_temp.gift(), 'Hat', null, null, null) $$,
  '42501', null, 'owner still cannot edit it after reveal');
update public."Group" set is_claims_visible_to_owner = false where id = 'a9a9a9a9-0000-0000-0000-000000000001';

-- ------------------------------------------------------------ release and removal
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000d');
delete from public."Claim" where gift_id = pg_temp.gift();
select is(pg_temp.gift_exists(), false, 'releasing the claim deletes the off-list gift');

select set_config('test.gift',
  private.add_off_list_gift('a9a9a9a9-0000-0000-0000-0000000000a1', 'Mug', null, null, null)::text, true);
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000a');
select private.remove_wishlist('a9a9a9a9-0000-0000-0000-0000000000a1');
select pg_temp.act_as('a9a9a9a9-0000-0000-0000-00000000000d');
select is((select removed_at is not null from public."Gift" where id = pg_temp.gift()), true,
  'removing the wishlist keeps the off-list gift as removed for its giver');
select throws_ok($$ select private.add_off_list_gift('a9a9a9a9-0000-0000-0000-0000000000a1', 'Late', null, null, null) $$,
  '42501', null, 'cannot add to a removed wishlist');
select throws_ok($$ select private.update_off_list_gift(pg_temp.gift(), 'Cup', null, null, null) $$,
  '42501', null, 'cannot edit a removed off-list gift');

select * from finish();
rollback;
