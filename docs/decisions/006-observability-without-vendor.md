# ADR 006: Error tracking and metrics without a vendor

## Status
Accepted (2026-11-17)

## Context
Beyond `/q/health`, there was nothing: uncaught exceptions fell through to the
Quarkus default page, users had nothing to quote when reporting a failure, logs
were plain text, and there were no metrics. App hosting isn't chosen yet.

## Options
- **Sentry (SaaS).** Grouping, alerts, release tracking. Adds a processor
  receiving account IDs and stack traces (Privacy §4/§8 updates, legal bump).
- **GlitchTip (self-hosted, Sentry-compatible).** No new processor, but one more
  service to run and patch before we even have hosting.
- **Structured logs + Prometheus.** Reference IDs in JSON logs, counters for
  errors and key flows, scraped by whatever the host provides. No new processor;
  no grouping UI.

## Decision
Structured logs + Prometheus (Micrometer). `UnhandledErrorMapper` gives every
unexpected exception a reference ID shown to the user and logged with the stack
trace. Health and metrics are on the management port (9000), not the public one.
Server-side only: no browser SDK.

## Consequences
- Finding an error means searching logs for its `errorId`; alerting relies on
  `giftloop_errors_unhandled_total` / `giftloop_log_events_total`.
- No legal change: metrics are aggregate (fixed enum tags, never user/group/URL
  values) and Privacy §1 already covers error logs linked to account IDs.
- Adding Sentry later is additive (Quarkiverse log handler), but requires the
  Privacy Policy updates above.
- The host must scrape `:9000/q/metrics` and must not expose port 9000.
