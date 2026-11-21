# Changelog

## 0.1.0 (MVP)
- Signed-out landing page at `/`: what GiftLoop does, how it works, sign-up,
  sign-in and a link for people with a join code. Removes the HTMX ping demo.
- Security headers on every response: strict CSP (no inline scripts or
  styles), clickjacking protection, `Referrer-Policy: same-origin`,
  `Permissions-Policy`, and HSTS in prod. See `docs/SECURITY.md`.
- Friendly 404 and 403 pages (and HTMX fragments) instead of empty responses.
- Launch checklist for whichever host is chosen: `docs/DEPLOYMENT.md`.
- Observability without a vendor: JSON logs, error reference IDs shown to users,
  Prometheus metrics and readiness checks (DB, Supabase Auth) on a private
  management port (ADR 006).
- Terms of Service, Privacy Policy and Cookie Notice pages, with recorded
  acceptance at sign-up and re-acceptance when they change.
- Off-list gifts: record a gift that isn't on someone's list. Others see it as
  covered; the owner sees it after the reveal.
- Gift auto-fill: paste a store link to fill in name, price and photo (fetched
  server-side with SSRF protection), limited per user. Also an "Add to
  GiftLoop" share target and bookmarklet (`/add`).
- Gift photos (upload or fetched), re-encoded to JPEG and served only to people
  who can see the gift.
- Account deletion: hands off owned groups, removes wishlists, keeps claimed
  gifts visible to claimers as "Removed by Former member".
- Claims: claim a quantity, co-buyers, hidden names, "bought" counts, **My
  claims** page, wishlist filters, and claims hidden from the wishlist owner
  until the day after the event (or an admin reveals them early).
- Wishlists per event with per-gift visibility ("Only people I pick"), copying
  gifts from past lists, and "Removed by …" for claimed gifts the owner deletes.
- Groups: create, join with a code, roles (owner, admin, member), rename,
  replace the code, remove members, hand off ownership, leave, delete.
- Event types: Standard or Secret Santa / Gift Exchange, picked with a short
  description when creating an event. Exchanges: opt-in participants, optional
  budget, admin-managed exclusions, and an in-database name draw where each
  giver sees only their own recipient. Legal pages updated (version 2026-11-17).
- Group owners and admins can edit (name, last day) and delete events from the
  event page. Event creators who are plain members no longer can (RLS).
- Sign-up no longer reveals whether an email is registered: it emails a 6-digit
  code (sign-in code for existing accounts) and sets the password after the code
  is verified. Requires Supabase "Confirm email" on in hosted projects.
- Rate limit failed group joins: 5 unknown join codes per user per 15 minutes,
  then joining is blocked until the oldest failure expires.
- CI/CD (GitHub Actions): DB lint + pgTAP, web tests, image build (GHCR on
  `main`), CodeQL, Dependabot, and approval-gated prod migrations. See
  `docs/CICD.md`.
- Local Supabase project with initial schema (groups, events, wishlists, gifts,
  claims with anonymous co-purchasers), RLS policies, seed data and pgTAP tests.
- Sign-in/sign-up (email code or password) with cookie sessions, onboarding
  (username, display name, optional birthday and avatar), profile editing and
  private avatars.
- Quarkus web app (`web/`): Qute + HTMX landing page, health, OpenAPI, Supabase
  JWT verification, `RlsContext` for RLS-scoped JDBC, and `GET /api/me`.
