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
  Group ||--o{ GroupUser : "members (owner|admin|member)"
  Group ||--|| GroupJoinCode : ""
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
| `Profile` | 1:1 with `auth.users`. `username` citext, unique, `^[A-Za-z0-9_]{3,32}$`. `onboarded_at` set when onboarding finishes (requires `display_name`). Birthday is `birth_month`/`birth_day` only (no year). `avatar_path` = `<user id>/<file>` in the `avatars` bucket. |
| `Group` | `is_claims_visible_to_owner` lets owners see claims before the event ends. |
| `GroupUser` | Membership + `role` (`owner`, `admin`, `member`). Creator is auto-added as owner; at most one owner per group (partial unique index). No direct inserts: rows come from the creator trigger and `join_group_by_code`. |
| `GroupJoinCode` | One code per group, `^[A-HJ-NP-Z2-9]{8}$` (no I/O/0/1). Readable by owner/admins only. Created by trigger with the group. |
| `JoinCodeFailure` | One row per unknown join code entered (`user_id`, `failed_at`); guesses aren't stored. RLS on, no policies, no grants: only `join_group_by_code` uses it. Rows older than the window are pruned on the user's next attempt. |
| `Event` | `end_date` drives claim reveal. Null = never auto-reveal. |
| `Wishlist` | Unique per (`event_id`, `owner_id`). Owner must be a group member. |
| `Gift` | `quantity` ≥ 1. `price`, `links`, `notes` optional. |
| `Claim` | `quantity` units of a gift. Total claimed **may exceed** `Gift.quantity`. `created_by` is not readable via the API. |
| `ClaimParticipant` | Creator auto-added. Any participant can be `is_anonymous`. |
| `GiftClaimSummary` (view) | Per-gift `claim_count` / `claimed_quantity`, RLS applied (security_invoker). |

## Profiles & Avatars
- New auth users get a placeholder username from their email; they choose a
  real one during onboarding. `private.is_username_available(text)` checks
  uniqueness (case-insensitive) without exposing other profiles.
- `avatars` Storage bucket is private (2 MB, JPEG). Owners write under their
  own folder; owners and co-members can read (`storage.objects` policies).
- With `search_path = ''`, compare citext with `operator(extensions.=)`; a bare
  `=` silently falls back to case-sensitive text equality.

## Group Membership
Functions in `private` (security definer, callable by `authenticated`):
- `join_group_by_code(code)` → group id. Ignores case, spaces and dashes.
  Unknown code records a `JoinCodeFailure` and returns null. Already a member:
  no change. 5 failures within 15 minutes (sliding) → every attempt, even a
  correct code, raises `PT429` with DETAIL = seconds until retry.
- `regenerate_join_code(group_id)` → new code. Owner/admin, else `42501`.
- `transfer_group_ownership(group_id, user_id)`. Owner only (`42501`); target
  must be another member (`23514`). Caller becomes admin.

Policies: owner/admin rename the group; only the owner deletes it or changes
roles (`admin` ↔ `member`). Delete on `GroupUser` allows leaving (not the
owner), owner removing anyone else, admins removing plain members.
`private.is_group_admin` counts the owner as an admin.

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
- Deleting the owner's account leaves the group without an owner.
- An admin who is also a wishlist owner can flip the reveal flag.
