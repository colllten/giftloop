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
- Content column is `--content-width` (34rem), single column on all screens.

## Components
| Class | Purpose |
|---|---|
| `button` / `.button` | Outlined secondary action |
| `.primary` | Filled main action. Full width directly inside a form |
| `.link-button` | Button that looks like a link |
| `.field`, `.hint`, `.row` | Form layout |
| `.error`, `.ok` | Inline validation and status |
| `.notice` | Neutral info box |
| `.banner` | Highlighted info, e.g. "Jo can't see who claimed what until Dec 26" |
| `.card` | Separate object, such as a wishlist item |
| `.chip.chip-mine` / `.chip.chip-taken` | Claim states |
| `.avatar` | Round profile photo with gold ring |
| `.brand` | Wordmark text |

Use at most one `.primary` button per view.
