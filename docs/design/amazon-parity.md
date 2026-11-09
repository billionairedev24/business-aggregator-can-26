# Amazon parity audit: consumer web (S-191, 2026-11-09)

**Why:** after the deploy the owner wrote: "this website does not match amazon in any way", "the footer is so dry" and
"the current website is also bizarre on mobile. Please we gotta fix that". This file compares the consumer site with
Amazon's own patterns, page by page, at desktop (1440) and phone widths (390 and 360). It lists every gap, ranks them,
and says which ones S-191 fixed and which ones are still open (the open ones are backlog rows S-191a to S-191l).

**Method and limits.** The agent proxy blocks amazon.ca (HTTP 403 on CONNECT), so this audit works from Amazon's
well-known desktop and mobile web patterns, not from live pages or screenshots of them. Our side is `make ui-shots`
(the built consumer app on the shots fixtures) before and after S-191, in English and French, at 1440, 390 and 360
wide, plus scrolled phone screenshots for the sticky parts. As in `revamp-epic.md`, we copy Amazon's **patterns**, not
its trade dress: our name, logo, colours (the brand layer's tokens, which S-189 is changing to Northern Pine) and type
stay.

Ranks: **P1** the owner sees it at once on a phone · **P2** visible on most visits · **P3** polish, or needs data or
an api we don't have.

## Ranked gaps

| # | Rank | Area | Width | Gap (before S-191) | Amazon's pattern | Status |
|---|---|---|---|---|---|---|
| 1 | P1 | Home | phone | The hero filled the first screen and more: a three-line 36 px headline, two trust lines, a 112 px run card and three 136 px department tiles stacked one under the other, then the 700 px maker-of-the-week panel. The first product appeared about 2,350 px down, almost three screens. | Products and product cards on the first screen; a slim promo, then a dense card grid. | **Fixed:** compact band (two-line 24 px headline, one trust point, a 76 px run card, the three tiles side by side at 88 px). The card grid follows the band and the deal of the day is its first card, so a product photo and price show at about 540 px. |
| 2 | P1 | Footer | all | One thin band with eight links in a row. | "Back to top" bar; four link columns; a language and region row; a legal strip with conditions of use, privacy, the interest-based ads notice and "© year". Stacked and compact on a phone. | **Fixed:** all of it, in both languages, every link a real route or help-centre section (test-guarded). Two by two on phones, 44 px rows. |
| 3 | P1 | Header | phone | The whole header (about 194 px: logo row, search, place, strip) stayed stuck on every scroll, a quarter of an 844 px screen. | Logo, account and cart in one row, the search under it; scrolling down, only the search stays. | **Fixed:** scrolling down, the logo row tucks up and the place and strip are clipped away, so only the 54 px search stays; scrolling up or focusing the header brings it all back. |
| 4 | P1 | Results | phone | Sort was a full-width select on its own row and Filters a separate button lower down; neither stayed on screen. | Sort and Filter in one bar that sticks under the search. | **Fixed:** "Filters (n)" and Sort in one bar stuck under the header (or under the search alone while scrolling down). |
| 5 | P1 | Product | phone | Photo first, then the title, then the price, then a long description and facts table; the buy box (delivery, quantity, Add to cart, Buy now) started about 1,480 px down. | Store, title and rating above the photo carousel; price, delivery promise, quantity, Add to cart and Buy now right under it; a sticky Add to cart. | **Fixed:** store, title, rating and run tag come first, then the photo, the price and options, then the buy box; the description and facts follow it. The buy box hides its repeated price on phones; the sticky price + Add to cart bar stays. |
| 6 | P1 | Header | phone | The "Deliver to" line and the department strip ran together on the same violet as the bar; the strip's links looked like plain text. | A slim, separate "Deliver to {city}" bar; a sideways-scrolling row of department chips. | **Fixed:** the place is a full-width slim bar on the strip's colour with a hairline; the strip's items are square chips (32 px drawn in a 44 px target, never pills) that scroll sideways. The slot shows whatever the location state says ("Deliver to Calgary", "Set your location"); S-190 owns the behaviour. |
| 7 | P2 | All | phone | Page gutters were 12 px (the scale's fluid `clamp(12px, 2.4vw, 32px)`). | 16 px gutters. | **Fixed** in the consumer shell (phones only, safe areas kept); Studio and console unchanged. |
| 8 | P2 | Cart | phone | Each line's stepper, Delete and Save for later wrapped over two rows with stray "\|" separators, and the price sat alone on its own row. | Photo beside title, price and stock; stepper, Delete and Save for later in one row under it. | **Fixed** (the line's parts join one grid under 480 px; the separators are hidden there). |
| 9 | P2 | Home | desktop | The 240 px maker-of-the-week panel sat between the hero and the card grid, so the cards started about 1,000 px down. | Card grid straight under the hero, then rails. | **Fixed:** the card grid follows the band (the deal first), then the first rail, then the maker hero, then the other rails. A departure from the hybrid mockup's order (band, maker, tiles), made for the owner's feedback. |
| 10 | P2 | Home | all | No "Pick up where you left off" or "Keep shopping for" card (nothing records what someone viewed). | Personal cards first: recently viewed, keep shopping for, buy again. | Open: **S-191a** (recently viewed, browser-only first). |
| 11 | P2 | Home | all | "Deals" is the deal of the day from three popular products; "Today's deals" opens tonight's run (no deals feed in the api). | A real deals card and a deals page. | Open: **S-191b** (needs an api deals feed; S-170 noted it too). |
| 12 | P2 | Results | phone | Cards are 2-up with a full-width "Add to cart" under each, so about 2.5 products show per screen. | A list layout (image left, details right) with more items per screen; Add to cart on the card or on the product page. | Open: **S-191c** (a list/grid toggle or the list as the phone default; touches ItemCard and the a11y baselines). |
| 13 | P2 | Product | phone | No swipeable image carousel: one photo with a "1 / 3" count and a thumbnail row. | Swipe carousel with dots. | Open: **S-191d**. |
| 14 | P2 | Header | desktop | The account menu opens on click only; no "Returns & Orders" shortcut beside it. | Hover flyout "Account & Lists"; a separate "Returns & Orders" link. | Open: **S-191e** (CLAUDE.md keeps orders in the account menu; owner decision). |
| 15 | P3 | Home | all | Rails show 7 products at 1280 with full buttons; no "sponsored"/"best seller" badges or "#1 in" ranks. | Dense rails, badges, ranks. | Open: **S-191f** (needs sales ranks in search). |
| 16 | P3 | Results | desktop | The summary line is small; no "Results" heading or "Customers also bought" interleaved rails. | Interleaved rails, a results heading. | Open: **S-191g**. |
| 17 | P3 | Product | desktop | No "Frequently bought together", Q&A or review list (the api has a shop rating, no product reviews). | All three. | Open: **S-191h** (api work). |
| 18 | P3 | Footer | all | Language is a two-way toggle and the region a static "Canada · CAD $": there is no other region or currency. | A language and region picker. | Open: **S-191i** (only when there's a second region or currency). |
| 19 | P3 | Checkout | phone | Already Amazon-like (one page, numbered sections, a sticky total + Place order bar). Review items use a select for quantity at 44 px. | Same. | No change. Small: **S-191j** (review line density). |
| 20 | P3 | Header | phone | The "All" sheet lists categories only; no account, orders or help block at its top. | The hamburger opens a menu with the account, "Shop by department", programs and help. | Open: **S-191k**. |
| 21 | P3 | All | phone | A layout shift of about 0.37 as the header's first row settles after hydration (S-188 measured it on develop too). | No shift. | Open: **S-191l**. |

Not gaps (kept on purpose): the brand layer's violet chrome and yellow deal stickers (our trade dress, now being
re-tokened by S-189), the hero band itself (the owner asked to keep it, compact on phones), the department strip on
desktop, square corners, IBM Plex Sans, the location dialog (never a geolocation prompt on load).

## Page by page

### Header and nav
- **Desktop.** Search-led bar (logo · Deliver to · scoped search · Hello / Account & orders · cart with count) and the
  department strip: already Amazon's layout (S-170). Unchanged by S-191.
- **Phone (fixed).** Row 1: menu · logo · account · cart. Row 2: the full-width search. Row 3: the slim "Deliver to
  {city}" bar (the S-190 location state decides the words). Row 4: department chips that scroll sideways. Scrolling
  down keeps only the search; up brings the rest back (`useStickyOffset` `--nl-header-clip`).

### Home
- **Phone (fixed).** Compact band → 2-column card grid (deal of the day, Shop departments, Book trusted pros, Kitchens
  open now; four pictures a card) → "Popular near {city}" carousel → maker of the week → pros and kitchens carousels.
  Products are on the first screen at 390 and 360, in English and French.
- **Desktop (fixed order).** Band → the four cards → rails, the maker hero after the first rail.

### Results
- Desktop: left filter rail, summary with Sort at its right end, 5-up dense cards (S-171), unchanged.
- **Phone (fixed).** Summary line, then the sticky Filters + Sort bar, applied-filter chips in one sideways row (shown
  only when there are some), 2-up cards with image, title, rating, price, unit price and the delivery promise.

### Product
- **Phone (fixed).** Store link, title, rating, run tag → photo (full bleed, "1 / 3") and thumbnails → price and options
  → buy box (delivery window and cut-off, stock, quantity, Add to cart, Buy now, sold by, payment held, other sellers)
  → description and facts → more from the shop, reviews. The sticky price + Add to cart bar stays at the bottom.
- Desktop: thumbnails · photo · details · 300 px buy box, unchanged (details now in three blocks in the same column).

### Cart and checkout
- **Cart phone (fixed):** subtotal and "Proceed to checkout" first (S-173), then lines with the photo beside name,
  price and stock and the actions in one row.
- **Checkout:** one page of six numbered sections, the sticky total bar on phones: no change needed.

### Footer (fixed)
"Back to top" · **Get to know us** (How {brand} works → /help, Local shops → /shop, Find a pro → /services, Order food
→ /food, Today's deals) · **Make money with us** (Sell on {brand}, Offer a service, Run a kitchen → /sell?type=…,
Every way to join → /sell) · **Payments and policies** (Payment held until delivery → /help#help-pay, Payment methods →
/account?tab=payments, Accessibility → /help#help-a11y, Photo credits → /credits) · **Let us help you** (Your account,
Your orders, Your bookings, Returns & refunds, Your delivery location → /location, Help) · logo, FR/EN switch,
"Canada · CAD $" · Conditions of use, Privacy notice, Cookies and ads notice (→ privacy § 9) and "© {year} {company}"
(the render clock's year, so no hydration error).

## Screenshots
Before and after, en and fr, 1440 / 390 / 360: `$SCRATCH/s191-shots/{before,after}/` (named `<route>[-fr]-<width>.png`;
the "before" set covers the routes from home to account-notifications, the run stopped at its time limit), scrolled
phone states in `after/scrolled/`.
