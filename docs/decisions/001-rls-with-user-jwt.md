# ADR 001: Enforce Authorization with RLS and the User's JWT

## Status
Accepted (2026-11-08)

## Context
Claim secrecy (owners can't see claims; anonymous co-purchasers) is the core
product promise. We need one place where it's guaranteed, regardless of which
client (Quarkus web app now, mobile later) touches the data.

## Options
- **RLS + user JWT.** Quarkus validates the Supabase JWT and runs queries as
  `authenticated` with `request.jwt.claims` set (or via PostgREST).
  - Pro: rules enforced in the DB for every client; testable with pgTAP.
  - Con: policies add query cost; SQL must respect column grants.
- **Service role, app enforces.** Quarkus connects as a privileged role.
  - Pro: simpler queries, rules in Java.
  - Con: every code path must re-implement secrecy; a bug leaks claims.

## Decision
RLS + user JWT. Every table has RLS; `anon` has no grants; Quarkus must not use
the service role for user-facing requests.

## Consequences
- Quarkus needs per-request `set local role authenticated` and
  `set_config('request.jwt.claims', ...)` inside a transaction (or calls PostgREST).
- Service-role use is limited to admin/maintenance jobs and must be reviewed.
- Policy changes require pgTAP coverage in `database/supabase/tests/`.
