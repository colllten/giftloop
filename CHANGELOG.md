# Changelog

## Unreleased
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
