# ADR 003: Cookie Sessions with Server-Side Refresh

## Status
Accepted (2026-11-08)

## Context
Pages are server-rendered (Qute + HTMX), so the browser can't attach a Bearer
token itself. Supabase access tokens last 1 hour; users (many not tech-savvy)
shouldn't be signed out mid-task.

## Options
- **HttpOnly cookies + server-side refresh.**
  - Pro: tokens never reach JavaScript; works with plain forms and HTMX.
  - Con: needs CSRF protection and refresh plumbing.
- **Supabase JS client in the browser.**
  - Pro: Supabase handles refresh.
  - Con: tokens in JS-readable storage; adds a JS bundle to an HTMX app.
- **Server-side session store.**
  - Pro: revocable, small cookie.
  - Con: extra state/store to run.

## Decision
HttpOnly cookies: `gl_at` (access token, max-age = token lifetime) and `gl_rt`
(refresh token, 30 days), SameSite=Lax, Secure outside dev/test.
`SessionRefreshHandler` (Vert.x route, before auth) exchanges `gl_rt` when
`gl_at` is missing or expires within 5 minutes, sets new cookies, and passes the
new token to the current request. Authentication is lazy
(`quarkus.http.auth.proactive=false`) so public pages ignore stale cookies.
`quarkus-rest-csrf` protects form posts.

## Consequences
- Unauthenticated page requests redirect to `/signin?next=…` (HTMX gets
  `HX-Redirect`); `/api/**` gets 401.
- Refresh tokens rotate; Supabase's 10 s reuse interval covers parallel requests.
- JSON write endpoints for API clients need a CSRF strategy before they're
  added (the CSRF filter rejects non-form bodies).
