-- =============================================================================
-- Migration: gift_images
-- Why: Gifts get an optional photo, auto-filled from a product link or uploaded
--   by the owner. The app stores a re-encoded copy (never a hotlink).
--   - Objects live at gift-images/<owner id>/<gift id>/<file>.jpg. The file
--     name is random, so a replaced photo gets a new URL (safe to cache).
--   - Read follows Gift RLS: whoever can see the gift can see its photo,
--     including claimers of a soft-removed gift.
--   - Only the owner of a live gift can upload to it, in their own folder.
--     Delete needs only the folder, so files can be cleaned up after the
--     gift row is gone.
--   - The owner can't tell a deleted gift from a removed one (claimers keep
--     the photo), and purges happen in triggers. So replaced or deleted paths
--     are queued in "GiftImageOrphan"; the app drains the caller's queue with
--     take_gift_image_orphans() and deletes those files through Storage.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tables
-- -----------------------------------------------------------------------------

alter table public."Gift"
  add column image_path text,
  add constraint gift_image_path_own_gift
    check (image_path is null
           or (char_length(image_path) <= 200 and split_part(image_path, '/', 2) = id::text));

grant update (image_path) on public."Gift" to authenticated;

-- Files no gift points at any more, waiting for their owner to delete them.
-- owner_id has no FK: rows outlive a deleted account (see Known Gaps).
create table public."GiftImageOrphan" (
  path       text primary key,
  owner_id   uuid not null,
  created_at timestamptz not null default now()
);

create index giftimageorphan_owner_id on public."GiftImageOrphan" (owner_id);

-- Only the trigger and take_gift_image_orphans (security definer) touch this
-- table. RLS is on with no policies on purpose: deny all to API roles.
revoke all on public."GiftImageOrphan" from anon, authenticated;
alter table public."GiftImageOrphan" enable row level security;

-- -----------------------------------------------------------------------------
-- Storage
-- -----------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('gift-images', 'gift-images', false, 1048576, array['image/jpeg']);

-- Owner id encoded in a gift image path, or null if malformed.
create function private.gift_image_owner(p_name text)
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

-- Gift id encoded in a gift image path, or null if malformed.
create function private.gift_image_gift(p_name text)
returns uuid
language plpgsql immutable
set search_path = ''
as $$
begin
  return split_part(p_name, '/', 2)::uuid;
exception when invalid_text_representation then
  return null;
end;
$$;

revoke execute on function private.gift_image_owner(text) from public;
revoke execute on function private.gift_image_gift(text) from public;
grant execute on function private.gift_image_owner(text) to authenticated;
grant execute on function private.gift_image_gift(text) to authenticated;

-- The subquery runs under the caller's Gift RLS (not security definer).
-- objects.name is qualified: a bare name would bind to Gift.name.
create policy "Gift images: read when gift visible" on storage.objects
  for select to authenticated
  using (
    bucket_id = 'gift-images'
    and exists (select 1 from public."Gift" g where g.id = private.gift_image_gift(objects.name))
  );

create policy "Gift images: upload to own gift" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'gift-images'
    and private.gift_image_owner(name) = (select auth.uid())
    and private.is_gift_owner(private.gift_image_gift(name))
  );

create policy "Gift images: update own gift" on storage.objects
  for update to authenticated
  using (bucket_id = 'gift-images' and private.gift_image_owner(name) = (select auth.uid()))
  with check (
    bucket_id = 'gift-images'
    and private.gift_image_owner(name) = (select auth.uid())
    and private.is_gift_owner(private.gift_image_gift(name))
  );

create policy "Gift images: delete own" on storage.objects
  for delete to authenticated
  using (bucket_id = 'gift-images' and private.gift_image_owner(name) = (select auth.uid()));

-- -----------------------------------------------------------------------------
-- Orphan queue
-- -----------------------------------------------------------------------------

-- Queues the old file when a gift's image is replaced, cleared or deleted.
create function private.queue_gift_image_orphan()
returns trigger
language plpgsql security definer
set search_path = ''
as $$
begin
  if old.image_path is not null
     and (tg_op = 'DELETE' or new.image_path is distinct from old.image_path)
     and private.gift_image_owner(old.image_path) is not null then
    insert into public."GiftImageOrphan" (path, owner_id)
    values (old.image_path, private.gift_image_owner(old.image_path))
    on conflict (path) do nothing;
  end if;
  return null;
end;
$$;

revoke execute on function private.queue_gift_image_orphan() from public;

create trigger queue_gift_image_orphan
  after update of image_path or delete on public."Gift"
  for each row execute function private.queue_gift_image_orphan();

-- Removes and returns the caller's queued files, for the app to delete.
create function private.take_gift_image_orphans()
returns setof text
language sql volatile security definer
set search_path = ''
as $$
  delete from public."GiftImageOrphan"
  where owner_id = (select auth.uid())
  returning path;
$$;

revoke execute on function private.take_gift_image_orphans() from public;
grant execute on function private.take_gift_image_orphans() to authenticated;
