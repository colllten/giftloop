# ADR 002: Plain JDBC with Transaction-Scoped RLS Context

## Status
Accepted (2026-11-08)

## Context
ADR 001 requires queries to run as the calling user so RLS applies. Quarkus
needs a way to set that context on pooled connections without leaking it
between requests.

## Options
- **Plain JDBC (Agroal) + `RlsContext` helper.**
  - Pro: explicit, small, easy to audit; one choke point.
  - Con: hand-written SQL and mapping.
- **Hibernate ORM / Panache.**
  - Pro: less boilerplate, entity mapping.
  - Con: must hook RLS setup into every session/transaction; TitleCase quoted
    tables and column grants (`Claim.created_by`) add mapping friction.
- **Call PostgREST from Quarkus.**
  - Pro: RLS context handled by Supabase.
  - Con: extra network hop; weaker typing; harder transactions.

## Decision
Plain JDBC. `RlsContext.inUserTransaction` opens a transaction, runs
`set_config('request.jwt.claims', <payload>, true)` and
`SET LOCAL ROLE authenticated`, executes the work, then commits or rolls back.
Both settings are transaction-local, so pooled connections return clean.
ORM may be revisited once the domain model settles.

## Consequences
- Don't use `@Transactional` around `RlsContext` calls (it manages its own transaction).
- Locally the pool connects as `postgres`. In production use a dedicated login
  role that is granted `authenticated` (and nothing that bypasses RLS).
- SQL must quote TitleCase tables and select columns explicitly.
