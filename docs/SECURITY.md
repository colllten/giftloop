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
- Sign-up requires ticking the Terms/Privacy checkbox; acceptance is recorded
  server-side (`docs/LEGAL.md`).
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

## Response Headers
`SecurityHeaders` (Vert.x route, all paths except `/q/`):
- CSP: `'self'` only for scripts, styles, fonts and requests; images also
  `data:` (staged gift photo previews); `frame-ancestors 'none'`,
  `form-action 'self'`. So templates can't use inline `<script>`, `style=""`,
  `on*=` handlers or `hx-on`.
- `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: same-origin` (gift links don't leak GiftLoop URLs),
  `Permissions-Policy` (camera, mic, location, payment, USB off),
  `Cross-Origin-Opener-Policy: same-origin`.
- HSTS (1 year, no subdomains) only when `giftloop.security.hsts` is on: prod
  yes, dev/test no.

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
  covers headers and body; pages are truncated at 1.5 MB, images over 5 MB
  are refused, and bodies are only read for a 200 of the expected type.
- 20 auto-fills per user per sliding hour, every attempt counted, stored in the
  DB (`private.record_link_preview`, `PT429`), so it holds across restarts and
  instances.
- DNS rebinding is closed in code: `PublicOnlyDnsResolver` resolves each host
  once, refuses it if any address is non-public, and Apache HttpClient connects
  to exactly those addresses (no second lookup, no proxy settings read).
  Non-public IP literals are refused before connecting.
- The async client negotiates HTTP/2 and sends no `Accept-Encoding`: Cloudflare
  sites answer Java TLS over HTTP/1.1, or a long codec list, with 429.
- `giftloop.link-preview.allow-loopback` exists for tests only (`%test`); it
  also allows non-default ports for local fixture servers.

### Management port
Health and metrics are on port 9000 (ADR 006). Keep it on the private network
only: metrics reveal traffic volumes and error rates.

### Production egress
Defense in depth for the app container. Fly.io has no per-app egress
firewall, so on Fly these rules rely on `SafeFetcher` (which also rejects Fly's
private `fdaa::/16`); apply them at the network layer if the host changes:
- Allow: DNS to the platform resolver; Supabase (HTTPS 443, Postgres 5432/6543);
  general HTTPS/HTTP 443/80 to the internet (link previews).
- Deny: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, 127.0.0.0/8,
  169.254.0.0/16 (incl. metadata 169.254.169.254), fc00::/7, fe80::/10,
  `fd00:ec2::254`. Add explicit allows above the denies only for private
  services the app really uses (e.g. a VPC-internal database or resolver).
- If the platform offers it, also block or require tokens for the instance
  metadata service (e.g. AWS IMDSv2 with hop limit 1).

## Local Development
Local Supabase keys printed by `supabase start` are shared defaults. Never use
them outside local development.
