# API

JSON under `/api`, authenticated with `Authorization: Bearer <Supabase access
token>` (or the `gl_at` cookie). Reads only for now: JSON writes wait on a CSRF
strategy ([ADR 003](decisions/003-cookie-sessions.md)). Full schema:
`/q/openapi`.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/me` | Caller's profile |
| GET | `/api/groups` | Caller's groups: `id`, `name`, `myRole`, `memberCount` |
| GET | `/api/groups/{id}` | Group with `members[]` (`userId`, `username`, `displayName`, `avatarUrl`, `role`) and `joinCode` (null unless owner/admin). 404 if not a member. |

Roles are `owner`, `admin`, `member`.
