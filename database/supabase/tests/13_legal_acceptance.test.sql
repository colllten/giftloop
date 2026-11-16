-- Terms acceptance: only private.accept_terms can record it, for the caller only.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

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

insert into auth.users (id, email, raw_user_meta_data) values
  ('99999999-0000-0000-0000-00000000000a', 'la-self@example.com',  '{"username":"la_self"}'),
  ('99999999-0000-0000-0000-00000000000b', 'la-other@example.com', '{"username":"la_other"}');

select is((select terms_version from public."Profile" where id = '99999999-0000-0000-0000-00000000000a'),
          null, 'new profiles have not accepted');

select pg_temp.act_as('99999999-0000-0000-0000-00000000000a');

select throws_ok(
  $$ update public."Profile" set terms_version = '2099-01-01', terms_accepted_at = now()
     where id = '99999999-0000-0000-0000-00000000000a' $$,
  '42501', null, 'cannot set acceptance with a direct update'
);
select lives_ok($$ select private.accept_terms('2026-11-16') $$, 'accept_terms runs for a signed-in user');
select is((select terms_version from public."Profile" where id = '99999999-0000-0000-0000-00000000000a'),
          '2026-11-16', 'own acceptance recorded');
select throws_ok($$ select private.accept_terms(repeat('x', 21)) $$, '23514', null, 'over-long version rejected');

select pg_temp.act_as(null);
select is((select terms_accepted_at from public."Profile" where id = '99999999-0000-0000-0000-00000000000b'),
          null, 'other users are untouched');

set local role anon;
select throws_ok($$ select private.accept_terms('2026-11-16') $$, '42501', null, 'anon cannot accept');
reset role;

select * from finish();
rollback;
