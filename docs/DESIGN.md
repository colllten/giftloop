# Design System: Evergreen

Deep green, berry and gold ribbon. Warm and festive without being childish.
Built for users from teens to grandparents, so readability wins over density.

Source: `web/src/main/resources/META-INF/resources/assets/app.css`, served at
`/assets/app.css` and linked from `templates/base.html`.

## Rules
- Style through tokens (`var(--color-*)`, `--space-*`, `--text-*`). No hex
  values or inline colors in templates.
- Every text/background pair must meet WCAG AA (4.5:1). Input borders and
  focus rings must reach 3:1. Recheck when adding a token.
- Body text is 18px (`--text-base`). Nothing smaller than 16px (`--text-sm`).
- Controls are at least 44px tall (`--tap`).
- State is never shown by color alone. Pair it with text or an icon
  ("You claimed", "Claimed").
- Dark mode follows the device (`prefers-color-scheme`). Every token has a
  dark value; add both when adding one.

## Color tokens
| Token | Light | Dark | Use |
|---|---|---|---|
| `--color-bg` | `#F7F8F5` | `#0F1A15` | Page background |
| `--color-surface` | `#FFFFFF` | `#17241D` | Cards, inputs, details |
| `--color-surface-sunken` | `#EEF2EE` | `#1E2E25` | Notices, button hover |
| `--color-text` | `#17251E` | `#E8EFEA` | Body text |
| `--color-text-muted` | `#55635B` | `#9DB0A5` | Hints, meta |
| `--color-border` | `#D6DDD7` | `#2A3A31` | Decorative dividers |
| `--color-border-strong` | `#7A897F` | `#61766A` | Input outlines |
| `--color-primary` | `#1F5A41` | `#5FBF8F` | Main actions, links |
| `--color-accent` | `#B02A4C` | `#F07A97` | Sparing emphasis. Not for errors |
| `--color-gold` | `#E3B04B` | `#E8BC5E` | Decoration only, never text |
| `--color-highlight` | `#F6E7C4` | `#3A2F14` | Info banners (`.banner`) |
| `--color-claimed` | `#E2EFE7` | `#1E3A2C` | "You claimed" chip |
| `--color-error` | `#B42318` | `#FF8A7A` | Errors |
| `--color-success` | `#1B6E45` | `#6FD3A0` | Confirmations |

`--color-on-*` tokens give the text color for each filled background.

## Type
- Headings: Bricolage Grotesque, weight 800, tight tracking.
- Body: Nunito Sans.
- Both are self-hosted (latin subset, SIL OFL) in `assets/fonts/`, so pages
  make no third-party requests.
- Scale: `--text-sm` 16px, `--text-base` 18px, `--text-lg` 22px,
  `--text-xl` 28px, `--text-2xl` 36px.

## Shape and spacing
- `--radius` 10px on cards, inputs and buttons. `--radius-pill` for chips only.
- Spacing scale `--space-1` to `--space-6` (4px to 32px).

## Layout
Mobile first. One breakpoint at 56rem (896px), written literally in
media queries because they can't read custom properties.

- Every page gets the site header (`{#appheader /}`). Signed-in pages
  override it: `{#header}{#appheader nav=true current='groups' /}{/header}`
  (`current`: `groups`, `profile` or `''`).
- `<main class="page">` width comes from `{#width}` in `base.html`:
  - `page-narrow` (default): forms and sign-in. `--content-width` (34rem).
    A centered card on desktop.
  - `page-wide`: dashboards (home, group). Up to `--page-width` (72rem).
- Below the breakpoint everything is one column in source order. Order
  markup for phones first, then place it for desktop with grid.
- Group page on desktop: events and members on the left, invite and
  settings in a `--sidebar-width` (22rem) side column (`.group-layout`).
- Wishlist page on desktop (owner): gifts on the left, add form and settings
  in the side column (`.wishlist-layout`). Others get one column.

## Components
| Class | Purpose |
|---|---|
| `button` / `.button` | Outlined secondary action |
| `.primary` | Filled main action. Full width directly inside a form |
| `.link-button` | Button that looks like a link |
| `.field`, `.hint`, `.row` | Form layout |
| `.choice` | Radio or checkbox row with its label; the row is the tap target |
| `.error`, `.ok` | Inline validation and status |
| `.notice` | Neutral info box |
| `.banner` | Highlighted info, e.g. "Jo can't see who claimed what until Dec 26" |
| `.card` | Separate object, such as a wishlist item |
| `.chip.chip-mine` / `.chip.chip-taken` | Claim states ("You claimed", "You bought", "Claimed", "1 of 3 claimed", "Bought", "Removed by …") in `.gift-chips` |
| `.gift-filter` / `.gift-filter-count` | Pill links filtering gifts by claim state; current one filled, marked `aria-current` |
| `.gift-removed` / `.gift-removed-note` | Dashed, sunken card for a gift the owner removed after it was claimed |
| `.claim-list` / `.my-claim` / `.claim-manage` / `.claim-start` / `.claim-purchase` | Other people's claims, your claim, the inline (not dropdown) panels to claim or manage it, and the always-visible "bought" control |
| `.avatar` / `.avatar-sm` / `.avatar-initial` | Round profile photo with gold ring; small size and letter fallback for lists |
| `.chip.chip-role` | Group role label (Owner, Admin) |
| `.danger` | Outlined destructive action (remove, leave, delete). Always confirm |
| `.join-code` | Large, spaced join code |
| `.group-list` / `.group-link` | Linked group cards on home |
| `.link-list` / `.link-card` / `.link-card-title` | Linked cards for events and wishlists |
| `.member-list` / `.member` | Member rows with an optional compact "Manage" menu (`.member-actions` details, `.member-menu`) |
| `details[data-menu]` | Dropdown menu: closes on outside click or Escape (`assets/app.js`) |
| `.visually-hidden` | Text for screen readers only, e.g. the member name in "Manage" |
| `.brand` | Wordmark text |
| `.site-header` / `.site-nav` | Site header and signed-in nav. Current link is underlined |
| `.page-head` | Title block above a wide page's content |
| `.group-layout` | Group page grid: `.group-events`, `.group-members`, `.group-invite`, `.group-settings` |
| `.gift-list` / `.gift` | Gift cards: `.gift-summary` (photo + `.gift-body`), `.gift-head`, `.gift-meta`, `.gift-notes`, `.gift-links`, `.gift-actions` |
| `.gift-photo` | Gift photo thumbnail (contained, bordered). Decorative `alt=""` on cards; `.gift-photo-preview` in the form |
| `.autofill` / `.autofill-row` / `.autofill-notice` | "Paste a link" field + Auto-fill button at the top of the gift form; status text while loading |
| `.chip.chip-visibility` | Who can see a gift, as text ("Everyone", "Shared with 2 people"). Owner only |
| `.visibility` / `.viewer-picker` | Gift visibility fieldset; member checkboxes show only when "Only people I pick" is chosen |
| `.wishlist-layout` | Wishlist page grid: `.wishlist-gifts`, `.wishlist-add`, `.wishlist-settings` |

Use at most one `.primary` button per view.
