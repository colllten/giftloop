-- =============================================================================
-- Local development seed. Loaded by `supabase db reset`. Never run in prod.
-- All users sign in with password: password123
--   alice@example.com  admin of "Glover Family", has a wishlist
--   bob@example.com    member, claimed Alice's headphones with Carol (anonymous)
--   carol@example.com  member
--   dave@example.com   not in any group (use to check isolation)
-- =============================================================================

insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token
)
select
  '00000000-0000-0000-0000-000000000000', u.id, 'authenticated', 'authenticated', u.email,
  extensions.crypt('password123', extensions.gen_salt('bf')), now(),
  '{"provider":"email","providers":["email"]}',
  jsonb_build_object('username', u.username, 'display_name', u.display_name),
  now(), now(), '', '', '', ''
from (values
  ('00000000-0000-0000-0000-00000000a11c'::uuid, 'alice@example.com', 'alice', 'Alice'),
  ('00000000-0000-0000-0000-000000000b0b'::uuid, 'bob@example.com',   'bob',   'Bob'),
  ('00000000-0000-0000-0000-00000000ca01'::uuid, 'carol@example.com', 'carol', 'Carol'),
  ('00000000-0000-0000-0000-00000000da7e'::uuid, 'dave@example.com',  'dave',  'Dave')
) as u (id, email, username, display_name);

insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
select gen_random_uuid(), id, id::text,
       jsonb_build_object('sub', id::text, 'email', email, 'email_verified', true),
       'email', now(), now(), now()
from auth.users
where email in ('alice@example.com', 'bob@example.com', 'carol@example.com', 'dave@example.com');

-- Seed users skip onboarding.
update public."Profile" set onboarded_at = now()
where id in ('00000000-0000-0000-0000-00000000a11c', '00000000-0000-0000-0000-000000000b0b',
             '00000000-0000-0000-0000-00000000ca01', '00000000-0000-0000-0000-00000000da7e');

-- Group (trigger adds alice as admin).
insert into public."Group" (id, name, created_by)
values ('10000000-0000-0000-0000-000000000001', 'Glover Family', '00000000-0000-0000-0000-00000000a11c');

insert into public."GroupUser" (group_id, user_id) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000b0b'),
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000ca01');

insert into public."Event" (id, group_id, name, end_date, created_by)
values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        'Christmas 2026', '2026-12-26', '00000000-0000-0000-0000-00000000a11c');

insert into public."Wishlist" (id, event_id, owner_id) values
  ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000a11c'),
  ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000b0b');

insert into public."Gift" (id, wishlist_id, name, price, links, quantity, notes) values
  ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
   'Noise-cancelling headphones', 249.99, array['https://example.com/headphones'], 1, 'Black, over-ear'),
  ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001',
   'Wool socks', 15.00, null, 3, 'Size M'),
  ('40000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000002',
   'Cookbook', 30.00, null, 1, null);

-- Bob claims the headphones (trigger adds Bob as participant); Carol chips in anonymously.
insert into public."Claim" (id, gift_id, created_by, quantity)
values ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000b0b', 1);

insert into public."ClaimParticipant" (claim_id, user_id, is_anonymous)
values ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000ca01', true);
