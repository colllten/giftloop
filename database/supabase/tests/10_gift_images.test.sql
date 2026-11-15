-- Gift images: path check on Gift.image_path and gift-images storage policies.
-- Cast: owner (O), member (M), hidden member (H), outsider (X).
-- Self-contained fixtures.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

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

-- Gift-image objects of a gift the current user can read.
create function pg_temp.sees_image(p_gift uuid) returns int
language sql as $$
  select count(*)::int from storage.objects
  where bucket_id = 'gift-images' and name like '%/' || p_gift::text || '/%';
$$;
grant execute on function pg_temp.sees_image(uuid) to authenticated;

insert into auth.users (id, email, raw_user_meta_data) values
  ('a7a7a7a7-0000-0000-0000-00000000000a', 'gi-owner@example.com',    '{"username":"gi_owner"}'),
  ('a7a7a7a7-0000-0000-0000-00000000000b', 'gi-member@example.com',   '{"username":"gi_member"}'),
  ('a7a7a7a7-0000-0000-0000-00000000000c', 'gi-hidden@example.com',   '{"username":"gi_hidden"}'),
  ('a7a7a7a7-0000-0000-0000-00000000000d', 'gi-outsider@example.com', '{"username":"gi_outsider"}');

-- ------------------------------------------------------------ fixtures
-- g1 visible to all, g2 visible to M only.
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
insert into public."Group" (id, name) values ('a7a7a7a7-0000-0000-0000-000000000001', 'GI Family');
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000b');
select private.join_group_by_code(pg_temp.code());
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000c');
select private.join_group_by_code(pg_temp.code());

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
insert into public."Event" (id, group_id, name)
values ('a7a7a7a7-0000-0000-0000-0000000000e1', 'a7a7a7a7-0000-0000-0000-000000000001', 'GI Event');
insert into public."Wishlist" (id, event_id)
values ('a7a7a7a7-0000-0000-0000-0000000000a1', 'a7a7a7a7-0000-0000-0000-0000000000e1');
insert into public."Gift" (id, wishlist_id, name, is_visible_to_all) values
  ('a7a7a7a7-0000-0000-0000-0000000000f1', 'a7a7a7a7-0000-0000-0000-0000000000a1', 'Open gift', true),
  ('a7a7a7a7-0000-0000-0000-0000000000f2', 'a7a7a7a7-0000-0000-0000-0000000000a1', 'Hidden gift', false);
insert into public."GiftViewer" (gift_id, user_id)
values ('a7a7a7a7-0000-0000-0000-0000000000f2', 'a7a7a7a7-0000-0000-0000-00000000000b');

-- ------------------------------------------------------------ Gift.image_path
select has_column('public', 'Gift', 'image_path', 'Gift has image_path');
select throws_ok(
  $$ update public."Gift" set image_path = 'a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f2/x.jpg'
     where id = 'a7a7a7a7-0000-0000-0000-0000000000f1' $$,
  '23514', null, 'image_path must name its own gift'
);
select lives_ok(
  $$ update public."Gift" set image_path = 'a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f1/x.jpg'
     where id = 'a7a7a7a7-0000-0000-0000-0000000000f1' $$,
  'owner sets image_path for own gift'
);

-- ------------------------------------------------------------ uploads
select lives_ok(
  $$ insert into storage.objects (bucket_id, name) values
       ('gift-images', 'a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f1/x.jpg'),
       ('gift-images', 'a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f2/y.jpg') $$,
  'owner uploads to own gifts'
);
select throws_ok(
  $$ insert into storage.objects (bucket_id, name)
     values ('gift-images', 'a7a7a7a7-0000-0000-0000-00000000000b/a7a7a7a7-0000-0000-0000-0000000000f1/z.jpg') $$,
  '42501', null, 'owner cannot upload outside own folder'
);

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000b');
select throws_ok(
  $$ insert into storage.objects (bucket_id, name)
     values ('gift-images', 'a7a7a7a7-0000-0000-0000-00000000000b/a7a7a7a7-0000-0000-0000-0000000000f1/z.jpg') $$,
  '42501', null, 'non-owner cannot upload to a gift'
);
select is(
  (select count(*)::int from public."Gift" where id = 'a7a7a7a7-0000-0000-0000-0000000000f1' and image_path is not null),
  1, 'member reads image_path'
);

-- ------------------------------------------------------------ reads
select is(pg_temp.sees_image('a7a7a7a7-0000-0000-0000-0000000000f1'), 1, 'member sees open gift image');
select is(pg_temp.sees_image('a7a7a7a7-0000-0000-0000-0000000000f2'), 1, 'allowed viewer sees hidden gift image');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000c');
select is(pg_temp.sees_image('a7a7a7a7-0000-0000-0000-0000000000f1'), 1, 'other member sees open gift image');
select is(pg_temp.sees_image('a7a7a7a7-0000-0000-0000-0000000000f2'), 0, 'non-viewer cannot see hidden gift image');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000d');
select is(pg_temp.sees_image('a7a7a7a7-0000-0000-0000-0000000000f1'), 0, 'outsider cannot see gift image');

-- ------------------------------------------------------------ orphan queue
select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000b');
select is((select count(*)::int from private.take_gift_image_orphans()), 0, 'member has no orphans');

select pg_temp.act_as('a7a7a7a7-0000-0000-0000-00000000000a');
update public."Gift" set image_path = 'a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f1/new.jpg'
where id = 'a7a7a7a7-0000-0000-0000-0000000000f1';
select results_eq(
  'select * from private.take_gift_image_orphans()',
  $$ values ('a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f1/x.jpg') $$,
  'replacing an image queues the old file'
);
select is((select count(*)::int from private.take_gift_image_orphans()), 0, 'taking drains the queue');
select private.remove_gift('a7a7a7a7-0000-0000-0000-0000000000f1');
select results_eq(
  'select * from private.take_gift_image_orphans()',
  $$ values ('a7a7a7a7-0000-0000-0000-00000000000a/a7a7a7a7-0000-0000-0000-0000000000f1/new.jpg') $$,
  'deleting a gift queues its file'
);
select throws_ok(
  $$ select * from public."GiftImageOrphan" $$,
  '42501', null, 'queue is not readable directly'
);

-- ------------------------------------------------------------ helpers
select pg_temp.act_as(null);
select ok(private.gift_image_gift('a7a7a7a7-0000-0000-0000-00000000000a/not-a-uuid/x.jpg') is null,
          'malformed path has no gift');
select ok(private.gift_image_owner('nope/x/y.jpg') is null, 'malformed path has no owner');

select * from finish();
rollback;
