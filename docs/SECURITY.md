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
- `anon` has no table access. The service role bypasses RLS and must not serve
  user requests.

## Local Development
Local Supabase keys printed by `supabase start` are shared defaults. Never use
them outside local development.
