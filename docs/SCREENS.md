# Screen inventory

Status: **Redesign adopted 2026-10-18** (DECISIONS § "2026-10-18 — Redesign foundation"). The "Spruce & Honey" look of
the 2026-09-28 lock is retired: every screen is rebuilt on AfroTransact's layouts and flows with our own brand —
**`docs/design/redesign-spec.md`** says which AfroTransact screen each route looks like (§ 2), what to remove (§ 3) and
the build order (§ 4). The brand is `web/packages/tokens/brand.json` + its palette. This inventory still lists every
screen and state, and the `design/*.dc.html` references still define copy, states and behaviour; do not add screens
not listed here (or in the spec's mapping) without a DECISIONS.md entry.

Open each reference in a browser. "State key" = the view/screen value that shows it in the reference.

## Shared shells
| Shell | Used by | Pattern |
|---|---|---|
| Consumer header | web consumer | AfroTransact two bars (spec § 1.3, Shop:27–76): logo · "Deliver to" (map-pin, auto-detect) · search (every page) · avatar menu (guest: "Welcome, guest", Sign in, Create account, language; signed in: Orders & bookings, account, language, sign out) · cart (count) / department bar: Services · Shop · Food. Footer (dark): help and policies, for businesses, language, company line. Auth pages: the logo alone |
| Studio shell | provider, seller, kitchen, onboarding | AfroTransact back-office frame (BO:30–110): dark top bar (logo + "Studio", business · city, tier, assistant, account menu: profile, switch business, team, help, language, sign out) · white 264px sidebar with Phosphor regular icons (fill when current), group heads, badges |
| Console shell | platform ops | the same frame: dark top bar (logo + "Console", global search, region line, account menu: profile, role switch, audit, language, sign out) · sidebar filtered by role; Pilot, UAT, Go-live, API and On-call only with `VITE_NL_TESTING_TOOLS` |
| Mobile app | iOS/Android | 402×874 frame, bottom tabs, one live phone per journey |

## Consumer web — `design/06 Consumer Web.dc.html`
home · location · search · category · svcCategory · shop · product · cart · confirmed · food · restaurant · foodCheckout · foodTrack · services · providers · provider · book · quote · orders · account · auth (sign in / register / OTP / MFA)

## Consumer app — `design/01 Consumer App.dc.html` (screens in `Consumer Screen.dc.html`)
- A Account: welcome, signup, otp, mfa, location
- B Shop: home, search, product, cart, checkout, pay, confirmed, track, delivered, refund
- C Services: services, providers, provider, book_service, book_slot, book_review, booked, notifications, eta, signoff, review
- D Account: orders, quote, account, security, wallet

`mobile/apps/consumer` (S-97, Expo): every screen above is a route — the registry is `src/screens.ts`, the routes and
the api each uses are in `docs/MOBILE_PLAN.md` § Screens. Tabs Home · Services · Cart · Orders · You (text labels, as
the design); sub-screens with the design's "← Back" header. Additions with no design (DECISIONS S-97/S-98): `/sign-in`
(phone + code for an existing account; the system-browser sign-in for passkeys, Google, Apple) and the stub shown until
a journey's story builds its screens.

## Studios — `design/02 Provider Studio.dc.html` (`portal` prop: provider | seller | kitchen)
- Signed out: sign in, register (phone → OTP → MFA → done)
- Provider/Seller: dashboard, appointments (provider), orders (seller), messages, products, product_new, bulk, availability (provider), storefront, earnings, reports, payouts, refunds, compliance, reviews, settings, help
- Kitchen: kds, menu, combos, kitchenHours, messages, earnings, reports, payouts, refunds, compliance, reviews, storefront, settings, help
- Wrappers: `02b Seller Studio`, `02c Kitchen Studio`

## Onboarding — `07a–07d` (wrap Studio with `start-at="onboarding"`)
Steps: account → business (legal fields per `docs/spec/legal-details.schema.json`) → categories (DB-limited) → verify → store builder (`storefront-sections.json`) → listings → review. 07d = brand-new user path.

## Platform console — `design/03 Platform Console.dc.html` (`role` prop)
overview · orders · disputes · delivery · sellers · seller_detail · verify · vetting · trust · taxonomy · support · regions · finance · reports · api · team · profile (security, sessions, audit, prefs) · oncall · denied (role gate)

## Courier app — addition (S-87; no design in `design/`, DECISIONS 2026-10-01 — S-87)
`mobile/apps/courier`, iOS/Android (Expo). The brand's tokens (colours already follow the palette; fonts and layout are redesign phase 4), 48 dp targets; a
stack (no bottom tabs: three levels). Copy en + fr-CA ours.
sign-in (browser sign-in, language) · shift (status, start/end, next shift, the open run) · run (stops in order, next
marked, unsent marked, location card) · stop (address, unit, note, order ref, maps, arrive / pick up / hand over; no
customer contact) · pickup (sealed-bag confirm) · dropoff (photo · signature · PIN) · account (language, privacy note,
sign out with an unsent-actions warning). States: loading, offline, saved on phone, refused (the api's message), not a
courier, sign-in ended.

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
### App sidebar (Studio, Console) — spec § 1.3 "Backoffice shell"
- White surface with a right border, full height, sticky under the dark top bar; 264px.
- ≥ 1024px: the full sidebar, with a collapse-to-rail toggle at the top (72px rail, tooltips; remembered). 768–1023px: the rail, expanding in place. < 768px: hidden; the menu button in the top bar opens it as a modal sheet; choosing an item closes it.
- Dashboard / Overview pinned first; then collapsible groups (2xs uppercase heads) — Studio: Operations · Catalogue · Finance · Account · Help (Kitchen for food); Console: Operations · Marketplace · Platform. One group open at a time; the group holding the current page opens by default; a dot marks it when collapsed. The current item: brand tint, bold, its icon filled.
- Console filters groups/items by role.

## Every screen must implement
- No horizontal scroll at any width ≥ 320px: grids use `minmax(min(100%, N), 1fr)`, top bar wraps.
- Loading (skeleton matching layout), empty (inline, never an illustration: one bold line, one muted sentence, one primary action), error (`--color-danger` inline, retry).
- en/fr copy (`design/i18n-fr.js` is the reference glossary); the product's name as `{brand}`.
- Focus ring `2px var(--color-focus)` (on the dark chrome: the hover fill + the accent underline), hit targets ≥ 44px, hover `--color-surface-hover`.
