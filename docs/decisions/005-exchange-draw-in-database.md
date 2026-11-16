# ADR 005: Secret Santa draw runs in the database

## Status
Accepted (2026-11-17)

## Context
Secret Santa assignments must stay secret from everyone but the giver,
including group admins who usually take part. Someone also has to generate
them, with exclusions and no self-pairs.

## Options
- **Draw in Quarkus, save via a validating function.** Easy to unit-test.
  But the caller supplies the pairs, so anyone who can call the function with
  their JWT could pick their own assignments.
- **Draw in a `security definer` function (`private.draw_exchange`).** The
  caller only says "draw"; Postgres picks and stores the pairs. Harder to test
  outside pgTAP.

## Decision
Draw in `private.draw_exchange` (randomized backtracking in plpgsql).
`ExchangeAssignment` has no write grants and a giver-only select policy.

## Consequences
- No client, admin or API caller can choose or read other pairs.
- Draw rules are tested in pgTAP (`14_gift_exchange.test.sql`).
- Backtracking is capped (100k steps); a very constrained set may report "no
  draw fits" even if one exists. Fine for family-sized groups.
