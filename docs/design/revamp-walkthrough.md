# E-15 revamp: the owner's sign-off walkthrough (S-180)

A 20-minute click path through the demo to sign off the revamp: what to look at on each app, and the before / after
pictures to compare. The direction you picked is **A · Plainsight** (`design/revamp/a.html`) with Main Street's
maker-of-the-week hero (`design/revamp/b.html`); `revamp-epic.md` § Locked direction is the contract.

**Where.** The demo: consumer `https://demo.northline.ca`, Studio `https://studio.demo.northline.ca`, console
`https://console.demo.northline.ca` (`docs/runbooks/demo.md`; the demo accounts and their authenticator codes are in
the operator's `.demo/<env>.json`). Look at each step on a laptop **and** on a phone (or a 390 px wide window), and
switch to **Français** once per app (footer or account menu): every screen is bilingual.

**Pictures.** The lead shares two folders. *Before* is the S-165 audit (develop before E-15), named
`<app>-<route>-<1440|390>.png`; *after* is S-180's `make ui-shots`, named `<app>/<route>-<d|m>.png` (1440 / 390) and
`<route>-fr-<d|m>.png` in French. One contact sheet per app gives the overview: `s180-contact-sheet-consumer.png`,
`-studio`, `-console`, `-mobile`. Regenerate the after pictures any time with `make ui-shots` (docs/LOCAL_DEVELOPMENT.md).

**Sign-off.** For each step, "as drawn" or a note. The open questions at the end need an answer either way.

**Brand layer (S-188, 2026-10-24).** On top of Plainsight the web now wears the approved Plainsight + Chinook layer
(`design/revamp/hybrid/index.html`, `revamp-epic.md` § Brand layer): the consumer header, department strip and footer
in deep violet, the home's "tonight" hero band, violet buttons, links and focus in all three apps, yellow deal stickers
and ink-on-yellow cut-off pills. Compare each step below with the hybrid mockup's frames; the S-188 before / after
pictures are `s188-shots/before/<app>-<route>-<1440|390>.png` and `s188-shots/after/…` (the lead shares them). The
phone apps get the layer in the next story.

## 1. Consumer web (signed out, then as a customer)

| # | Go to | Look at | Before → after |
|---|---|---|---|
| 1 | Home | The violet header: logo · "Deliver to …" · the white search (All / Services / Shop / Food scope) joined to the violet search button · "Hello, sign in" · cart with its yellow count; the violet department strip ("Today's deals" in yellow); the **hero band** — "Everything *your place* needs, tonight." in Archivo, the trust line, the run card with the time left on a yellow sticker, three department photo tiles across the violet edge (hover one: it lifts, its arrow turns violet); then the **maker-of-the-week hero** (its headline in Archivo); category and deal tiles; rails of 7; the violet footer. IBM Plex Sans for every other word, no pills. | `s188-shots/before/consumer-home-1440` → `s188-shots/after/consumer-home-1440` |
| 2 | Search "sourdough", then Shop scope | 5 results a row; the left filter rail with counts; a rating filter turns into a removable chip (✓ … ×); one price format (`$8.50`, unit price as meta). Phone: Filters (n) opens a sheet. | `consumer-search-1440` → `consumer/search-shop-d`, `search-filters-m` |
| 3 | A product | Photo in the 1:1 grey well; the **buy box** (price, delivery tonight with the cut-off as an ink-on-yellow pill, stock, quantity, Add to cart in violet, Buy now); on a phone the sticky bar keeps the price and Add to cart. | `consumer-product-1440` → `consumer/product-d`, `product-m` |
| 4 | Services → Mobile mechanic → a provider | The booking box: five days and the times as one-choice tiles (solid ink when chosen), "Book Sat … at 9:30 a.m."; trust badges in green; prices `$89.00`. | `consumer-provider-1440` → `consumer/provider-day-d` |
| 5 | Food → a kitchen → checkout | Dishes two a row, "Your order" box; the food checkout's numbered sections. | `consumer-food-kitchen-1440` → `consumer/food-kitchen-d`, `food-checkout-d` |
| 6 | Cart → Checkout (signed in) | Saved for later in the cart; the **one-page checkout**: six numbered sections, every choice shows its selected ink, the sticky summary with Place your order (a sticky bar on phones). | `consumer-checkout-1440` → `consumer/cart-d`, `checkout-d`, `checkout-m` |
| 7 | Account, Orders & bookings, sign-in | The account hub fills the row; statuses are coloured by meaning; sign-in is one centred card (no stock photo, no US flag). The footer is one compact band. | `consumer-account-1440`, `consumer-sign-in-1440` → `consumer/account-d`, `account-orders-d`, `sign-in-d` |

## 2. Studio (a seller, a provider, a kitchen)

| # | Go to | Look at | Before → after |
|---|---|---|---|
| 1 | Dashboard (seller) | White top bar with the business switcher and "/" search; grouped sidebar with the violet marker and icon on the current page (S-188); violet buttons, links and focus; **Needs you now** cards (one job, one button, urgency edge); key figures; the sales columns (round axis steps: $50 · $100 · $150). Phone: the menu opens under the bar. | `studio-dashboard-1440` → `studio/dashboard-seller-d`, `dashboard-menu-m` |
| 2 | Orders | Status tabs with counts, quick filters, dense rows with coloured statuses; tick two rows: the bulk bar with **Mark packed**. Phone: compact cards. | `studio-orders-1440` → `studio/orders-d`, `orders-m` |
| 3 | Products → a product | The editor's sections as underline tabs, the live preview card, the sticky save bar with the listing's status. | `studio-listing-editor-1440` → `studio/listing-editor-d` |
| 4 | Earnings, Payouts, Reports | Page head + KPI strip, columns charts with the current period in ink, statuses by meaning, one price format. | `studio-earnings-1440` → `studio/earnings-d`, `payouts-d` |
| 5 | Store (storefront editor), Settings | Form panels with the sticky save bar; the preview uses the real consumer cards. | `studio-page-1440`, `studio-settings-1440` → `studio/page-d`, `settings-d` |
| 6 | Kitchen: Live orders | Three lanes New → Cooking → Ready, large ticket numbers, one big button each; Pause new orders is red. | `studio-kitchen-live-1440` → `studio/kitchen-live-d` |
| 7 | Onboarding (a new business) | The wizard's own top bar with the step indicator; the chosen business type drawn selected; Continue stays at the bottom of the window. | `studio-onboarding-business-1440` → `studio/onboarding-business-d`, `-m` |

## 3. Console (an admin)

| # | Go to | Look at | Before → after |
|---|---|---|---|
| 1 | Overview | The market scope in the top bar; KPI strip; **Needs attention** cards, most urgent first. | `console-overview-1440` → `console/overview-d` |
| 2 | Sellers & providers | Status tabs with counts, department and At risk filters, saved views; **At risk** counts only this scope's live businesses with a flag and opens exactly those; tick a row: the red **Suspend…**. | `console-sellers-1440` → `console/sellers-d`, `sellers-selected-d` |
| 3 | A seller | The record page: header with status and key facts, Standing tab (signals, then **Consequences and coaching**), the right-hand action panel. | `console-seller-1440` → `console/seller-d` |
| 4 | Applications → one; Disputes → one; Support → one case | Each is a page now (old links with `?application=`, `?case=`, `?ticket=`, `?seller=` open it). | `console-verification-review-1440` → `console/verification-review-d`, `dispute-d`, `support-case-d` |

## 4. Phone apps (consumer and courier)

Not run on a device yet (S-184): these are the react-native-web pictures at 390 × 844 (`make mobile-shots`). On a
phone, if you have the Expo build: the same screens.

| # | Screen | Look at | Before → after |
|---|---|---|---|
| 1 | Consumer home | The header, the department tabs, the cut-off strip, the maker of the week, product cards with Add to cart. | `s169-shots/before/consumer/07-home` → `s178-shots/after/consumer/08-home` |
| 2 | Search results, product, provider | Filter chips (applied = ink edge with ✓), two-up cards, the buy box and its sticky bar, option cards. | `10-search-results`, `14-product`, `13-provider` → `11-search-results`, `15-product`, `14-provider` |
| 3 | Cart → checkout | The one-page checkout and its sticky total. | `16-checkout` → `17-checkout` |
| 4 | Courier: today, route, a stop | Ink on white, large figures, 44 pt stop numbers, full-width actions, the sealed-bag switch. | `s169-shots/before/courier/02-today`, `03-route` → `s178-shots/after/courier/02-today`, `03-route`, `08-stop-dropoff` |

## 5. Your answers needed

1. **Food on the phone app.** The web shows Food everywhere (as the mockups do); the consumer app still hides it
   (S-185). Show it in the app too, or hide it on the web again?
2. **"Today's deals"** opens tonight's pooled-run items (there is no deals feed yet).
3. **Saved for later** lives in the browser until the cart api has a saved list (S-183).
4. A **device pass** of both phone apps before launch (S-184).
5. Sign-off of the walkthrough above (backlog S-180's acceptance criterion).
6. **Hero copy (S-188).** The trust line says "Payment held until you confirm delivery" and "Missing items refunded
   automatically", as in the approved mockup; confirm both are promises the operations team keeps.
