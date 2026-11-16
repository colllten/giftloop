-- Schema-level guarantees: tables exist, RLS on everywhere, anon has no access.
begin;
create extension if not exists pgtap with schema extensions;
select plan(28);

select has_table('public', t, format('table %s exists', t))
from unnest(array['Profile','Group','GroupUser','GroupJoinCode','Event','Wishlist','Gift','Claim','ClaimParticipant','JoinCodeFailure','GiftViewer','ExchangeParticipant','ExchangeExclusion','ExchangeAssignment']) as t;

select is(
  (select count(*)::int from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity),
  0,
  'every public table has RLS enabled'
);

select is(
  (select count(*)::int from information_schema.role_table_grants
   where table_schema = 'public' and grantee = 'anon'),
  0,
  'anon has no table grants'
);

select ok(
  not has_column_privilege('authenticated', 'public."Claim"', 'created_by', 'SELECT'),
  'Claim.created_by is not readable by authenticated'
);

select ok(
  not has_column_privilege('authenticated', 'public."Claim"', 'purchased_quantity', 'UPDATE'),
  'Claim.purchased_quantity has no direct update (private.set_claim_purchased only)'
);

select has_function('private', 'set_claim_purchased', array['uuid', 'integer'],
  'private.set_claim_purchased exists');

select has_view('public', 'GiftClaimSummary', 'GiftClaimSummary view exists');

select ok(
  not has_schema_privilege('anon', 'private', 'USAGE'),
  'anon cannot use private schema'
);

select ok(
  not has_table_privilege('authenticated', 'public."GroupUser"', 'INSERT'),
  'GroupUser has no direct insert (join via code only)'
);

select ok(
  not has_table_privilege('authenticated', 'public."JoinCodeFailure"', 'SELECT, INSERT, UPDATE, DELETE'),
  'JoinCodeFailure is not accessible to authenticated'
);

select ok(
  not has_table_privilege('authenticated', 'public."Gift"', 'DELETE'),
  'Gift has no direct delete (private.remove_gift only)'
);

select ok(
  not has_table_privilege('authenticated', 'public."Wishlist"', 'DELETE'),
  'Wishlist has no direct delete (private.remove_wishlist only)'
);

select ok(
  not has_table_privilege('authenticated', 'public."ExchangeAssignment"', 'INSERT, UPDATE, DELETE'),
  'ExchangeAssignment has no direct writes (private.draw_exchange only)'
);

select ok(
  not has_column_privilege('authenticated', 'public."Event"', 'drawn_at', 'UPDATE'),
  'Event.drawn_at has no direct update (draw functions only)'
);

select has_function('private', 'draw_exchange', array['uuid'], 'private.draw_exchange exists');

select * from finish();
rollback;
