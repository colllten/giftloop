# giftloop-web
Quarkus web app. Setup, commands and architecture: see the root `README.md`,
`AGENTS.md` and `docs/ARCHITECTURE.md`.

- Pages: `src/main/resources/templates/` (Qute) + HTMX fragments under `/fragments`.
- API: `/api`, OpenAPI at `/q/openapi`.
- User data access: `com.giftloop.security.RlsContext` only.
