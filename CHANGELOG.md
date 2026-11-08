# Changelog

## Unreleased
- Local Supabase project with initial schema (groups, events, wishlists, gifts,
  claims with anonymous co-purchasers), RLS policies, seed data and pgTAP tests.
- Quarkus web app (`web/`): Qute + HTMX landing page, health, OpenAPI, Supabase
  JWT verification, `RlsContext` for RLS-scoped JDBC, and `GET /api/me`.
