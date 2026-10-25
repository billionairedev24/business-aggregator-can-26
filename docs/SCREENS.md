# Screen inventory

Status: **E-15 revamp built 2026-10-24** (S-165–S-182; DECISIONS § "2026-10-23 — S-16x/S-17x/S-18x" and "2026-10-24 —
S-180"). Every screen is on **A · Plainsight**: `docs/design/revamp-epic.md` (the locked direction) and its mockups
`design/revamp/a.html` say how the screens look; `docs/design/redesign-spec.md` (the 2026-10-18 AfroTransact /
Evergreen direction) is superseded. The brand is `web/packages/tokens/brand.json` + `palettes/plainsight.json`; the
system is `CLAUDE.md` § Design system. `make ui-shots` photographs every route listed in
`web/packages/a11y/shots/routes.ts` in English and French; the owner's walkthrough is `docs/design/revamp-walkthrough.md`. This inventory still lists every
screen and state, and the `design/*.dc.html` references still define copy, states and behaviour; do not add screens
not listed here (or in the spec's mapping) without a DECISIONS.md entry.

Open each reference in a browser. "State key" = the view/screen value that shows it in the reference.

## Shared shells
| Shell | Used by | Pattern |
|---|---|---|
| Consumer header | web consumer | S-170, `SiteHeader`: one white bar — logo · "Deliver to (first name)" over the place (the location dialog) · search with its All / Services / Shop / Food scope · "Hello, … / Account & orders" (the account menu: orders, bookings, FR/EN, sign out) · cart with its count in the commerce colour; the grey department strip (All · Services Shop Food · departments \| service categories · Today's deals, Sell on {brand}); the delivery cut-off strip on the home and Shop pages. Phones: menu · logo · account · cart, then the search and the place. Footer: one compact grey band. Auth pages: the logo alone |
| Studio shell | provider, seller, kitchen | S-174, `AppShell`: white top bar (logo + "Studio", the business switcher, the global search — "/" —, the assistant, the account menu) · 228 px sidebar of caps-headed groups (Dashboard · Operations · Catalogue · Money · Store · Account), current item on the grey tint with the 3 px ink bar; icon rail < 1024 px, disclosure menu < 768 px. Onboarding has its own top bar with the step indicator (S-176) |
| Console shell | platform ops | S-177, the same `AppShell`: logo + "Console", the market / province scope, the global search, the staff member and role · sidebar filtered by role; Pilot, UAT, Go-live, API and On-call only with `VITE_NL_TESTING_TOOLS` |
| Mobile apps | iOS/Android | S-169/S-178/S-179, `@northline/mobile-kit`: `AppHeader` and the `BottomTabBar` (consumer Home · Browse · Activity · Cart · Account; the courier's stack); gallery at `/dev/gallery` |

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
Steps (redesign § 3.10, 2026-10-18): account → business (legal fields per `docs/spec/legal-details.schema.json`) → categories (DB-limited) → verify → review = the approval gate (AT BO:235). The store page (`storefront-sections.json`) and the first listings come after approval, in the Studio. 07d = brand-new user path.

## Platform console — `design/03 Platform Console.dc.html` (`role` prop)
overview · orders · disputes · delivery · sellers · seller_detail · verify · vetting · trust · taxonomy · support · regions · finance · reports · api · team · profile (security, sessions, audit, prefs) · oncall · denied (role gate)
Redesign phase 3 (2026-10-18): verify + vetting are one sidebar entry, **Applications** (Businesses · Listings · Licences · Age checks tabs; spec § 3.12); overview, applications, sellers, orders and disputes use the back-office page head, KPI strip and the 680px right sheet (spec § 2.3).

## Courier app — addition (S-87; no design in `design/`, DECISIONS 2026-10-01 — S-87)
`mobile/apps/courier`, iOS/Android (Expo). On the mobile kit since S-179 (ink on white, 16 pt body, large figures, 46 pt actions: glanceable in sunlight), 48 dp targets; a
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
### App sidebar (Studio, Console) — S-174 `AppShell`
- White surface with a right border, full height, sticky under the white top bar; 228px.
- ≥ 1024px: the full sidebar, with a collapse-to-rail toggle at its foot (72px rail, label as tooltip and accessible name; remembered). < 1024px: the rail, expanding in place. < 768px: a disclosure menu under the bar (`aria-expanded`; Escape or choosing an item closes it and focus returns to the button).
- Dashboard / Overview pinned first; then every group with all its items visible under a caps heading — Studio: Operations · Catalogue · Money · Store · Account; Console: Operations · Marketplace · Platform. The current item: the grey tint, bold, the 3 px ink bar.
- Console filters groups/items by role.
### Record pages (Console) — S-177
Seller, application, dispute or refund case, order and support case are pages (`/sellers/$id`, `/verification/$id`, `/disputes/$kind/$id`, `/orders/$ref`, `/support/$id`): `RecordHeader`, `RecordTabs`, the right-hand `ActionPanel`. The old sheet addresses (`?seller=`, `?application=`, `?case=kind:id`, `?ticket=`) redirect to them (S-180).

## Every screen must implement
- No horizontal scroll at any width ≥ 320px: grids use `minmax(min(100%, N), 1fr)`, top bar wraps.
- Loading (skeleton matching layout), empty (inline, never an illustration: one bold line, one muted sentence, one primary action), error (`--color-danger` inline, retry).
- en/fr copy (`design/i18n-fr.js` is the reference glossary); the product's name as `{brand}`.
- Focus ring `2px var(--color-focus)`, hit targets ≥ 44px (the documented 24 px exceptions are in docs/a11y/audit.md § S-180), hover `--color-surface-hover`; WCAG 2.2 AA checked on every route by `make ui-a11y`.
