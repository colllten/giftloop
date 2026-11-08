# Overview
- `web/` — Quarkus 3.40 (Java 25 target), Qute server-rendered HTML + HTMX, JSON API under `/api`.
- `database/` — Supabase (Postgres, Auth). Schema and RLS owned by Supabase migrations.

# Frontend Responsibilities
Qute templates (`web/src/main/resources/templates/`) render full pages; HTMX
requests fragment endpoints (`/fragments/...`) and swaps them in. HTMX is served
locally from the `org.mvnpm:htmx.org` jar at `/_static/htmx.org/dist/htmx.min.js`.

# Backend Responsibilities
Quarkus REST resources, grouped by feature package (`com.giftloop.<feature>`).
Business rules that protect data live in Postgres (RLS + triggers); Java handles
request handling, validation for UX, and rendering.

# API Structure
APIs follow REST conventions under `/api`. OpenAPI at `/q/openapi`, Swagger UI
at `/q/swagger-ui` (dev/test only).

# Database Access Patterns
Plain JDBC via Agroal ([ADR 002](decisions/002-jdbc-with-transaction-scoped-rls.md)).
All user-facing queries go through `RlsContext.inUserTransaction(jwt, work)`,
which sets the caller's JWT claims and the `authenticated` role for the
transaction so RLS applies.

```mermaid
sequenceDiagram
  participant B as Browser / client
  participant Q as Quarkus
  participant A as Supabase Auth
  participant P as Postgres
  B->>A: sign in (email/password)
  A-->>B: access token (ES256 JWT)
  B->>Q: GET /api/me (Authorization: Bearer)
  Q->>A: fetch JWKS (cached)
  Q->>Q: verify signature, iss, aud
  Q->>P: BEGIN; set_config('request.jwt.claims', ...); SET LOCAL ROLE authenticated
  Q->>P: select ... (RLS uses auth.uid())
  P-->>Q: only rows the user may see
  Q->>P: COMMIT (role/claims reset)
  Q-->>B: 200 JSON
```

# Error Handling
SQL failures in `RlsContext` are logged and rethrown as `DataAccessException`
(500). Missing/invalid tokens return 401 via SmallRye JWT.

# Authorization Boundaries
- Authentication: Supabase Auth issues JWTs; Quarkus verifies via JWKS.
- Authorization: Postgres RLS ([ADR 001](decisions/001-rls-with-user-jwt.md)).
  Java code must not bypass `RlsContext` for user data.
- Browser sign-in/cookie handling is not built yet; the API currently accepts
  Bearer tokens only.

# Third-party Integrations
The app will need to utilize other authentication methods, or integrate with
Supabase's integrations with Apple, Google, etc.

Resend or other email providers will need to be utilized.

To be determined.
