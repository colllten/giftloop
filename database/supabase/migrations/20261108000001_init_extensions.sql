-- =============================================================================
-- Migration: init_extensions
-- Why: Base objects the core schema depends on.
--   - citext for case-insensitive usernames.
--   - `private` schema for helper/trigger functions. It is NOT exposed through
--     the Data API (see config.toml [api].schemas), so nothing here is callable
--     over REST. `authenticated` gets USAGE only so RLS policies can call it.
--   - group_role enum for GroupUser.role.
-- =============================================================================

create extension if not exists citext with schema extensions;

create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create type public.group_role as enum ('admin', 'member');

-- Sets updated_at = now() on every UPDATE. Attach as a BEFORE UPDATE trigger.
create function private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

revoke execute on function private.set_updated_at() from public;
