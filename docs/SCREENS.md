# Screen inventory — LOCKED

Status: **Design locked 2026-09-28.** Visual system: Northline "Spruce & Honey" (`packages/tokens/tokens.json`, mirrored in `design/theme/northline.css`). The attached Broadsheet system is NOT used. Do not restyle; do not add screens not listed here without a DECISIONS.md entry.

Open each reference in a browser. "State key" = the view/screen value that shows it in the reference.

## Shared shells
| Shell | Used by | Pattern |
|---|---|---|
| Consumer header | web consumer | brand · location pill (map-pin, auto-detect) · search (off-home) · Services/Shop/Food · FR/EN · cart · account menu (Orders & bookings, wallet, security) |
| Studio shell | provider, seller, kitchen, onboarding | top bar (brand+"Studio", business, tier, account menu: profile, switch business, team, help, language, sign out) · sticky left sidebar with Phosphor duotone icons, section heads, badges |
| Console shell | platform ops | top bar (brand+"Console", global search pill, account menu: profile, role switch, audit, on-call) · sticky sidebar filtered by role |
| Mobile app | iOS/Android | 402×874 frame, bottom tabs, one live phone per journey |

## Consumer web — `design/06 Consumer Web.dc.html`
home · location · search · category · svcCategory · shop · product · cart · confirmed · food · restaurant · foodCheckout · foodTrack · services · providers · provider · book · quote · orders · account · auth (sign in / register / OTP / MFA)

## Consumer app — `design/01 Consumer App.dc.html` (screens in `Consumer Screen.dc.html`)
- A Account: welcome, signup, otp, mfa, location
- B Shop: home, search, product, cart, checkout, pay, confirmed, track, delivered, refund
- C Services: services, providers, provider, book_service, book_slot, book_review, booked, notifications, eta, signoff, review
- D Account: orders, quote, account, security, wallet

## Studios — `design/02 Provider Studio.dc.html` (`portal` prop: provider | seller | kitchen)
- Signed out: sign in, register (phone → OTP → MFA → done)
- Provider/Seller: dashboard, appointments (provider), orders (seller), messages, products, product_new, bulk, availability (provider), storefront, earnings, reports, payouts, refunds, compliance, reviews, settings, help
- Kitchen: kds, menu, combos, kitchenHours, messages, earnings, reports, payouts, refunds, compliance, reviews, storefront, settings, help
- Wrappers: `02b Seller Studio`, `02c Kitchen Studio`

## Onboarding — `07a–07d` (wrap Studio with `start-at="onboarding"`)
Steps: account → business (legal fields per `docs/spec/legal-details.schema.json`) → categories (DB-limited) → verify → store builder (`storefront-sections.json`) → listings → review. 07d = brand-new user path.

## Platform console — `design/03 Platform Console.dc.html` (`role` prop)
overview · orders · disputes · delivery · sellers · seller_detail · verify · vetting · trust · taxonomy · support · regions · finance · reports · api · team · profile (security, sessions, audit, prefs) · oncall · denied (role gate)

## Legal — ship verbatim
`09 Terms of Service`, `10 Privacy Policy`

## Shared components
### Data Table — `design/Data Table.dc.html` (implement with TanStack Table)
Every record list uses it (one `spec` prop: columns, rows, can, roleName, actions). Reference/matrix tables (variant editor, notification matrix, cohort heatmap, quote line items) stay plain.
- Checkbox first column; header checkbox selects all filtered rows (indeterminate when partial); "Select all N" across pages; selection persists across pages.
- Sorting: click = asc → desc → off; Shift-click adds secondary sorts (rank shown). Numeric-aware.
- Global search, faceted filters with counts (enum/tag columns), min/max range filters (number/money), removable active-filter chips, Clear all.
- Column visibility (primary column locked), pagination 5/10/25/50.
- Generate report: rows = selected | filtered | all; format CSV | Excel | PDF/print; pick columns.
- CRUD gated by role: `can = { create, update, delete, export }`. Create/edit dialog built from columns; delete requires confirm and is audit-logged; view-only roles see a "View only · role" tag and no mutating controls. Custom row/bulk actions declare `perm` and a `when` status guard.
- Responsive: lowest-priority columns auto-hide to fit, then the table becomes a card list below ~600px. Never horizontal scroll.
### App sidebar (Studio, Console)
- Light panel (`--color-surface`) with a right border and soft shadow, full height, sticky under the top bar — distinct from the off-white content area.
- Collapse-to-icon-rail toggle sits at the top of the sidebar (72px rail, tooltips).
- Dashboard / Overview pinned first; then accordion groups — Studio: Operations · Catalogue · Finance · Account · Help (Kitchen for food); Console: Operations · Marketplace · Platform. One group open at a time; the group holding the current page opens by default; a dot marks it when collapsed.
- < 900px: sidebar hidden; menu button in the top bar opens it as an off-canvas drawer; choosing an item closes it.
- Console filters groups/items by role.

## Every screen must implement
- No horizontal scroll at any width ≥ 320px: grids use `minmax(min(100%, N), 1fr)`, top bar wraps.
- Loading (skeleton matching layout), empty (one line + primary action), error (rosehip `accent-2` inline, retry).
- en/fr copy (`design/i18n-fr.js` is the reference glossary).
- Focus ring `2px var(--color-accent)`, hit targets ≥ 44px, hover from accent-100.
