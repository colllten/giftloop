# Security

## Authentication
Supabase Auth. A `Profile` row is created for each new auth user by trigger.

## Authorization
Enforced in Postgres with RLS using the caller's JWT. See
[ADR 001](decisions/001-rls-with-user-jwt.md) and [DATABASE.md](DATABASE.md#claim-visibility).

- All data is scoped to group membership.
- Claims are hidden from wishlist owners until reveal; anonymous participants
  are hidden from everyone not on the claim.
- `anon` has no table access. The service role bypasses RLS and must not serve
  user requests.

## Local Development
Local Supabase keys printed by `supabase start` are shared defaults. Never use
them outside local development.
