-- =============================================================================
-- Migration: profile_onboarding
-- Why: Users finish their profile after signup (onboarding) and can edit it.
--   - onboarded_at marks completion; display_name is required once onboarded.
--   - Birthday is month/day only (year never collected).
--   - Avatars live in a private Storage bucket, readable by the owner and
--     people who share a group with them (same rule as Profile).
--   - is_username_available lets the UI check uniqueness without exposing
--     Profiles outside the caller's groups.
-- =============================================================================

alter table public."Profile"
  add column onboarded_at timestamptz,
  add column birth_month  smallint check (birth_month between 1 and 12),
  add column birth_day    smallint check (birth_day between 1 and 31),
  add column avatar_path  text check (char_length(avatar_path) <= 200),
  add constraint profile_birthday_complete
    check ((birth_month is null) = (birth_day is null)),
  add constraint profile_birthday_valid
    check (birth_day is null
           or birth_day <= (array[31,29,31,30,31,30,31,31,30,31,30,31])[birth_month]),
  add constraint profile_onboarded_has_display_name
    check (onboarded_at is null or display_name is not null),
  add constraint profile_avatar_in_own_folder
    check (avatar_path is null or avatar_path like id::text || '/%');

grant update (birth_month, birth_day, avatar_path, onboarded_at) on public."Profile" to authenticated;

-- True if no other user has this username (case-insensitive). The caller's own
-- current username counts as available.
create function private.is_username_available(p_username text)
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select not exists (
    select 1 from public."Profile"
    -- Explicit operator: with an empty search_path a bare `=` falls back to
    -- case-sensitive text equality instead of citext's.
    where username operator(extensions.=) p_username::extensions.citext
      and id <> (select auth.uid())
  );
$$;

revoke execute on function private.is_username_available(text) from public;
grant execute on function private.is_username_available(text) to authenticated;

-- -----------------------------------------------------------------------------
-- Avatar storage: objects at avatars/<user id>/<file>
-- -----------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', false, 2097152, array['image/jpeg']);

-- Returns the owner id encoded in an avatar object path, or null if malformed.
create function private.avatar_owner(p_name text)
returns uuid
language plpgsql immutable
set search_path = ''
as $$
begin
  return split_part(p_name, '/', 1)::uuid;
exception when invalid_text_representation then
  return null;
end;
$$;

revoke execute on function private.avatar_owner(text) from public;
grant execute on function private.avatar_owner(text) to authenticated;

create policy "Avatars: read own or co-member" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'avatars'
    and (private.avatar_owner(name) = (select auth.uid())
         or private.shares_group_with(private.avatar_owner(name)))
  );

create policy "Avatars: upload own" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'avatars' and private.avatar_owner(name) = (select auth.uid()));

create policy "Avatars: update own" on storage.objects
  for update to authenticated
  using (bucket_id = 'avatars' and private.avatar_owner(name) = (select auth.uid()))
  with check (bucket_id = 'avatars' and private.avatar_owner(name) = (select auth.uid()));

create policy "Avatars: delete own" on storage.objects
  for delete to authenticated
  using (bucket_id = 'avatars' and private.avatar_owner(name) = (select auth.uid()));
