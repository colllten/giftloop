# Changelog

## Unreleased
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
