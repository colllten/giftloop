-- Profile onboarding columns, username availability and avatar storage policies.
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

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

-- Users: U (self), M (co-member), X (outsider).
insert into auth.users (id, email, raw_user_meta_data) values
  ('77777777-0000-0000-0000-00000000000a', 'po-self@example.com',     '{"username":"po_self"}'),
  ('77777777-0000-0000-0000-00000000000b', 'po-member@example.com',   '{"username":"po_member"}'),
  ('77777777-0000-0000-0000-00000000000c', 'po-outsider@example.com', '{"username":"po_outsider"}');
insert into public."Group" (id, name, created_by)
values ('88888888-0000-0000-0000-000000000001', 'PO Group', '77777777-0000-0000-0000-00000000000a');
insert into public."GroupUser" (group_id, user_id)
values ('88888888-0000-0000-0000-000000000001', '77777777-0000-0000-0000-00000000000b');
insert into storage.objects (bucket_id, name, owner_id)
values ('avatars', '77777777-0000-0000-0000-00000000000a/avatar-1.jpg', '77777777-0000-0000-0000-00000000000a');

select pg_temp.act_as('77777777-0000-0000-0000-00000000000a');

-- Onboarding constraints.
select throws_ok(
  $$ update public."Profile" set onboarded_at = now() where id = '77777777-0000-0000-0000-00000000000a' $$,
  '23514', null, 'cannot onboard without a display name'
);
select lives_ok(
  $$ update public."Profile" set display_name = 'Self', onboarded_at = now(), birth_month = 2, birth_day = 29
     where id = '77777777-0000-0000-0000-00000000000a' $$,
  'onboard with display name and Feb 29 birthday'
);
select throws_ok(
  $$ update public."Profile" set birth_month = 2, birth_day = 30 where id = '77777777-0000-0000-0000-00000000000a' $$,
  '23514', null, 'Feb 30 rejected'
);
select throws_ok(
  $$ update public."Profile" set birth_month = 5, birth_day = null where id = '77777777-0000-0000-0000-00000000000a' $$,
  '23514', null, 'month without day rejected'
);
select throws_ok(
  $$ update public."Profile" set avatar_path = '77777777-0000-0000-0000-00000000000b/x.jpg' where id = '77777777-0000-0000-0000-00000000000a' $$,
  '23514', null, 'avatar_path must be in own folder'
);
select lives_ok(
  $$ update public."Profile" set avatar_path = '77777777-0000-0000-0000-00000000000a/avatar-1.jpg' where id = '77777777-0000-0000-0000-00000000000a' $$,
  'avatar_path in own folder accepted'
);

-- Username availability.
select ok(private.is_username_available('po_self'), 'own username counts as available');
select ok(not private.is_username_available('PO_MEMBER'), 'taken username (other case) is unavailable');
select ok(not private.is_username_available('po_outsider'), 'usernames outside my groups are still checked');
select ok(private.is_username_available('brand_new_name'), 'unused username is available');

-- Avatar storage.
select is((select count(*)::int from storage.objects where bucket_id = 'avatars' and name like '77777777-%'), 1, 'owner sees own avatar');
select throws_ok(
  $$ insert into storage.objects (bucket_id, name) values ('avatars', '77777777-0000-0000-0000-00000000000b/avatar-2.jpg') $$,
  '42501', null, 'cannot upload into another user''s folder'
);
select lives_ok(
  $$ insert into storage.objects (bucket_id, name) values ('avatars', '77777777-0000-0000-0000-00000000000a/avatar-2.jpg') $$,
  'can upload into own folder'
);

select pg_temp.act_as('77777777-0000-0000-0000-00000000000b');
select is((select count(*)::int from storage.objects where bucket_id = 'avatars' and name like '77777777-0000-0000-0000-00000000000a/%'), 2, 'co-member can read avatars');

select pg_temp.act_as('77777777-0000-0000-0000-00000000000c');
select is((select count(*)::int from storage.objects where bucket_id = 'avatars' and name like '77777777-%'), 0, 'outsider cannot read avatars');
select ok(private.avatar_owner('not-a-uuid/x.jpg') is null, 'malformed avatar path has no owner');

select pg_temp.act_as(null);
select * from finish();
rollback;
