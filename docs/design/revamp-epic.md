# E-15 Full UI revamp

**Status: started 2026-10-23 (owner direction); built 2026-10-24 — S-165–S-182 merged, S-180 QA done, the owner's sign-off walkthrough is `revamp-walkthrough.md`.** The UI across every app is not good enough. The light & photo-first /
Evergreen direction of 2026-10-19 (`redesign-spec.md`) is **reopened**: this epic picks a new visual direction, then
rebuilds the design system and every screen on it. Where this file and `redesign-spec.md` disagree, this file wins.

## Locked direction (owner, 2026-10-23): A · Plainsight, with Main Street's maker-of-the-week hero
The reference mockups are in `design/revamp/` (`a.html` is the locked direction; `b.html` supplies the home hero; `c.html`
is kept for the record). **When a story and these mockups disagree, the mockups win.**
- **Type:** IBM Plex Sans for all UI, 400/500/600/700, self-hosted from `@fontsource` (the CSPs allow `'self'` only).
  Body 14 px, meta 12 px, card titles 13.5 px, page titles 24 px. No display face; Fraunces and DM Sans are retired.
- **Colour roles:** action `#1747C9` (Add to cart, Book, Place order, links) · selected `#121418` (segments, chips, tabs,
  checkboxes turn solid ink) · commerce `#B0187F` (deals, cart count, cut-off timer) · success `#137A3A` · warning
  `#8F5600` · danger `#B42318` (Suspend and every destructive action) · info `#1F5FA8` · line `#C8CDD5` (3:1) · well
  `#F3F4F6`. One role per colour: the action colour is never a status, the commerce colour is never a button.
- **Shape:** 3 px corners on controls, 4 px on panels. Buttons, underline tabs, joined-box segmented controls and
  square-cornered chips must never look alike. Every choice has a visible selected state.
- **Density:** 5 results per row at 1280, 7 per rail; a left filter rail with counts and removable applied-filter chips.
- **Photos:** product on white: every photo sits in a 1:1 grey well with a 12 px inset. The fallback is a dashed well with
  the category icon.
- **Home:** search-led header and department strip, a delivery cut-off strip, then **Main Street's maker-of-the-week
  hero** (one featured local business with its photo, story and two actions, plus two side tiles), then category and
  deal tiles and rails, all in Plainsight's type, colours and shapes.
- **Prices:** one format: `$8.50` / `8,50 $`, with unit price as meta.

## Brand layer (owner, 2026-10-24): Plainsight + Chinook, in Northern Pine (owner, 2026-11-09)
The owner approved `design/revamp/hybrid/index.html` (S-188). Plainsight's pages stay; Chinook's brand layer goes on top.
**Where the hybrid mockup and the values above disagree, the hybrid wins.**
Northern Pine (owner, 2026-11-09) replaces the violet: the owner deployed the violet look, didn't like the purple and chose
Northern Pine from four palettes (`design/revamp/palettes/index.html`, S-189; its token diff lists the 52 values changed).
Only colours changed; **for colour, the Northern Pine section of the palettes page wins over the hybrid mockup.**
- **Consumer chrome:** header `#0F2A21`, department strip `#173B2E` (hover `#22503F`), footer `#0F2A21`; white text,
  meta `#B5D2C4`; the white search field (scope on `#EAF2EE`) joins a spruce search button. No spruce text on the forest
  chrome (2.38:1): links and focus there are white or `#8FDCC4`.
- **Home:** Chinook's hero band first ("Everything <place> needs, tonight.", trust line, the run card with the cut-off,
  three department photo tiles straddling the forest edge), then the maker-of-the-week, then the tiles and rails. The
  band is forest with a pine glow `#1F7A5E` (peak `#15523F`) and a faint gold glow at the lower left; "tonight." and the
  trust line's icons are gold `#F7C75A`, the trust line `#D4E7DD`.
- **Action colour:** spruce `#0E6B58` (hover `#0A5546`, halo `#BFE4D9`) for buttons, links and focus in every app.
  Studio and Console chrome stays white; the current-page marker and icon are spruce on `#E7F3EE`. Spruce (168°) is not
  the success green `#137A3A` (142°): success is only ever a tinted tag with an icon, never a solid button.
- **Deals:** harvest gold `#F5B82E` with ink text (soft surface `#FDEFC9`), as tilted stickers with a 1.5 px ink shadow;
  "Today's deals" on the strip is `#F7C75A`. Gold is never text on a light surface.
- **Type:** Archivo expanded 800 (self-hosted) for the hero, department tiles and the maker-of-the-week headline only.
- **Unchanged:** IBM Plex Sans for UI, selected states in ink `#121418`, shapes, density, layout, photo wells, the hero
  and the sticker mechanic, status colours, red destructive.

## Owner inputs
- **Direction:** reopened, not only re-executed.
- **Scope:** consumer web, Studio, console, consumer mobile app, courier app.
- **Reference:** Amazon.

## What "like Amazon" means here
We take Amazon's **patterns**, not its brand. Northline keeps its own name, logo, colours and type. We use no Amazon
trade dress (the navy-and-orange chrome, the smile arrow, Amazon wording or assets).

| Area | Patterns we adopt |
|---|---|
| Header | Search-led: logo · deliver-to (our location dialog) · search with a Services / Shop / Food scope · account & orders · cart with count; a department strip under it |
| Density | Many items per screen, small type for meta, little decorative whitespace, utility first |
| Results | Left filter rail (chips and a sheet on phone), sort, dense result cards with price, rating, delivery or availability |
| Detail | Gallery · details · a **buy box** (price, delivery or booking estimate, Add to cart, Buy now) that stays reachable on phone |
| Home | Category and deal tiles, then rails ("Near you", "Order again", "Popular kitchens") |
| Back office | Seller Central-style dashboard of actionable cards; dense tables with bulk actions |

## Order and gates
1. **S-165 Audit (done, `audit-2026-10.md`).** Deploy is fine; the problem is direction and content first, then the
   component system. S-182 fixes the one bug found and makes `ui-shots` show what the demo shows.
2. **S-166 Directions and S-181 photography.** The worst problem is the photos, so a licensed, art-directed set is part
   of the direction, not an afterthought. **S-166:** Three rendered directions. **The owner picks one; no page work starts before that.**
3. **S-167–S-169 System.** Tokens v2, the web component library with a gallery and screenshot tests, and the mobile kit.
4. **S-170–S-179 Screens.** Consumer web, Studio, console, mobile. Each story is checked against the locked mockups at
   1440 and 390 wide, in en and fr-CA.
5. **S-180 Sign-off.** Accessibility (WCAG 2.2 AA), the visual regression gate, docs, and the owner's walkthrough.

## What does not change
Backend, BFF contracts, validation, escrow, i18n (en + fr-CA) and region neutrality: every rule in `CLAUDE.md` still
applies. This epic changes how the screens look and are laid out, not what they do.
