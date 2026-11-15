# Security

## Authentication
Supabase Auth. A `Profile` row is created for each new auth user by trigger.

- Sign in: email + password, or an emailed 6-digit code (existing accounts only).
- Sign up: email → emailed 6-digit code + new password (min 8). Every email gets
  the same response: new emails a sign-up code, existing accounts a sign-in code
  (Supabase `/otp`, `create_user=true`). The password is set (`PUT /user`) only
  after the code is verified, so an existing account's password is replaced (like
  a reset), as is any password from someone who pre-registered the email.
- Supabase "Confirm email" (`enable_confirmations`) must stay on in every project,
  so the raw `/signup` API can't create active accounts for unverified emails.
- Password reset: emailed recovery code → session issued → new password set via
  Supabase `PUT /user`.
- Sign-up, code and reset requests respond identically whether or not the
  account exists. The Supabase Auth API itself still differs for existing
  emails, so keep the publishable key server-side.

## Sessions ([ADR 003](decisions/003-cookie-sessions.md))
- `gl_at` (access token, expires with it) and `gl_rt` (refresh token, 30 days):
  HttpOnly, SameSite=Lax, Secure outside dev/test.
- Refreshed server-side before expiry; failed refresh clears both cookies.
- Sign-out revokes the Supabase session and clears cookies.
- CSRF: all form posts (incl. HTMX) carry a token checked by `quarkus-rest-csrf`
  (cookie `gl_csrf`, field `csrf_token`).
- `next` redirect targets must be same-site relative paths.
- Account deletion needs a signed-in session plus the typed username
  (`private.delete_my_account`). Cookies are cleared; Supabase sessions cascade
  with the auth user. The avatar file is deleted with the still-valid token.
  Access tokens on other devices stay valid until expiry; pages treat a token
  with no `Profile` row as signed out (cookies cleared, sent to sign-in).

## Authorization
Enforced in Postgres with RLS using the caller's JWT. See
[ADR 001](decisions/001-rls-with-user-jwt.md) and [DATABASE.md](DATABASE.md#claim-visibility).

- All data is scoped to group membership. Membership is created only by the
  group creator trigger or by entering the group's join code; nobody can add
  another user directly.
- Join codes: 5 unknown codes per user per 15 minutes (sliding), then all join
  attempts are refused until the oldest failure expires. Enforced in
  `join_group_by_code`. Sign-up spam is limited by Supabase Auth's per-IP limits.
- Claims are hidden from wishlist owners until reveal; anonymous participants
  are hidden from everyone not on the claim.
- Avatars are private: served by Quarkus after Storage RLS confirms the viewer
  is the owner or a co-member. Uploads are re-encoded, dropping EXIF/GPS data.
- Gift photos follow gift visibility: Storage RLS reads through Gift RLS, and
  Quarkus serves files only after that check. All photos (uploads and fetched)
  are re-encoded to JPEG; sources over 6000px a side are refused before decoding.
- `anon` has no table access. The service role bypasses RLS and must not serve
  user requests.

## Link Auto-fill (SSRF)
Quarkus fetches user-supplied URLs, so `SafeFetcher` restricts them:
- http/https on default ports, no user info; every resolved address must be
  public (no loopback, private, link-local/metadata, CGNAT, multicast, ULA,
  NAT64 or mapped private IPv4).
- Redirects followed by hand (max 3), each hop re-checked. One 6 s deadline
  covers headers and body; bodies are capped (1.5 MB HTML, 5 MB images) and
  only read for a 200 of the expected type.
- 20 previews per user per minute (in memory, per instance).
- Residual risk: DNS rebinding. The JDK client resolves the host again after
  the check, so a hostile DNS server could answer a private address the second
  time. Mitigate at the network layer in production (deny egress to private
  ranges/metadata from the app).
- `giftloop.link-preview.allow-loopback` exists for tests only (`%test`).

## Local Development
Local Supabase keys printed by `supabase start` are shared defaults. Never use
them outside local development.
