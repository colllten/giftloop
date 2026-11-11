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
| Make admin / member | ✓ | | |
| Make someone owner | ✓ (becomes admin) | | |
| Delete group | ✓ | | |
| Leave | after handing off ownership | ✓ | ✓ |

## Group Users
Users within groups can create their wishlists. Others within the group can
view each users' list for a particular event. Other users can claim purchasing
an item. The wishlist owner cannot see the status of items on their list,
unless configured by the admin.

# In-Scope Features
- Group setup (above)
- Events, wishlists and claims: to be determined

# Out-of-Scope Features
- Mobile application development
