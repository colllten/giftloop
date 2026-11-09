-- Schema-level guarantees: tables exist, RLS on everywhere, anon has no access.
begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

select has_table('public', t, format('table %s exists', t))
from unnest(array['Profile','Group','GroupUser','Event','Wishlist','Gift','Claim','ClaimParticipant']) as t;

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

select has_view('public', 'GiftClaimSummary', 'GiftClaimSummary view exists');

select ok(
  not has_schema_privilege('anon', 'private', 'USAGE'),
  'anon cannot use private schema'
);

select * from finish();
rollback;
