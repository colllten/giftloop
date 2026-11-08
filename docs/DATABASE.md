# Database

Supabase (Postgres 17). Project lives in `database/supabase/`.
Migrations: `database/supabase/migrations/`. Tests: `database/supabase/tests/` (pgTAP).

## Conventions
- Tables are TitleCase and singular, so they **must be double-quoted** in SQL:
  `select * from "Gift"`. Columns are snake_case.
- Access helpers and trigger functions live in the `private` schema, which is
  not exposed through the Data API.
- `anon` has no access. `authenticated` gets explicit grants only.

## Entity Relationship
```mermaid
erDiagram
  auth_users ||--|| Profile : "signup trigger"
  Profile ||--o{ GroupUser : ""
  Group ||--o{ GroupUser : "members (admin|member)"
  Group ||--o{ Event : ""
  Event ||--o{ Wishlist : "one per member"
  Profile ||--o{ Wishlist : owns
  Wishlist ||--o{ Gift : ""
  Gift ||--o{ Claim : ""
  Claim ||--o{ ClaimParticipant : "co-purchasers"
  Profile ||--o{ ClaimParticipant : ""
```

| Table | Notes |
|---|---|
| `Profile` | 1:1 with `auth.users`. `username` citext, unique, `^[A-Za-z0-9_]{3,32}$`. |
| `Group` | `is_claims_visible_to_owner` lets owners see claims before the event ends. |
| `GroupUser` | Membership + `role` (`admin`, `member`). Group creator is auto-added as admin. |
| `Event` | `end_date` drives claim reveal. Null = never auto-reveal. |
| `Wishlist` | Unique per (`event_id`, `owner_id`). Owner must be a group member. |
| `Gift` | `quantity` ≥ 1. `price`, `links`, `notes` optional. |
| `Claim` | `quantity` units of a gift. Total claimed **may exceed** `Gift.quantity`. `created_by` is not readable via the API. |
| `ClaimParticipant` | Creator auto-added. Any participant can be `is_anonymous`. |
| `GiftClaimSummary` (view) | Per-gift `claim_count` / `claimed_quantity`, RLS applied (security_invoker). |

## Claim Visibility
- Group members (non-owners) see all claims on a gift.
- The wishlist owner sees claims only when `Event.end_date < current_date` (UTC)
  or `Group.is_claims_visible_to_owner` is true.
- Anonymous participants are visible only to people on the same claim, even
  after reveal.
- Owners can't claim or co-purchase their own gifts. Participants must be group members.

Because `Claim.created_by` is column-restricted, `select *` on `Claim` fails.
Select columns explicitly (`id, gift_id, quantity, created_at`) and use
`ClaimParticipant` for "who" / "my claims".

## Known Gaps
- No invite/join flow yet; admins add members directly to `GroupUser`.
- Nothing prevents removing or demoting a group's last admin.
- An admin who is also a wishlist owner can flip the reveal flag.
