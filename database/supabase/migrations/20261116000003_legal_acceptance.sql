-- =============================================================================
-- Migration: legal_acceptance
-- Why: Users must accept the Terms of Service and Privacy Policy (sign-up
--   checkbox, or /legal/accept when the documents change).
--   - terms_version is the accepted document version (the app's
--     giftloop.legal.version, a date like 2026-11-16); terms_accepted_at is when.
--   - No column grant: users can't forge acceptance with a direct update.
--     private.accept_terms is the only writer and stamps the server time.
-- =============================================================================

alter table public."Profile"
  add column terms_version     text check (char_length(terms_version) between 1 and 20),
  add column terms_accepted_at timestamptz,
  add constraint profile_terms_complete
    check ((terms_version is null) = (terms_accepted_at is null));

-- Records that the caller accepted the given document version, now.
create function private.accept_terms(p_version text)
returns void
language plpgsql security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;
  update public."Profile"
  set terms_version = p_version, terms_accepted_at = now()
  where id = (select auth.uid());
end;
$$;

revoke execute on function private.accept_terms(text) from public;
grant execute on function private.accept_terms(text) to authenticated;
