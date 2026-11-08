# Security

## Authentication
Supabase Auth. A `Profile` row is created for each new auth user by trigger.

## Sessions ([ADR 003](decisions/003-cookie-sessions.md))
- `gl_at` (access token, expires with it) and `gl_rt` (refresh token, 30 days):
  HttpOnly, SameSite=Lax, Secure outside dev/test.
- Refreshed server-side before expiry; failed refresh clears both cookies.
- Sign-out revokes the Supabase session and clears cookies.
- CSRF: all form posts (incl. HTMX) carry a token checked by `quarkus-rest-csrf`
  (cookie `gl_csrf`, field `csrf_token`).
- `next` redirect targets must be same-site relative paths.

## Authorization
Enforced in Postgres with RLS using the caller's JWT. See
[ADR 001](decisions/001-rls-with-user-jwt.md) and [DATABASE.md](DATABASE.md#claim-visibility).

- All data is scoped to group membership.
- Claims are hidden from wishlist owners until reveal; anonymous participants
  are hidden from everyone not on the claim.
- Avatars are private: served by Quarkus after Storage RLS confirms the viewer
  is the owner or a co-member. Uploads are re-encoded, dropping EXIF/GPS data.
- `anon` has no table access. The service role bypasses RLS and must not serve
  user requests.

## Local Development
Local Supabase keys printed by `supabase start` are shared defaults. Never use
them outside local development.
