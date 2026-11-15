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
| GET | `/api/wishlists/{id}` | Wishlist (`eventId`, `ownerName`, `mine`) with the `gifts[]` the caller can see (`id`, `name`, `price`, `links`, `quantity`, `notes`, `imageUrl` when it has a photo: app-relative, needs the same session). Owner only: `visibleToAll`, `viewerIds`. When claims are visible to the caller: `claimedQuantity`, `purchasedQuantity`, `claimedByMe`. `removed: true` on gifts the owner removed (claimers only). 404 if not a group member or the wishlist was removed. |
| GET | `/api/claims` | Caller's claims, upcoming events first: `id`, `quantity`, `purchased`, `creator`, `anonymous`, `coBuyers` (names), gift (`giftId`, `giftName`, `price`, `links`, `wanted`, `removed`), `wishlistId`, `ownerName`, `eventId`, `eventName`, `endDate`, `groupName`. |

Roles are `owner`, `admin`, `member`.
