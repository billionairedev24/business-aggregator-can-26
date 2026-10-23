# E-15 Full UI revamp

**Status: started 2026-10-23 (owner direction).** The UI across every app is not good enough. The light & photo-first /
Evergreen direction of 2026-10-19 (`redesign-spec.md`) is **reopened**: this epic picks a new visual direction, then
rebuilds the design system and every screen on it. Where this file and `redesign-spec.md` disagree, this file wins.

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
1. **S-165 Audit.** Screenshots and root causes. Deploy-level breakage (fonts, CSP, assets) is fixed first, separately.
2. **S-166 Directions.** Three rendered directions. **The owner picks one; no page work starts before that.**
3. **S-167–S-169 System.** Tokens v2, the web component library with a gallery and screenshot tests, and the mobile kit.
4. **S-170–S-179 Screens.** Consumer web, Studio, console, mobile. Each story is checked against the locked mockups at
   1440 and 390 wide, in en and fr-CA.
5. **S-180 Sign-off.** Accessibility (WCAG 2.2 AA), the visual regression gate, docs, and the owner's walkthrough.

## What does not change
Backend, BFF contracts, validation, escrow, i18n (en + fr-CA) and region neutrality: every rule in `CLAUDE.md` still
applies. This epic changes how the screens look and are laid out, not what they do.
