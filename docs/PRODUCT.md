# What Problem Does This Solve?
GiftLoop mitigates families and groups who purchase items for one another
from purchasing the same item and ensuring those who request items have a
central place for creating their wishlist. This application is similar to
a registry, except internal to specific groups.

# Audience
GiftLoop's users range from teenagers to the elderly who may not be tech-savvy.

# Primary Workflows

## Group Setup
Signed-in users land on **Your groups** (`/`), listing their groups with
**Create a group** and **Join with a code**.

1. **Create**: name the group. The creator becomes its **owner** and the group
   gets a join code.
2. **Invite**: the owner and admins see the code on the group page
   (`ABCD-EFGH`) and share it however they like. **Make a new code** replaces
   it; the old one stops working. Codes don't expire.
3. **Join**: others enter the code at `/join` and become **members**. People
   without an account sign up and finish their profile first, then return.
4. **Manage** on the group page:

| Action | Owner | Admin | Member |
|---|---|---|---|
| See group and members | ✓ | ✓ | ✓ |
| See / replace join code | ✓ | ✓ | |
| Rename group | ✓ | ✓ | |
| Remove someone | anyone else | members only | |
| Make / remove admin | ✓ | | |
| Make someone owner | ✓ (becomes admin) | | |
| Delete group | ✓ | | |
| Leave | after handing off ownership | ✓ | ✓ |
| Delete account | picks or auto-assigns new owners | ✓ | ✓ |

## Group Users
Users within groups can create their wishlists. Others within the group can
view each users' list for a particular event. Other users can claim purchasing
an item. The wishlist owner can't see claims on their list until the day
after the event ends, or earlier if a group owner/admin turns that on.

## Claims
On someone else's wishlist, **Claim this gift** with a quantity. **My claims**
(`/claims`) lists everything you've claimed, by event.

| Action | Claim creator | Co-buyer | Other members | Wishlist owner |
|---|---|---|---|---|
| See claims (who, how many, how many bought) | ✓ | ✓ | ✓ | after reveal |
| See anonymous names | ✓ | ✓ | | |
| Mark how many are bought | ✓ | ✓ | | |
| Change quantity, add/remove co-buyers, release | ✓ | | | |
| Hide/show own name, leave | own name | ✓ | | |

- Claiming past what the owner wants is allowed after a confirm step.
- Bought can't exceed the claim, and a claim can't be lowered below what's bought.
- Filter a wishlist by **All**, **Still needed** (unclaimed or partly claimed),
  **Fully claimed** or **Claimed by me** (`?show=needed|claimed|mine`). Owners
  get the filters (minus "mine") only after the reveal.
- Reveal: the day after `end_date`, or anytime the group setting
  "Show claims to owners now" is on. No end date = only the setting.
- If the owner removes a claimed gift (or the whole wishlist), claimers still
  see it as **Removed by {owner}** until they release it. The owner sees it
  disappear either way.

## Deleting an Account
**Profile → Delete my account** (`/account/delete`). Type your username to confirm.
- Groups you own: pick a new owner, or leave it on **Automatic** (oldest admin,
  else oldest member). Groups where you're alone are deleted.
- Your wishlists are removed; gifts already claimed stay on the claimers' lists
  as **Removed by Former member**.
- Claims you share pass to a co-buyer; claims only you made are released.

# In-Scope Features
- Group setup (above)
- Events, wishlists and claims (above)

# Out-of-Scope Features
- Mobile application development
