# Redesign spec: the backend as it is, AfroTransact v2 layouts, a new brand

**Status: adopted 2026-10-18** (owner direction; `docs/DECISIONS.md` § "2026-10-18 — Redesign foundation"). This spec
supersedes the "Spruce & Honey" design lock of 2026-09-28: `CLAUDE.md` § Design system and `docs/SCREENS.md` point here.
Every backend, validation, escrow, BFF, i18n and region-neutrality rule of `CLAUDE.md` still applies; this changes how
the screens look and are laid out, not what they do.

**The owner's direction (2026-10-18).** The product gets a new name (not chosen yet) and its own colours. The UI is
rebuilt on AfroTransact's layouts and flows — **not** its colours, fonts or logo. The palette is one of three directions,
each one file (`web/packages/tokens/palettes/`): **A "Lakeshore"** (deep blue `#17407A` + sun `#F2B33D`, Manrope —
the placeholder default), **B "Evergreen"** (forest `#1F5E4A` + ember `#C4531C`, Fraunces headings + DM Sans), **C
"Dusk"** (plum `#4A2C7F` + mint `#0F7B67`, Plus Jakarta Sans). Switching is one setting: `palette` in
`web/packages/tokens/brand.json`.

Paths used below:
- **AT** = the AfroTransact reference, repository `billionairedev24/diaspora-commerce` (read-only; not part of this
  repository); **AT/design** = its `design/` folder of `.dc.html` prototypes.
- **NL** = this repository's root.
- "Shop:182" means `AT/design/AfroTransact Shop.dc.html`, the section that starts at line 182. "BO" = `AfroTransact Backoffice.dc.html`, "PH" = `AfroTransact Provider Hub.dc.html`, "MA" = `AfroTransact Mobile App.dc.html`, "DR" = `AfroTransact Driver.dc.html`.
- The AT implementation (Next.js) is a second reference with the same values, already made responsive. Use it to check breakpoints: `AT/web/packages/tokens/src/tokens.css`, `AT/web/packages/ui/src/**`, `AT/web/apps/storefront/src/components/**`, `AT/web/apps/seller-center`, `AT/web/apps/staff-console`, `AT/mobile/apps/{customer,driver}`.

**Colour words in this spec.** AT's own role names are kept where the spec quotes AT ("gold", "green", "black
header"); in this repository they map onto our brand's roles: AT's gold *primary* (Add to cart, Search, rating, the
header underline) is our **accent** (`--color-action-accent`, `--color-rating`, `--color-header-indicator`); AT's
green *secondary* (Buy now, Place order, links, focus) is our **primary** (`--color-action-primary`, `--color-link`,
`--color-focus`); AT's black chrome is our **header colour** (`--color-header-bg`, a brand-dark per palette). See § 5.

---

## 1. The AfroTransact design language, with the brand taken out

AT has three token layers (`AT/design/afrotransact-tokens.css`, refined in `AT/web/packages/tokens/src/tokens.css`):
1. primitive ramps;
2. semantic roles, which components use;
3. a store theme that sellers can override.

We keep the structure and every non-colour value. We replace all primitives and fonts. Components may only use semantic tokens.

### 1.1 Things we replace (AT brand, do not copy)
| AT value | Role | Replace with |
|---|---|---|
| Gold ramp `#FFD400` (500), 50…900 | primary action (Add to cart, Search), rating stars, avatar fill, the active underline in the header | new brand **primary** ramp |
| Green ramp `#067457` (600), `#055E47` (700) | secondary action (Buy now, Place order), links, focus, success | new brand **secondary/confirm** ramp |
| Sand `#F6F0E4` | brand surface | new brand tint |
| Ink ramp (warm greys `#191816`…`#FCFBF8`) | text, borders, header `#000` | keep a neutral ramp. It can be the same warm-grey idea, but regenerate it from the new brand's neutral |
| Red `#B42318`/`#D92D20` | deal, danger, low stock, cart badge | keep a red danger ramp (not brand-specific) |
| Fonts Figtree (prototype) or Inter + Fraunces (implementation) | type | new brand font(s). AT uses **one sans font for all UI**; the display serif appears only in a few marketing spots |
| Logo mark (gold bag) and lowercase wordmark "afrotransact" at weight 800 | brand | new mark plus wordmark |
| "Fulfilled by AfroTransact", "AfroTransact Brand" badges | first-party retail | drop (section 2.3) |

### 1.2 Things we keep exactly (the design language)
**Canvas.** Pure white page (`canvas = surface = #FFF`). The owner insisted on this in AT chat1:219. The dark header is black (`header-bg = ink-1000`) and the department subnav under it is `ink-900`. Footer `ink-900`. There is no off-white app background. Content blocks are told apart by `shadow-sm` or a 1px `border`, not by tinted panels.

**Layout grid**
- Page max width `--page-max: 1500px`. Side gutter `--page-gutter: clamp(12px, 2.4vw, 32px)` (implementation: `max(clamp(…), env(safe-area-inset-*))`).
- Breakpoints (from the implementation CSS): `1100px` (PDP drops to 3 columns, header compacts), `999/1000px` (checkout goes to one column), `899/900px` (cart goes to one column, header wraps), `760px` (PDP goes to one column), `599/600px` (product card padding tightens, tables become cards), `420/479px` (smallest). Backoffice: `<768px` one column with the menu as a disclosure; `768–1023px` a 72px icon rail; `≥1024px` a 264px sidebar.
- Every auto grid uses `repeat(auto-fill|auto-fit, minmax(min(100%, N), 1fr))`. There is never horizontal page scroll (`html, body { overflow-x: hidden }`). Scrollbars are hidden everywhere (owner rule, chat1:857).

**Spacing scale** (4px base): `0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64` (`--space-0…16`; there is no 7, 9, 11, 13–15).
**Control sizes:** `control-sm 32px`, `control 40px`, `control-lg 48px`, `touch 44px`. On coarse pointers, `control` and `control-sm` become 44px and the smallest text becomes 12px.

**Type scale (px):** `2xs 11 · xs 12 · sm 13 · md 14 · base 15 · lg 17 · xl 20 · 2xl 24 · 3xl 30 · 4xl 38 · 5xl 52`. Body is 15px at line-height 1.45.
- Fluid on the web: `2xl clamp(21px, 19px+.5vw, 24px)`, `3xl clamp(24px, 20px+1vw, 30px)`, `4xl clamp(28px, 22px+1.6vw, 38px)`, `5xl clamp(34px, 22px+3.2vw, 52px)`.
- Weights `400/500/600/700/800`. Leading `1.2 / 1.45 / 1.6`. Tracking: `-0.015em` on large headings, `0.06em` uppercase for kickers and section labels.
- Usage: page H1 `3xl` at 600–700; hero H1 `5xl` at 800, line-height 1.05; section H2 `xl` at 700; card titles `base` at 500; meta text `xs` muted; price is a `3xl` whole-dollar figure with `xs` superscript `$` and cents.

**Radii:** `xs 4 · sm 6 · md 8 · lg 12 · pill 999`.
- Buttons are pills.
- Inputs, selects, table controls and day/slot pickers use `sm` 6px.
- Cards and panels use `sm`/`md`. Hero and promo blocks use `lg`.
- Header hover rings use `xs`.

**Shadows:** `sm 0 1px 2px rgb(25 24 22/.08)` · `md 0 4px 14px rgb(25 24 22/.12)` · `lg 0 16px 40px rgb(25 24 22/.22)` · `focus 0 0 0 3px <secondary-200>`. Z-index: `dropdown 20, sticky 30, drawer 40, dialog 50, toast 60`. Motion: `120ms / 200ms`, `cubic-bezier(.2,.7,.2,1)`.

**Icons:** Phosphor (`@phosphor-icons` 2.1.1). Use the regular weight normally and the fill weight for active or selected states. Northline already uses Phosphor, but the duotone weight; switch to regular and fill.

### 1.3 Component inventory (exact values)

**Header and nav (Shop:27–76; impl `storefront/src/components/shell/Header.tsx` and `.module.css`)**
- **Bar 1:** black, padding `8px gutter`, one row with no wrap above 900px. It holds, in order:
  - logo: 32px mark plus wordmark, `2xl`, weight 800;
  - "Deliver to" location button: `xs` muted label over `md` bold city, max 120px, with ellipsis;
  - **search**, which takes the remaining width (min 260px): one 40px control with `sm` radius made of a department `<select>` (68px, muted surface), the input (15px) and a 48px primary-colour search button;
  - account avatar: a 36px circle with initials on primary (signed in), or a grey user icon (guest), and a caret;
  - cart: 30px icon, a red pill badge (20px, 2px border in the header colour) and the bold label "Cart".
- Every header item has a 1px transparent border that becomes white on hover, radius `xs`.
- **Bar 2** (subnav): `ink-900`, padding `4px gutter`, `md` text, a single row that scrolls horizontally. Items are department or category buttons with icons.
- An optional **announcement bar** follows: centred, `sm` semibold, dismissible, from the console.
- **Account menu:** 290px dropdown, `md` radius, `shadow-lg`. The head has a 44px avatar, name and email. Items are icon plus label plus count, padding `8px 16px`, `md` text. Sign out sits below a divider. Guests get the same avatar with "Welcome, guest" and Sign in / Create account items. Orders, bookings and returns live **only** in this menu, never in the top nav.

**Search suggestions (Shop:42–52):** an absolute panel `top: 100%+4px`, `shadow-lg`, max-height 520px, grouped. Group heads are `2xs` uppercase. Term rows show the typed part plain and the predicted rest in **bold**. Product rows are a grid `44px thumb | name+meta | price`.

**Home (Shop:182–272)**
- Full-bleed hero carousel: min-height 380px with 150px bottom padding. Two columns `minmax(min(100%,380px),1fr)` holding kicker, `5xl` title (max 16ch), `lg` sub (46ch) and a pill CTA, beside a 16:9 image. Arrows are 44×88. Dots are 8px pills.
- Then a **tile row that overlaps the hero by −120px**: `repeat(auto-fill,minmax(270px,1fr))`, gap 20. Each tile is a white card with `shadow-sm`, padding 20, an `xl` title and a 2×2 image grid with `xs` captions, plus a "See more" link.
- Sponsored banner: two columns, border, `md` radius.
- **Rails:** white, `shadow-sm`, padding 20, title plus "See more", grid `minmax(170px,1fr)`, gap 20.

**Product card, results variant (Shop:313–332; impl `ui/src/commerce/ProductCard.module.css`)**
- Card: border, radius `sm`, white. Media area padded 16 on the sunken surface with a 1:1 image.
- Body padding `12 16 16`, gap 4. In order: optional sponsored line; title `base` at 500 that turns the link colour on hover; meta `xs` muted; stars with the count in link colour; price; badges; ETA `sm` ("Get it **Tomorrow**"); low-stock line ("Only 3 left", red `xs` semibold).
- Action: a full-width `32px` pill "Add to cart" in primary. Once added it becomes a stepper with a 2px primary border: "− 2 in cart +".
- Below 600px: media padding 8, body padding `8 12 12`, title `md`.
- **Rail variant:** no border, title `md` clamped to 2 lines, price `2xl`.

**Service card (Shop:891)**
- A button-card grid `96px | 1fr | auto`, padding 16, `md` radius, 2px border (primary colour when selected).
- 1:1 thumbnail on `sm` radius. Kind as an `xs` uppercase muted kicker. Name `lg` bold. Stars. "City · distance" `sm` muted. On the right, "from" over the **price**.
- Above the list: chips for service kinds (32px pills, `sm` text).

**Results page (Shop:274–340):** a sticky-ish summary bar (`shadow-sm`) with "N results for **q**" and a Sort select (32px). Below it a grid of a `230px` filter aside and the results. Filters are bold headings with checkbox or radio lists, star rows, counted options and a "Clear all" link. The results grid is `minmax(230px,1fr)`, gap 12. Infinite scroll with a sentinel ("Loading more…" / "You've seen all N"). Empty state: "**No results.** Try clearing a filter…".

**PDP (Shop:342–423; impl `product/Product.module.css`)**
- White full-bleed. Breadcrumb `xs`.
- Grid `64px thumbs | minmax(0,5fr) image (sticky) | minmax(0,4.2fr) info | 300px buy box (sticky)`, gap 24. At 1100px: `64px | 1fr | 300px`. At 760px: one column.
- Info: H1 `2xl` at 600, "Visit the X store" link, stars, price block, a facts grid `max-content 1fr`, "About this item" bullets.
- Buy box: bordered, `md` radius. Holds price, delivery promise, stock, qty, Add to cart (primary pill) and Buy now (secondary pill), plus seller and return facts.

**Cart (Shop:425–500; impl `cart/Cart.module.css`)**
- Grid `minmax(0,1fr) | 300px` sticky aside, gap 20.
- Lines: grid `24px checkbox | 150px image | details | price`, padding `20 0`, top border. At 899px the image is 96, at 479px it is 72.
- Line actions sit in a row separated by pipes: qty stepper | Delete | Save for later.
- "Subtotal (N items): **$X**" right-aligned.
- Aside: subtotal and a "Proceed to checkout" primary pill, then "Customers also bought" (grid `64px | 1fr`).
- Saved-for-later grid `minmax(180px,1fr)`.
- Empty state: bold line, one muted sentence, "Continue shopping" pill.

**Checkout (Shop:502–594; impl `checkout/Checkout.module.css`)**
- "Secure checkout" H1 `3xl` with a lock icon and the item count.
- Grid `minmax(0,1fr) | minmax(360px,400px)` sticky summary. One column at ≤999px.
- **Three numbered sections**, each with a 28px circle number and an `xl` H2. A completed step collapses to a summary with a "Change" link.
  1. **Delivery address:** saved-address radios, "Add a new address" (autocomplete), "Deliver to this address".
  2. **Payment:** express buttons, then "or choose a saved method", radios with a card-brand chip and last 4, "Add a card" (Stripe Payment Element in a dialog), a Stripe trust line.
  3. **Review items and delivery:** one block per shipment. Each block is a grid `items | minmax(220px,.7fr) delivery option`. Delivery options are radios: Standard, Express or Pickup with pickup points. Delivery preferences: hand to me or leave at a safe place, driver instructions (240 characters), contact preference, and a handoff code note.
- Summary aside: lines, total, "Place your order" (secondary pill), terms in `xs`.

**Services, booking aside (Shop:882–940; impl `services/ServicesView.tsx`, `SlotPicker.tsx`)**
- Grid `minmax(0,1fr) | 380px` sticky aside with max-height `100vh-32` that scrolls inside.
- Aside: provider name `2xl`, a 140px cover that bleeds to the edges, a licence line with a seal icon, pill chips (experience, mode, highlights), bio, "See full page".
- Then **1. Choose a service** (radio cards: name, meta, price), the **day strip** (56px-wide day buttons with day of week, number and status, 2px border, `sm` radius, time zone shown on the right), the **slot grid** (`repeat(3,1fr)`, 32px buttons), an optional note, a summary, the cancellation policy box with a shield icon, and the CTA.
- When the provider has no booking engine, the aside instead says "takes requests by quote".
- "Your appointments" list sits under the results, with status, actions and a confirmation before cancelling.

**Provider page (Shop:859–880; impl `business/BusinessPageView.tsx`):** cover, a 44px avatar beside an H1 `4xl`, then two columns `1.4fr | 1fr` (About, details), a work gallery `minmax(200px,1fr)`, featured work `minmax(320px,1fr)` and social links.

**Account (Shop:596–686):** max-width 1100. "Your Account" H1 `3xl`. A **card grid** `minmax(300px,1fr)`, gap 16; each card has a 48px icon circle, a title and a one-line description. Sub-pages use narrower max widths: Addresses 1100 (grid `minmax(280px,1fr)` of address cards plus an "Add address" dashed card), Payments 760, Login & security 860, Returns 960 (rows `72px | details | minmax(220px,auto)`).

**Orders (Shop:688–734):** grid `minmax(0,1fr) | 320px`. The right column holds help, recent purchases and a "buy again" panel (the owner asked that it not stay empty, chat1:775). Order card: head row (placed, total, ship-to, order number), status line, item rows `56px | name | actions`.

**Sign in / up (Shop:795–857):** max-width 1280, **two columns**: an advertorial panel (title `clamp(28px,3.4vw,44px)` at 800) and the form. Form H1 is `2xl`. Fields sit in `1fr 1fr` rows. Social buttons use the official logos and only appear when enabled. Passkey appears on sign-in only. **No site nav on auth pages.**

**Backoffice shell (BO:30–110, `ui/src/shell/BackofficeFrame.module.css`)**
- Black header holding the logo (28px), portal title muted, workspace switcher, a spacer and the avatar.
- Sidebar: 248px in the prototype, 264px in the implementation. It is a white surface with a right border, `sticky; height: 100vh − header`, and scrolls by itself. Padding `16 12`.
- Nav items are 34px high, padding `0 12 0 16`, radius `sm`, `md` text. The active item uses the brand tint background and bold text.
- Groups are collapsible: the head is `2xs` uppercase with `.08em` tracking. Dashboard is pinned first and open by default; the other groups start collapsed.
- Collapse toggle at the top: a 72px rail at 768–1023px; a disclosure on phones.

**Dashboard (BO:263–280):**
- KPI strip `repeat(auto-fill,minmax(190px,1fr))`, gap 12. Each card is bordered, `md` radius, padding 16, with an `xs` muted label, a `3xl` bold value and an `xs` delta in green or red.
- Charts (`AT/design/DashCharts.jsx`; Recharts in the implementation).
- "To do" cards `minmax(min(100%,340px),1fr)`.

**Tables (BO:756–810; `AT/web/packages/data-table`)**
- Toolbar: search input 32px with a left icon at 32px padding, facet buttons, column menu, export (PDF, CSV, Excel).
- `table-layout: fixed`, `sm` text. The first column is a 40px checkbox. Headers are `2xs` bold uppercase with `.02em` tracking and act as sort buttons. Cells have 8px padding.
- Footer has 28px pager buttons. Status tags are `xs` bold on a tinted background, padding `2px 8px`.
- **Never** scroll horizontally. Low-priority columns hide first; below 600px the table becomes cards.
- Rows open a **right sheet**: `fixed; width: min(680px,100%)`, `shadow-lg`, z drawer. The head is padded `16 20` with a bottom border; the body is padded 20 with gap 20. Large editors open in a dialog of `min(1040px)`.

**Forms (`ui/src/form/Field.module.css`)**
- Label `sm` semibold. Control 40px, 1px `border-strong`, radius `sm`, padding `0 12`, 15px text.
- Focus state: focus-colour border plus `shadow-focus`. Invalid state: danger border.
- Message line `xs` with a 14px minimum height (no layout shift).
- Checkbox 18px using the secondary accent colour.
- Phone input: 104px country selector with a flag, then the number.
- Addresses always use autocomplete.
- Onboarding wizard (BO:113): grid `240px step rail | form`, gap 24, and **no sidebar or site nav**. Before onboarding there is a basic account step (BO:77); an approval gate follows (BO:235).

**Empty states:** inline, never illustrations. One bold line, one muted sentence and one primary pill action. Table empty state: padding 32, centred, `sm` muted.

**Mobile (MA; impl `AT/mobile/apps/customer`)**
- Tab bar: grid of 5 columns, padding `6 8 28` (home indicator), 1px top border, white. Each item is 50px high with a 24px icon (fill when active) and a 10px semibold label. Active colour is `ink-1000`, inactive `ink-500`. Badge: an 18px red pill at `top:0; left:50%+4`.
- AT tabs: Home · Browse · Lola · Cart · Account.
- Screen padding is 16. The home header has a 30px logo, a 21px/800 wordmark, a 44px bell circle and a 44px avatar.
- "Deliver to" row (13px). Search pill 48px with a 1px border and a barcode icon.
- An active-order banner: black, `md` radius, truck icon.
- Category circles: 60px inside a 72px column. Hero card: `lg` radius, min-height 180.
- Sub-pages share a "‹ Back" header (MA:177). Primary actions sit in a sticky bottom action bar (MA:529); pickers use a bottom sheet (MA:550).
- Driver app tabs: Home · Route · Scan · Profile.

---

## 2. Screen-by-screen mapping

### 2.1 Consumer web (`NL/web/apps/consumer/src/routes`)
| Northline route | Look like (AT file and section) | Notes |
|---|---|---|
| `index` (home) | Shop:182 HOME; impl `storefront/components/home/Hero.tsx`, `HomeSections.tsx` | Hero carousel, overlapping tiles (Shop · Services · categories), rails of real listings (products **and** services cards). Replaces the greeting and "what do you need" hero. |
| `location` | Shop:990 LOCATION modal; impl `shell/DeliverToDialog.tsx` | A dialog from the header's "Deliver to" button, not a page (keep the route as a fallback). |
| `search` | Shop:274 RESULTS; impl `search/SearchResults.tsx` | Mixed results. The filter aside gains a "Type: Products / Services" facet and replaces "Fulfilment/Sold by" with "Distance / Open now / Price". |
| `shop/index` | Shop:182 HOME layout scoped to products (tiles plus rails) | The owner rule still applies: Shop opens a landing page, not results. |
| `shop/$department` | Shop:274 RESULTS with the department preset and subcategory chips on top | |
| `products/$productId` | Shop:342 PRODUCT; impl `product/ProductView.tsx` | 4-column PDP. Variants in the info column; offers from several shops become "Other sellers" in the buy box. |
| `cart` | Shop:425 CART; impl `cart/CartView.tsx` | **Take the payment out of the cart** (see section 3). |
| *(new)* `checkout` | Shop:502 CHECKOUT; impl `checkout/CheckoutView.tsx`, `PaymentStep.tsx`, `PackageCard.tsx` | Northline has no product checkout route today; payment is inside `features/cart/CartPage.tsx`. Move `StripePayment`, `StepUpDialog`, `AgeCheck` and `PromoPoints` into the 3-step checkout. |
| *(new)* `checkout/complete` | impl `checkout/CheckoutComplete.tsx` | Replaces the old order-confirmed screen. Clear the cart (chat1:775). |
| `orders/$orderId` | Shop:688 ORDERS detail; impl `orders/OrderDetailView.tsx`, `DeliveryProofCard.tsx` | Tracking timeline plus courier status (`features/tracking/CourierStatus.tsx`). |
| `account/orders` | Shop:688 ORDERS; impl `orders/OrdersView.tsx` | Tabs for Orders and Bookings, plus the right-hand "buy again / help" column. |
| `account/index` | Shop:596 ACCOUNT card grid; impl `account/AccountHome.tsx` | Cards: Orders, Bookings, Addresses, Payments, Login & security, Notifications, Help. Sub-pages: Shop:612 Addresses, :627 Payments, :646 Security. |
| `account/problem.$kind.$id` | Shop:658 RETURNS ("Return or replace items" :672); impl `account/StartReturnView.tsx` | Keep Northline's queue-based case flow; change only the look. |
| `services/index` | Shop:882 SERVICES, list column only (chips plus service cards) | Kind chips come from the Northline taxonomy. |
| `services/$category/index` | Shop:882 with that chip selected | |
| `services/$category/providers` | Shop:891 service-card list plus the 380px booking aside | |
| `services/$category/quote` | No equivalent. Design it from the system as a **form page in the wizard pattern** (BO:113: step rail and form) | The "takes requests by quote" branch (Shop:906 `noEngine`) links here. Keep it, because mechanics and carpenters need quotes. |
| `providers/$slug/index` | Shop:859 PROVIDER PAGE; impl `business/BusinessPageView.tsx` | |
| `providers/$slug/book` | Shop:906 booking aside, full page; impl `services/ServiceDetailView.tsx` + `SlotPicker.tsx` | Steps: service → day strip → slots → note → summary and policy → book. Payment goes through the checkout payment step. |
| `quotes/$quoteId` | No equivalent. Use the system's **checkout layout**: line items left (itemised, versioned, scope, exclusions, warranty, deposit, validity, as `NL/CLAUDE.md` requires) and a sticky 400px accept/pay aside | |
| `quotes/requests/$requestId` | No equivalent. Use the Orders card pattern with a list of received quotes (keep `QuoteCompare`, restyled) | |
| `bookings/$bookingId` | Shop:898 "Your appointments" item expanded; impl `services/BookingsView.tsx`; MA:515 for the states | Reschedule, cancel and status. |
| `food/index`, `food/$kitchen`, `food/checkout`, `food/orders/$orderId` | No equivalent. **Not in the demo** (section 3). Later: kitchen page = Provider page layout, menu = rail of product cards, checkout = Checkout, tracking = Order detail | |
| `help` | impl `help/HelpArticleList.tsx`, `HelpArticleView.tsx`; Shop:790 INFO PAGES | |
| `sign-in`, `register` | Shop:795 SIGN IN (2 columns with an advertorial); impl `auth/AuthShell.tsx` | No header nav. |
| `sell` | BO:77 SELLER ACCOUNT (before onboarding), reached from the footer and the account menu ("Sell or offer services") | No marketing "Sell on X" page (chat1:871). Signed-in customers get the role and go straight to onboarding (chat1:2038). |
| `__root` shell | Shop:27 header + subnav, Shop:945 footer (social links, legal, language picker), announcement bar | |

### 2.2 Studio = merchant back office (`NL/web/apps/studio/src/routes`)
AT splits this into Seller Center and Provider Hub. Northline's single Studio switches by `type` (provider, seller or both); keep that and use the matching AT screen for each type.

| Northline route | Look like | Notes |
|---|---|---|
| `register`, `sign-in` | Shop:795 2-column auth (seller copy) | |
| `onboarding/index`, `onboarding/$step` | BO:77 account, then BO:113 WIZARD (`240px rail | form`, no sidebar), then BO:235 ONBOARDING GATE; impl `seller-center/(portal)/onboarding/[step]` | Steps: account → business (legal schema per type) → categories → verification → review/submit. Move `page` and `listings` to after approval (section 3). Save and resume later; sign out from the gate. |
| `identity.done` | The verification step's return state inside the wizard | |
| `invite.$token` | impl `seller-center/invitations/accept` | |
| `pilot.$token` | Remove from the UI (section 3) | |
| `b.$merchantId/index` (dashboard) | BO:263 KPI STRIP + `DashCharts.jsx` + to-do cards; PH:53 HOME for providers | |
| `b.$merchantId/appointments` | PH:62 CALENDAR (day/week), PH:240 DETAIL sheet; impl `seller-center/(portal)/calendar` | |
| `b.$merchantId/availability` | PH:117 AVAILABILITY (weekly hours, breaks, blocked dates, booking rules); BO:511 | |
| `b.$merchantId/listings/index` | BO:756 GENERIC TANSTACK TABLE (module `sListings`, or `pServices` → PH:139 for providers); impl `seller-center/(portal)/listings` | Use Northline's `@northline/ui` DataTable. Restyle it; its behaviour already matches. |
| `b.$merchantId/listings/new`, `listings/$listingId` | `AfroTransact Catalog.dc.html` "3. Add a product"; impl `seller-center/(portal)/listings/new`, `[productId]/edit`; PH:139 for services | A sectioned form with a category-driven attribute section, variants and images. Never ask "sold by". A category request is allowed (chat1:970–1008). |
| `b.$merchantId/listings/bulk` | impl `seller-center/(portal)/listings/bulk` + `/history` | |
| `b.$merchantId/orders` | BO table (`sOrders`) + BO:811 right sheet; impl `seller-center/(portal)/orders` | Sellers set Packed and Shipped; the platform and webhooks set Delivered. |
| `b.$merchantId/messages` | No equivalent. PH:108 REQUESTS is closest (list plus detail column). Design from the system: list `minmax(0,1fr)` plus a 380px detail column | |
| `b.$merchantId/page` | PH:171 PUBLIC PROFILE (page design, gallery, featured work, social links) | Replaces the block PageBuilder for the demo. |
| `b.$merchantId/earnings`, `payouts` | PH:230 EARNINGS + BO:472 PAYOUT WALLET; impl `seller-center/(portal)/earnings`, `/payouts` | |
| `b.$merchantId/refunds` | BO table (disputes/returns module) + sheet | |
| `b.$merchantId/reports` | BO KPI strip + charts + export menu (PDF/CSV/Excel) | |
| `b.$merchantId/reviews` | BO table (`pReviews`) | |
| `b.$merchantId/compliance` | BO:673 COMPLIANCE (Stripe requirements as a form); impl `seller-center/(portal)/account-health` | |
| `b.$merchantId/settings` | BO:521 SETTINGS + team (BO:496 ROLE GUIDE with can/can't per role); impl `seller-center/(portal)/team`, `/account/*` | |
| `b.$merchantId/help` | Storefront help pattern inside the shell | |
| `b.$merchantId/kitchen/{live,menu,combos,hours}` | No equivalent. Design from the system later: live = KPI strip + order card columns; menu/combos = listings table + editor; hours = PH:117 | Hidden for the demo. |

### 2.3 Console = staff back office (`NL/web/apps/console/src/routes/_console`)
| Northline route | Look like | Notes |
|---|---|---|
| `index` (overview) | BO:263 KPI + charts (`dash`) | |
| `verification`, `vetting` | BO module `apps` (Applications, two-person review: reviewer → approver) + BO:811 sheet showing every submitted field | Merge them into one "Applications" queue with two status stages. |
| `sellers.index`, `sellers.$sellerId` | BO module `orgs` (table) + sheet or detail page | |
| `orders` | BO module `orders` + sheet | |
| `disputes` | BO module `disputes` + sheet | |
| `trust`, `catalogue` | BO module `catalog` (Listing moderation) | Taxonomy goes under the same group. |
| `delivery` | BO:662 DISPATCH FEED (`dispatch`) | |
| `finance` | BO modules `payouts` (ledger) + BO:342 FEES + `coupons` (promo codes) | |
| `provinces` | BO:548 MARKETS (on/off by country and province, then zones) | Keep the region/zone model; drop the "switchboard" look. |
| `team` | BO:712 ACCESS CONTROL + BO:372 RBAC MATRIX (read-only) | |
| `support` | BO table + sheet (`support`) | |
| `reports` | KPI + charts + exports | |
| `integrations` | BO:543 DEV TABS / `shipping` module table | |
| `privacy` | No equivalent. Design from the system: BO generic table + sheet | |
| `profile` | Account pages in the BO shell (`staff-console/(console)/account/*`) | |
| `go-live`, `on-call`, `pilot`, `uat` | Remove from nav (section 3) | |
| `sign-in` | Shop:795 auth, staff copy (MFA required) | |

### 2.4 Mobile consumer (`NL/mobile/apps/consumer/app`)
| Northline route | Look like (MA section) |
|---|---|
| `index`, `welcome` | MA:36 SIGN IN launch |
| `sign-in`, `sign-up`, `verify`, `second-factor` | MA:36, :58, :81 VERIFY (`second-factor` is the same code screen) |
| `(tabs)/home` | MA:180 HOME |
| `search` | MA:212 SEARCH |
| `(tabs)/services`, `services/[category]` | MA:469 SERVICES provider list |
| `providers/[slug]` | MA:477 PROVIDER |
| `book/[slug]/service`, `/time`, `/review` | MA:477 → MA:489 PICK A TIME → bottom action bar MA:529 |
| `bookings/[id]/index`, `/booked`, `/eta` | MA:515 BOOKING DETAIL (`booked` = toast plus detail; `eta` = MA:254 tracking style) |
| `bookings/[id]/sign-off`, `/review` | No equivalent. Use a bottom sheet (MA:550) with stars and a note |
| `product/[id]` | MA:222 PRODUCT |
| `(tabs)/cart` | MA:234 CART |
| `checkout`, `pay`, `stripe-redirect` | MA:244 CHECKOUT, MA:357 ADD CARD (native Payment Element) |
| `orders/[id]/index`, `/track`, `/confirmed`, `/delivered` | MA:254 TRACKING; MA:268 ORDERS |
| `(tabs)/orders` | MA:268 ORDERS |
| `problem/[kind]/[id]`, `cases/[number]` | MA:275 RETURN |
| `quotes/[id]` | No equivalent. Use the MA:515 detail layout with line items and a sticky accept bar |
| `(tabs)/account` | MA:296 ACCOUNT |
| `account/profile`, `account/preferences` | MA:308 PROFILE |
| `account/addresses`, `location` | MA:323 ADDRESSES, :331 ADD ADDRESS; location = bottom sheet |
| `account/payments`, `wallet` | MA:348 PAYMENTS (points: later) |
| `account/favourites` | MA:404 SAVED |
| `account/notifications`, `notifications` | MA:420 NOTIFICATION PREFS; MA:412 INBOX |
| `security` | MA:427 SECURITY |
| `account/help` | MA:441 HELP |
| `account/data` | No equivalent. A list row under Security |
| `age-verified` | No equivalent. A bottom sheet during checkout |
| `feedback` | Remove (pilot) |

Tabs: AT has Home · Browse · Lola · Cart · Account. Northline should use **Home · Browse · Activity (orders + bookings) · Cart · Account**: Activity takes Lola's slot, and Services lives inside Browse and Home. Use AT's icon-plus-label tab bar, not Northline's text-only tabs.

### 2.5 Mobile courier (`NL/mobile/apps/courier/app`) → AT driver (DR)
| Northline | AT |
|---|---|
| `sign-in` | DR:31 LOGIN, :46 MFA, :58 DENIED ("not a courier") |
| `(app)/index` (shift) | DR:69 HOME |
| `(app)/run` | DR:132 ROUTE |
| `stops/[id]/index` | DR:147 STOP |
| `stops/[id]/pickup` | DR:109 SCANNER (pickup context). Northline confirms a sealed bag, so use the confirm half and no scanning |
| `stops/[id]/dropoff` | DR:147–198 proof: live camera photo, PIN/handoff code, signature |
| `(app)/account` | DR:209 SYNC / PROFILE |
| `feedback` | Remove |

DR:84 INSPECTION and DR:198 END are later. Use tabs Today · Route · Profile in place of Northline's 3-level stack.

### 2.6 AT features Northline lacks: adopt, skip or later
| AT feature | Where | Call | Why |
|---|---|---|---|
| Provider Hub tools (calendar, requests, availability, services & prices, cancellation policy, public page, earnings) | PH, BO:470 | **Adopt now** (restyle Northline's own screens) | The core of "any kind of service". Northline already has the backend. |
| 3-step checkout with delivery preferences and handoff code | Shop:502 | **Adopt now** | Demo path. Northline's dropoff already uses a PIN. |
| Avatar account menu (guest and signed-in) | Shop:60 | **Adopt now** | |
| Search predictions dropdown | Shop:42 | **Adopt now** (Northline `SiteSearch` already suggests; restyle) | |
| Two-person application review | BO `apps` | **Adopt now** | Merges verification and vetting. |
| Onboarding wizard + gate, no sidebar | BO:77/113/235 | **Adopt now** | |
| Dashboard KPI strip + charts, export to PDF/CSV/Excel | BO:263 | **Adopt now** (KPI and charts); exports already exist in the NL DataTable | |
| Right-sheet record detail | BO:811 | **Adopt now** | |
| Announcement bar (console-managed) | Shop:77 | **Adopt (cheap)**, static content for the demo | |
| Cart "Save for later" and per-line select | Shop:425 | **Later**, unless the cart API already supports it | |
| Returns centre layout | Shop:658 | **Adopt** the layout over the NL case flow | after the demo |
| Stripe compliance "Account health" | BO:673 | **Adopt** (NL has compliance) | after the demo |
| RBAC matrix and role guide | BO:372/496 | **Adopt read-only** | after the demo |
| Markets & locations | BO:548 | **Adopt** as the look for provinces and zones | after the demo |
| Mobile inbox, Face ID/PIN lock | MA:412, :95, :109 | Inbox **adopt**; biometrics **later** | |
| Sponsored placements and ads | Shop:250, BO:281 | **Later** | |
| Seller Theme Studio (OKLCH ramps, contrast-vetted themes) | `Theme Studio.dc.html`, `at-theme.js` | **Later**. For now: logo, one brand colour (NL already enforces 4.5:1) and a cover | |
| Plans & subscriptions billing | BO:386 | **Later** | Not a demo concern. |
| Catalog identifiers and barcodes | `Catalog.dc.html`, `at-barcode.js` | **Later** | |
| Lola AI assistant | Shop:1007, MA:451 | **Later** | |
| Support chat and WhatsApp button | impl `SupportChat.tsx` | **Later** | |
| Pre-orders (city windows, countdown, waitlist) | `Pre-Order v2.dc.html` | **Skip** | Built for diaspora shipping waves. Could come back as "drops" much later. |
| Communities (diaspora filter and pages) | `storefront/(shop)/communities` | **Skip** | Not diaspora-specific. |
| Inventory Hub (warehouses, POs, lots, receiving) | `InventoryHub.dc.html` | **Skip** | No first-party retail. |
| House store, "Fulfilled by" and brand badges | Shop:313 | **Skip** | No first-party retail. |
| Feature flags and developer hub screens | BO `flags`, `dev` | **Skip** | |
| Driver inspection and scanner | DR:84/109 | **Skip / later** | |

---

## 3. Thrash list: remove or hide in Northline's UI
Hide these in the UI only. Keep every backend endpoint. "Hide" means removed from nav and routes gated behind a flag.

1. **Visual system.**
   - Newsreader serif headings, the off-white `#F7F4EE` canvas, spruce/honey/rosehip and Phosphor *duotone* all go.
   - Replace them with a white canvas, one sans family with heavy weights (700/800 headings), a black header, and Phosphor regular + fill.
   - `nl-*` classes and `--color-accent/highlight/accent-2` give way to the semantic token names in section 5.
2. **Home** (`features/home/HomeScreen.tsx`). Remove:
   - the time-of-day greeting ("Good evening, Ada · Beltline");
   - the "What do you need in … tonight?" hero question;
   - the Services/Shop/Food scope chips with live counts ("12 pros", "3 open");
   - three sections of text-only tiles with counts;
   - the "four promises" block;
   - "Trusted near you" rows with tier tags;
   - the "Your week" panel and the points/wallet widget.

   Replace all of it with the AT hero, overlapping tiles and rails of real image cards.
3. **Header** (`ui/SiteHeader.tsx`, `shell/Header.tsx`):
   - The FR/EN toggle moves to the footer and the account menu.
   - The separate Sign in and Create account buttons become one avatar menu.
   - Search, currently hidden on home, is always in the header.
   - Services/Shop/Food become the first items of the dark subnav, not standalone header links.
4. **GuestBanner** above cart, booking, orders and account: remove it. Guests sign in at the checkout or booking step, or through the avatar menu.
5. **Pilot and testing tools in the product UI:**
   - `PilotControl` floating "Send feedback" (consumer, studio and console layouts);
   - `PilotFeedback`;
   - the `pilot.$token` route;
   - the onboarding `PilotInviteScreen`;
   - mobile `feedback` screens;
   - the console `pilot`, `uat`, `go-live` and `on-call` screens.

   Hide all of them. They confuse a demo and none is a merchant or customer flow.
6. **Account sprawl** (`features/account/tabs.ts`):
   - There are 11 tabs today, and the account opens on **Wallet & points**.
   - Drop Dietary, Plus, Language (moves to the footer), Wallet & points (later) and the "Sell" tab (moves to the account menu) from the demo.
   - Favourites becomes "Saved".
   - Use the AT card grid.
7. **Food as a separate vertical:**
   - the top-level Food link, `food/*` routes, food checkout and tracking;
   - the studio `kitchen/*` (KDS, combos, kitchen hours).

   Hide them for the demo. Later, treat a kitchen as a vendor whose menu is listings, using the shared Provider page, Cart, Checkout and Tracking.
8. **Payment inside the cart** (`features/cart/CartPage.tsx` with `Payment`, `StepUpDialog`, `AgeCheck`, `PromoPoints`, `TipPicker`). Split it into Cart, a separate Checkout with 3 steps, then Complete.
9. **Studio dashboard copy.** The long generated headline sentence, the "coaching" paragraph and the quality "score" block go. Replace them with a KPI strip, charts and to-do cards. The quality score can come back as one KPI card.
10. **Onboarding order.**
    - Today the steps are `account → business → verification → review → page → listings` inside one rail.
    - Change to AT: basic account, then the wizard with no sidebar, then submit, then the gate.
    - Page design and the first listings come after approval, in the normal Studio.
    - Show only the copy that fits the chosen type (provider, seller or both). The owner complained that "everything" appeared for a declared provider.
11. **PageBuilder** (`features/storefront`: section blocks, reorder, preview, custom domain). For the demo, use only the Provider Hub "Public page" form: logo, cover, bio, gallery, featured work and links. Custom domains and the block builder come later.
12. **Console nav.**
    - It has 3 groups and about 20 screens, including `regions` as a "switchboard", separate `verification` and `vetting`, and `api`.
    - Merge verification and vetting into Applications.
    - Restyle provinces as Markets.
    - Hide pilot, uat, go_live, on-call and api.
13. **Promises, tiers and decorative tags** sprinkled through lists (`Tag tone=accent` tier labels, `nl-kicker` text). Replace them with stars, a review count and an optional verified seal (Shop:906 pattern).
14. **Mobile:** text-only tabs; the `feedback`, `wallet` (points) and `age-verified` standalone screens; courier screens with no tabs. Follow sections 2.4 and 2.5.

---

## 4. Phased build order (demo next week)

**Phase 0: foundations (day 1)**
- Write the DECISIONS entry and update CLAUDE.md/SCREENS.md (done 2026-10-18).
- Brand and token file per section 5. Map the AT semantic layer onto it. Swapping colours alone restyles the mobile apps right away, because `mobile-kit/theme` reads `tokens.json`.
- `@northline/ui` primitives restyled to AT values: Button (pill sizes 32/40/48; primary, secondary, neutral, dark, link), Field/Input/Select/Checkbox, Tag/status badge, Price, Stars, ProductCard, ServiceCard, EmptyState, Sheet (680px), Dialog, DataTable skin (headers, 40px checkbox column, pager, sheet on row open).
- Shells: consumer Header (two bars) + AccountMenu + Footer; BackofficeFrame (black header, 264px grouped sidebar, rail and phone disclosure). Both studio and console use it.
- A nav flag to hide the thrash items in section 3.

*Phase 0 as built (2026-10-18, branch `feat/redesign-foundation`):* the DECISIONS entry and the CLAUDE.md / SCREENS.md
rewrite; the brand files and token build with the contrast gate (§ 5); `<Logo/>` and `{brand}` copy with the lint;
the `@northline/ui` primitives restyled (buttons in five variants and three sizes, fields, checkbox, radio, switch,
option cards, chips, tags, panels, KPI strip, alerts, menus, dialog, the 680px sheet, empty and error states, product
and shop tiles, the Data Table skin); the consumer header (two bars, avatar menu for guests and signed-in people,
search on every page, language in the footer and the account menu) and footer; the Studio and console shell (dark
header, 264px white sidebar, the 768–1023px rail, the phone sheet); Phosphor regular + fill; and the `VITE_NL_TESTING_TOOLS`
flag that hides the pilot "Send feedback" control, the console's Pilot / UAT / Go-live / API screens and On-call from
the UI. Removed from the shells: the time-of-day greeting, the guest banner. Still to come in later phases: the
page-level items of § 3 (home, account tabs, Food, payment in the cart, Studio dashboard copy, onboarding order,
PageBuilder, console Applications/Markets, tier tags) and the ProductCard / ServiceCard / Price / Stars rebuilds that
belong with their pages.

**Phase 1: consumer demo path (days 2–4)**
1. Home → 2. Search results (mixed products and services) → 3. PDP → 4. Services list and booking aside / Provider page → 5. Cart → 6. **new** Checkout (address, payment, review) → 7. Checkout complete → 8. Order detail with tracking, and Booking detail → 9. Account menu, Orders list and Account card grid → Sign in / Register (2 columns).

Have demo seed data ready: listings with images, at least 2 service providers with open slots, one order in transit.

*Phase 1 as built (2026-10-18, branch `feat/redesign-consumer`, consumer side):* `Price`, `Stars`, `QuantityStepper`,
`ProductCard` and `ServiceCard` in `@northline/ui`; the home (hero carousel, overlapping tiles, product and service
rails from the public search), search (summary bar, Type / Category / Distance / Availability / Price aside, one grid of
product and service cards), the 4-column PDP, the cart without the payment, the new 3-step `/checkout` (address and
time, payment, review; the same APIs) and `/checkout/complete`, the order detail with tracking, the booking detail,
the slimmed account menu, the account card grid and the Orders | Bookings list; the provider list as service cards and
the booking wizard's aside, day strip and slots. A second flag, `VITE_NL_FULL_NAV`, hides Food as a vertical and the
account's Wallet & points, Dietary, Plus and Language. Not yet: sign-in / register in two columns, the provider page's
layout, quotes, the Deliver-to dialog, demo seed images (`docs/DECISIONS.md` § "Redesign phase 1").

**Phase 2: merchant demo path (days 3–5)**
Register → onboarding wizard (no sidebar) → submitted gate → (approve in the console) → Dashboard (KPIs) → Listings table → New listing form (product, and the service/price variant) → Orders table and sheet (status update) → Appointments calendar → Availability.

**Phase 3: staff console basics (day 5)**
Overview KPIs → Applications (two-stage review and sheet with every submitted field) → Sellers & providers → Orders → Disputes. All on the generic DataTable and Sheet.

**Demo rehearsal (day 6).** Walk the script end to end: customer books a service, buys a product, pays, tracks; a merchant signs up and gets approved, adds a listing, fulfils an order; staff approve the application. Fix only what the walkthrough shows.

**Phase 4: after the demo**
- Mobile consumer reskin per section 2.4: tab bar, Home, Browse, PDP, Cart, Checkout, Tracking, Booking.
- Courier reskin per section 2.5.

**Phase 5**
- Account sub-pages (addresses, payments, security, notifications) and returns/cases.
- Quotes (request form, quote page, compare).
- Messages, earnings/payouts, refunds, reviews, reports, compliance (Account health), settings/team with the role guide.
- Console finance, markets, team/RBAC, trust/moderation, support, privacy.

**Phase 6**
Food re-introduced as vendor menus, provider public-page design, announcement and ad placements, the seller theme studio, Lola, saved-for-later, biometrics.

---

## 5. Brand parameterisation (as built, 2026-10-18)

**One brand file, one setting.** `web/packages/tokens/`:
- `brand.json` — identity (`name`, `shortName`, `legalName`, `domain`, `supportEmail`, `wordmark`), the chosen
  `palette`, the radii (`xs 4 · sm 6 · md 8 · lg 12 · pill 999`). The name is the working name until the owner picks
  one.
- `palettes/{lakeshore,evergreen,dusk}.json` — the only brand-specific colours (`primary`, `primaryHover`,
  `primaryText`, `accent`, `accentText`, `accentTint`, `accentInk`, `ground`, `surface`, `border`, `borderStrong`, `ink`,
  `textMuted`, `textSubtle`, `headerBg`; optional `danger`/`success`/`warning`), the fonts (`sans`, optional `display`
  for headings), and the logo mark (SVG shapes whose colours are semantic role names).
- `scale.json` — **fixed, not brand**: spacing, type scale (and its fluid clamps), weights, leading, tracking, control
  sizes, page grid, shadows, z-index, motion (§ 1.2). A rebrand cannot change the rhythm.

**Build** (`pnpm --filter @northline/tokens build`; `lib/theme.mjs`, `lib/oklch.mjs`, `lib/contrast.mjs`):
- **Contrast gate** — the port of AT's `vet()`: every text pair the components draw (body and muted text on the page
  and cards, links, every button's label on its fill and hover, header, department bar, footer, tags and alerts, the
  cart count, the current nav item; 4.5:1) and every control outline and focus indicator (3:1) is checked **on every
  palette**; a failure stops the build with the pair, the ratio and the colours. Brand-supplied colours are never
  adjusted; derived text tones (status text on its tint, the rating number, the header's muted text) are darkened or
  lightened until they pass. The package's `test` script runs the same gate (`node build.mjs --check`), and
  `packages/ui/src/contrast.test.ts` lists every pair per palette.
- `dist/tokens.css` — layer 1 primitive ramps (`--primary-50…900`, `--accent-…`, `--neutral-0…1000`, `--danger-…`,
  `--success-…`, `--warning-…`), layer 2 semantic roles (`--color-canvas`, `--color-surface*`, `--color-text*`,
  `--color-border*` incl. `--color-border-control` (3:1 control outlines), `--color-header-*`, `--color-subheader-*`,
  `--color-footer-*`, `--color-action-{primary,accent,neutral,dark,danger}*`, `--color-link*`, `--color-focus*`,
  `--color-price`, `--color-rating*`, `--color-{success,warning,danger,info}(-bg)`, `--color-badge-*`,
  `--color-cart-badge*`, `--color-nav-active-*`, `--color-overlay`), fonts (`--font-sans`, `--font-display`,
  `--font-heading`), the scale (`--space-*`, `--text-*`, `--weight-*`, `--size-*`, `--page-*`, `--radius-*`,
  `--shadow-*`, `--z-*`, `--duration*`, `--ease`), layer 3 store theme (`--store-*` → the semantic roles), the web-only
  responsive layer (fluid type, notch-safe gutter, 44px controls and 12px minimum text on coarse pointers), and the
  **deprecated aliases** (`--color-accent*`, `--color-highlight*`, `--color-neutral-*`, `--color-bg`, `--font-body`, …
  pointed at the semantic roles) so screens not yet rebuilt wear the new brand meanwhile.
- `dist/palettes.css` — every palette scoped under `[data-palette]` (Storybook's palette switch).
- `generated/theme.json` (committed) — the resolved theme: brand identity, every semantic colour and ramp, fonts and
  their Google Fonts URL, the scale, CSS and React Native shadows, the logo. TypeScript imports it through
  `@northline/tokens` (`brand`, `theme`, `fontHref`); the native apps' `mobile-kit/theme` and `app.config.ts` read it.
- `generated/favicon.svg` (committed) — the mark, copied into the web apps' `public/` and the docs site.
- `vite.mjs` — `brandHtml()` fills `%BRAND_NAME%` / `%BRAND_FONTS%` in the SPAs' `index.html`.

**One `<Logo/>`** in `@northline/ui`: the palette's mark (colours as semantic tokens) plus the wordmark text in
`currentColor`, with an optional portal word ("Studio", "Console"). Nothing else draws the name as markup. (A
`react-native-svg` Logo in mobile-kit comes with the native reskin.)

**Name in copy.** Messages say `{brand}` (and `{brandShort}`); `useT` / `messagesFor` fill them from `brand.json` in
both languages. Code uses `brand.name`. `pnpm lint:brand` (`web/scripts/check-brand-literal.mjs`, run by `make
web-lint`) fails on the literal current name in the string literals, template strings and JSX text of `apps/*/src`,
`packages/ui/src` and `packages/auth-kit/src` (tests excepted; a genuine exception carries `brand-literal-ok`).
`pnpm lint:tokens` keeps the deprecated token names out of `@northline/ui` and the three app shells.

**Leave alone until the name is chosen:** the package scope `@northline/*`, the Java packages, the mobile bundle ids
and app names (`app.config.ts`; a new bundle id is a new store app), the infra domains, and the backend's own copy
(email templates, `messages*.properties`, `application*.yml`) — renamed in one sweep later.

**Not done yet (later phases).** Self-hosted `@fontsource` fonts (the apps load the palette's Google Fonts, which the
CSPs already allow); the backend's `app.brand.*` served through `/api/v1/config`; the native apps' fonts, icons and
splash from the palette's mark; the announcement bar; the header search's department select.

**Merchant themes stay layer 3.** The `--store-primary`, `--store-on-primary`, `--store-accent` and `--store-ink` defaults point at the platform semantic tokens, and a merchant's vetted brand colour overrides them only inside their provider or store page. NL's `BrandMark` and the 4.5:1 page-builder check already do this.
