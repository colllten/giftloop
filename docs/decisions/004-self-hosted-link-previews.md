# ADR 004: Self-hosted link previews and stored gift photos

## Status
Accepted (2026-11-15)

## Context
Owners want to paste a product link and have the gift filled in, ideally with a
photo. That means reading arbitrary retailer pages and showing their images to
everyone who can see the gift.

## Options
- **Self-hosted fetch + parse (jsoup).** Free, no data leaves our servers
  except the request to the retailer. Needs SSRF guards; bot-hostile sites
  (notably Amazon) often return nothing useful.
- **Third-party preview API (e.g. Microlink).** Better hit rate on hostile
  sites. Costs money, needs a key, and sends every user's links to a vendor.
- **Hotlink the retailer's image URL.** No storage. Images break when
  retailers change URLs, every viewer's browser contacts the retailer, and
  mixed content/CSP gets harder.
- **Store a re-encoded copy in Supabase Storage.** Stable, private, metadata
  stripped, served through the same RLS as the gift. Costs storage and cleanup.

## Decision
Fetch and parse in Quarkus (`SafeFetcher`, `ProductMetadataParser`), and store a
re-encoded copy of the photo in the private `gift-images` bucket. Auto-fill only
fills empty fields and never saves; failures fall back to manual entry.

## Consequences
- Some sites yield little or nothing; the UI says so and the owner types it in.
- The app makes outbound requests on users' behalf: SSRF controls and a per-user
  rate limit are required (docs/SECURITY.md). Production should also block
  egress to private ranges.
- Prices are only filled for USD (gifts store a bare amount).
- Photos outlive their gift rows until swept; a trigger-fed queue
  (`GiftImageOrphan`) makes cleanup reliable without the owner learning whether
  a gift was deleted or soft-removed.
