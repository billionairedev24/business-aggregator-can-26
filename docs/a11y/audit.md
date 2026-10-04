# Accessibility audit — WCAG 2.2 AA (S-109)

Audit of the Studio (`web/apps/studio`), the consumer web (`web/apps/consumer`) and the platform console
(`web/apps/console`) against WCAG 2.2 level AA, with cheap checks of the consumer app (`mobile/apps/consumer`).
Done 2026-10-02 on main at S-107. Result: **no critical or serious issue left**; 2 moderate and 6 minor issues are
ticketed in [tickets.csv](tickets.csv).

**Follow-up 2026-10-04 (S-140–S-148):** the eight ticketed issues are fixed and each has a test (§ Follow-ups); the
page sweep now fails on them. The screen-reader pass (S-148) has its automated part done — ARIA snapshots of the key
journeys and Testing Library checks of names, roles and states — and a [manual script](screen-reader-script.md);
**no person has run it yet**, so S-148 stays open.

## Method

1. **Read the rules the product set itself**: `docs/SCREENS.md` § Every screen must implement (focus ring, 44 px
   targets, no horizontal scroll at 320 px), the tokens, `docs/MOBILE_PLAN.md` § Accessibility.
2. **Component level, automated** (runs in `pnpm test`): axe-core through `vitest-axe` over every `@northline/ui`
   component in its main states (`packages/ui/src/a11y.test.tsx`, en and fr), plus behaviour axe cannot see —
   focus moves, traps and returns, keyboard models, announcements (`a11yBehaviour.test.tsx`). In the apps, the axe check
   sits inside the existing Testing Library tests once the screen has loaded: Studio 22 checks in 17 test files,
   consumer 19 in 17, console 22 in 21. Rules: WCAG 2.0–2.2 A/AA tags; `color-contrast`, `target-size` and `region`
   are left to the levels below (jsdom has no layout).
3. **Token contrast, computed** (`packages/ui/src/contrast.test.ts`): the colour pairs the components use, resolved
   from `tokens.json` + `derived.css` (the `color-mix(in oklch)` ramps, same maths as the native kit), 4.5:1 for text
   and 3:1 for borders of controls, focus rings and icons.
4. **Page level, automated** (`make a11y`; a manual CI job): Playwright 1.56 + `@axe-core/playwright` against the
   **built** apps (`vite preview` for the Studio and console, the Node server for the consumer site). No backend: a
   mock api answers with what the apps' own vitest suites answer (recorded by `pnpm --filter @northline/a11y record`
   into `web/packages/a11y/fixtures/`), plus a few overrides (session, one merchant per portal, an admin's grants). Each
   journey screen is checked at 1280 px in English and at 320 px in French: axe (WCAG A/AA + best practice),
   `<html lang>`, reflow (no horizontal scroll), text spacing (the 1.4.12 values), focus not hidden by the sticky top bar
   (30 Tab stops), no running animation under `prefers-reduced-motion`, and a count of targets under 44 px. Critical or
   serious WCAG violations, a wrong `lang` and horizontal scroll at 320 px fail the run; each page writes
   `a11y-results/pages/<page>.json`.
5. **Manual review** of the code and the rendered pages against the 2.2 criteria and the topics the story lists
   (below), with keyboard only and the accessibility tree (Chromium).
6. **Consumer app** (cheap wins only): a Jest + React Native Testing Library test walks every pressable control on 17
   key screens and checks role, name and a touch area of at least 44 pt (`__tests__/a11y.test.tsx`).

### Tools

axe-core 4.13 (`vitest-axe` 0.1, `@axe-core/playwright` 4.13), Playwright 1.56 with Chromium 1194, Testing Library
(React, React Native), the WCAG contrast formula over OKLCH-mixed tokens. Not used: real screen readers (see ticket
S-148) — the checks read the accessibility tree, they do not listen.

### Pages covered by the sweep

| app | journey | screens (path) |
|---|---|---|
| Studio | sign-in | `/sign-in`, `/register` (signed out) |
| Studio | onboarding | `/onboarding/business` |
| Studio | catalogue editing | `/b/…/listings`, `/b/…/listings/p1` (product editor) |
| Studio | orders | `/b/…/orders` (seller) |
| Studio | finance | `/b/…/earnings`, `/b/…/payouts`, `/b/…/reports` (charts) |
| Studio | KDS | `/b/…/kitchen/live`, `/b/…/kitchen/menu` |
| Studio | settings | `/b/…/settings` |
| consumer | search, product, service | `/`, `/search?q=sourdough`, `/products/P1`, `/services/mobile-mechanic`, `/providers/prairie-wrench` |
| consumer | cart & checkout, booking | `/cart`, `/providers/prairie-wrench/book` (signed in) |
| consumer | account | `/account`, `/account/orders` (signed in), `/sign-in` |
| console | queues | `/verification`, `/vetting`, `/disputes`, `/support` |
| console | privacy, retention, audit | `/privacy`, `/privacy?view=retention`, `/team` (audit log) |
| console | other | `/sign-in`, `/` (overview charts), `/delivery` (ops map) |

32 screens × 2 (1280 px en, 320 px fr-CA) = 64 page checks; all pass. The component tests cover the rest of each
app's screens.

## Findings

Severity follows axe's impact scale (critical, serious, moderate, minor). "Project rule" marks a requirement of
`docs/SCREENS.md` that is stricter than WCAG AA.

| # | WCAG criterion | severity | area | finding | status |
|---|---|---|---|---|---|
| 1 | 4.1.2 Name, Role, Value | critical | `@northline/ui` OptionCard (Studio sign-in/registration factor, onboarding type, checks dialog, payout frequency; consumer registration, food checkout) | `role="radio"` buttons also carried `aria-pressed` (axe aria-allowed-attr) | fixed: `aria-checked` when given a role |
| 2 | 4.1.2 Name, Role, Value | critical | Studio product editor | the section tabs carried `aria-pressed` on `role="tab"`; the fieldset claimed `role="tabpanel"` | fixed |
| 3 | 2.4.1 Bypass Blocks | serious | Studio, console (AppShell), onboarding | no skip link past the top bar and sidebar | fixed: "Skip to content" / « Passer au contenu », focusable `main` |
| 4 | 3.1.1 Language of Page | serious | Studio, console | opened in French (saved choice or browser), `<html lang>` stayed `en-CA` until the language was switched | fixed: the i18n provider sets it from the first render |
| 5 | 4.1.2 Name, Role, Value | serious | `@northline/ui` Meter | unnamed `role="meter"` when the label was not a plain string; the dispute meter announced 91 while showing 0.9 % | fixed: `aria-labelledby` + `aria-valuetext` |
| 6 | 2.5.7 Dragging Movements | serious | Studio kitchen menu builder | sections reordered only by dragging or arrow keys on a handle | fixed: move up / move down buttons |
| 7 | 1.4.3 Contrast (Minimum) | serious | kit inputs; Studio notification matrix, availability; console reports; consumer location | placeholders 4.48:1 (4.19:1 in the Studio), muted text at 2.8–4.2:1 | fixed: neutral-700 (6.5–7:1) |
| 8 | 1.4.11 Non-text Contrast | serious | kit inputs, checkboxes, radios, switch, multi-select | field borders 1.5:1, check boxes 2.1:1, switch knob on its off track 1.5:1 | fixed: neutral-600 (≥ 4.2:1) |
| 9 | 1.1.1 Non-text Content | serious | `@northline/ui` charts (Studio dashboard and reports, console overview) | the numbers were only in hover tooltips; the SVG had a title only | fixed: each chart carries its data as a visually hidden table (not where the screen already shows one) |
| 10 | 4.1.3 Status Messages | serious | Studio KDS | the whole board was an `aria-live` region, re-read on every poll and every 30 s clock tick | fixed: one polite line names the orders that arrived; buttons described by their order ref |
| 11 | 1.4.10 Reflow | serious | console vetting (and the Data Table) | long French tags scrolled the card list sideways at 320 px | fixed |
| 12 | 3.3.8 Accessible Authentication | moderate | every code field (Studio, consumer, console) | a pasted "123 456" was cut to "123 45" by `maxLength=6` | fixed: spaces and dashes dropped, no truncation |
| 13 | 2.4.3 Focus Order, 2.1.2 | moderate | AppShell below 900 px | the off-canvas menu did not keep focus; closing it lost focus | fixed: modal sheet, focus returns to the menu button |
| 14 | 2.4.3 Focus Order | moderate | `@northline/ui` Dialog/Drawer | with nothing focusable inside, Tab reached the page behind | fixed: the dialog takes focus |
| 15 | 2.4.11 Focus Not Obscured | moderate | sticky top bars (all three sites) | nothing kept a focused control from scrolling under the bar | fixed: `scroll-padding-top` from the bar's height; the sweep found no hidden focus |
| 16 | 2.5.3 Label in Name, 3.1.2 | moderate | consumer header | the FR/EN button showed "EN" but was named "Switch language — Français", in English | fixed: "EN — Switch language: Français", with `lang="fr-CA"` on Français |
| 17 | 4.1.3 Status Messages | moderate | `@northline/ui` Data Table | sorting and filtering changed the rows silently | fixed: "Sorted by Name, ascending", "3 matching listings" |
| 18 | 2.4.7 Focus Visible | moderate | kit menus, account menu | focus shown only as a 1.1:1 tint | fixed: 2 px ring |
| 19 | 2.1.1 Keyboard (ARIA tabs) | moderate | kit ChipTabs, UnderlineTabs | every tab a Tab stop, no arrow keys | fixed: one tab stop, ←/→, Home/End |
| 20 | 1.4.12 Text Spacing | moderate | consumer header, console overview | links and SLA lines did not wrap with user spacing at 320 px | fixed |
| 21 | 1.3.1 (landmarks) | moderate | console | two unnamed search landmarks | fixed: the global search is named |
| 22 | 2.1.1 Keyboard (ARIA radios) | moderate | OptionCard radio groups | each option is a Tab stop; no arrow keys | fixed (S-140): kit `RadioGroup` — one Tab stop, arrows, Home/End — on every button-radio group |
| 23 | 1.4.12 Text Spacing | moderate | Data Table (console queues at 1280 px) | the column budget is measured without user text spacing; the table overflows | fixed (S-141): the rendered table is measured; it drops columns, then shows cards |
| 24 | 2.5.8 Target Size (project rule) | minor | kit buttons, chips, tabs, menu and nav items | 40 px controls (WCAG AA asks 24, the design 44) | fixed in the kit: 44 px |
| 25 | 2.5.8 Target Size (project rule) | minor | screens' own controls | the sweep still counts controls under 44 px per page (Data Table row actions 36 px, chips 32–34 px; inline text links are exempt) | fixed (S-143): none left on the swept screens; the sweep fails above zero |
| 26 | 2.3.3 Animation (AAA; reduced motion) | minor | kit | only skeletons honoured `prefers-reduced-motion` | fixed: global rule; the sweep finds no running animation |
| 27 | 2.1.1 (menu keys) | minor | kit Menu | Tab left the menu open; no Home/End | fixed |
| 28 | 2.5.8, 2.5.3 | minor | consumer app home | address kicker 32 pt; its name read the ▾ glyph | fixed: 48 pt touch area, plain label |
| 29 | 1.3.1, 2.4.6 (headings) | minor | Studio payouts and settings; error states (consumer cart, route errors) | levels skip h2; an error state replaces the page and its h1 | fixed (S-142): h2 sections; loading and error states keep the h1 |
| 30 | 1.4.11 Non-text Contrast | minor | kit StepBars | steps still to do drawn at 1.4:1 | fixed (S-144): neutral-600 (≥ 3:1), "Step 2 of 3" for screen readers |
| 31 | 3.2.6 Consistent Help | minor | consumer web | help ("Help & cases") is in the account menu only; guests have none | fixed (S-145): Help in every page's footer, a `/help` page for everyone |
| 32 | 1.4.1 Use of Color | minor | console delivery map | stuck couriers are told apart by colour (the runs table says "stuck" in text) | fixed (S-146): a ringed diamond, in the legend too |
| 33 | 2.5.3 Label in Name | minor | consumer app | other names still read the ▾ glyph (search sort) | fixed (S-147): no glyph in any control's name (★ too); the app test fails on one |

**Counts.** Before: critical 2, serious 9, moderate 12, minor 10. After S-109: critical 0, serious 0, moderate 2,
minor 6 (all ticketed). After S-140–S-147 (2026-10-04): **none open**. Not counted: the manual screen-reader pass
still to do (S-148).

## Manual review notes

- **Focus not obscured (2.4.11)**: fixed as above; dialogs and drawers are modal with a backdrop. The Data Table's toast
  sits at the bottom centre for 2.8 s and can cover a control there briefly (not entirely, and it is transient).
- **Dragging movements (2.5.7)**: the menu builder (fixed), the business-page section list (arrows + drag) and the product
  images (move left/right + drag) all have a single-pointer way. No slider or map pan needs a drag.
- **Target size (2.5.8)**: everything meets 24 px; the kit now meets the design's 44 px (item 24); screens' own small
  controls are ticketed.
- **Consistent help (3.2.6)**: the Studio's Help is always in the sidebar's Help group and the account menu; the
  console's on-call and support live in the same places on every screen; the consumer site's Help is in every page's
  footer (item 31).
- **Redundant entry (3.3.7)**: the journeys reuse what was given — saved addresses and cards at checkout, the booking's
  job details carried to the review step, the registration phone into the code step. No instance found.
- **Accessible authentication (3.3.8)**: passkeys (WebAuthn), TOTP and SMS codes, backup codes; no CAPTCHA or cognitive
  test anywhere; paste is never blocked, codes accept "123 456" (item 12), `autocomplete="one-time-code"` throughout.
- **Keyboard traps, focus order, focus return**: dialogs, drawers and the narrow-screen menu trap Tab while open and
  return focus on close (tests in `a11yBehaviour.test.tsx`); the Data Table's native `<dialog>` restores focus.
- **Skip links and landmarks**: banner, navigation (named), main, contentinfo on all three sites; skip link everywhere.
- **Forms**: labels through `Field` (`htmlFor`), hints and errors by `aria-describedby`, errors as `role="alert"`,
  attention summaries on submit, `aria-invalid`.
- **Contrast in light and dark mode**: the tokens pass (contrast test). Northline has one, light, theme — there is no
  dark mode to check; the test takes a theme list, so a dark theme only has to be added there.
- **Reflow at 320 px, text spacing, reduced motion**: checked on every swept page (items 11, 20, 23, 26).
- **Language**: `lang` follows en-CA / fr-CA (item 4); the consumer site marks category names that have no French
  with `lang="en"`; the FR/EN toggles mark the other language up.
- **Data tables**: Data Table headers are `th scope="col"` with sort buttons and `aria-sort`, sort rank announced, row
  checkboxes named by the row, `aria-rowcount`/`aria-rowindex`, card layout below 600 px with `dl` labels.
- **Charts**: text alternatives as above (item 9); the console's reports already had a table in a `<details>`.
- **KDS live updates**: polite, one line per refresh, only new orders (item 10).
- **Maps**: the consumer order map is a captioned figure whose content is in the timeline next to it; the console
  delivery map is described by the runs and couriers tables below it, and stuck couriers differ by shape (item 32).

## Follow-ups S-140–S-148 (2026-10-04)

| ticket | fix | test |
|---|---|---|
| S-140 radio groups (2.1.1) | `RadioGroup` in `@northline/ui`: one Tab stop (the checked radio, else the first usable one), ←/→/↑/↓ move and select with wrap, Home/End; disabled radios skipped. Used by every button-radio group: Studio sign-in and registration factor, onboarding business type, verification choices and visit slots, payout frequency, weekday and bank mode, availability accept mode, page builder swatches and CTA; consumer registration, food checkout, cart windows and substitutions; console sign-in, region rollout and seller oversight. The console's market stages move focus only (a stage opens a confirmation) | kit behaviour test; one test per screen (Studio sign-in, registration, business type, returns policy, payout frequency; consumer registration, food checkout; console sign-in) |
| S-141 Data Table text spacing (1.4.12) | after rendering, a table wider than its box drops one more column at a time (lowest priority first), then shows cards; re-measured on resize. A long provider-page tag wraps | kit tests (layout maths, cards under overflow); the sweep fails on horizontal scroll with the 1.4.12 spacing — of the page or inside a Data Table — on every swept page at both widths |
| S-142 headings (1.3.1, 2.4.6) | Studio payouts, settings and the editors' French panel: h2 sections. `PageHeadingProvider`: the Studio and console shells name the current screen; `PageSkeleton` and `ErrorState` show that name as the h1 when the page has none (also the route-level pending/error states). Consumer cart: loading and error keep "Checkout" | every console screen loading and failing keeps exactly one h1; Studio finance screens; the cart; the sweep fails on `heading-order` and `page-has-heading-one` |
| S-143 44 px targets (project rule) | kit: skip links, Data Table chips, row actions, check boxes, bulk bar, search field, small buttons; switch hit area. Screens: footer, breadcrumbs, booking steps, auth links, cart line names, kitchen menu builder, payout documents, settings link, dispute evidence (hit areas by padding and negative margin where the line must not move) | the sweep probes each control's hit area (elementFromPoint 21 px around its centre), exempts links in a sentence and visually hidden controls, and fails above zero |
| S-144 step bars (1.4.11) | steps to do at `neutral-600`; `aria-valuetext` "Step 2 of 3" / « Étape 2 sur 3 » | contrast test lists the pair (bg and surface); kit test |
| S-145 consistent help (3.2.6) | footer link **Help** / **Aide** on every consumer page → `/help` (server-rendered): order problems (sign in or Help & cases), shopping as a guest, payments, accessibility, contact (`NL_SUPPORT_EMAIL` when set), businesses | Help page tests (guest, signed in, French, axe); footer tests |
| S-146 map colour (1.4.1) | stuck couriers are ringed diamonds; the legend draws both markers | delivery test |
| S-147 glyphs in names (2.5.3) | the search sort reads "Sort: price, low to high" / « Tri : prix croissant » (▾ on screen only); cards say "rated 4.9" instead of "★ 4.9" | `__tests__/a11y.test.tsx` fails on a decorative glyph in any control's name on the 17 screens; the sort in en and fr-CA |
| S-148 screen-reader pass | automated part: ARIA snapshots of 11 key screens (`pages/journeys.spec.ts`), a Testing Library test of the consumer header's names and states, the per-screen tests above. Manual part: [screen-reader-script.md](screen-reader-script.md) | **open** — needs a person with VoiceOver, NVDA and TalkBack |

### Screen-reader pass (S-148)

Not run yet. To be filled by whoever runs [the script](screen-reader-script.md):

| reader and version | device, browser or app | journeys (A–D) | date | by | result |
|---|---|---|---|---|---|
| VoiceOver (macOS) | | | | | |
| NVDA (Windows) | | | | | |
| VoiceOver (iOS) | | | | | |
| TalkBack (Android) | | | | | |

## Re-running

- Component level: `cd web && pnpm test` (runs in `make web-test`).
- Page level: `make a11y` (builds the three apps, then `pnpm --filter @northline/a11y a11y`): the sweep, the ARIA
  snapshots of the journeys (S-148) and the consumer CSP check (S104-09; alone: `make csp-check`). Chromium: `CHROMIUM=…`, the
  sandbox's `/opt/pw-browsers/chromium`, or `pnpm --filter @northline/ui exec playwright install chromium`.
- After a screen's api changes, `make a11y-record` refreshes the fixtures from the apps' tests.
- CI: GitHub `web.yml` input `a11y`, GitLab `RUN_A11Y=true` — manual only, like every pipeline here.

## Accessibility statement (draft for the sites' footer)

### English

> **Accessibility at Northline**
>
> We want everyone to be able to shop, book and run a business on Northline. Our websites — the marketplace, Northline
> Studio and our staff console — aim to meet the Web Content Accessibility Guidelines (WCAG) 2.2 at level AA, in
> English and in French. Our iOS and Android app follows the same rules.
>
> What we do: every page works with a keyboard and with screen readers, text and controls meet contrast requirements,
> pages reflow on small screens and when you enlarge text, we respect your reduced-motion setting, and sign-in never
> asks you to solve a puzzle — you can use a passkey, an authenticator app or a code we text you, and paste it.
>
> What we know is not perfect yet: we check every page automatically, but we have not yet tested every journey by
> hand with each screen reader. We are doing that now.
>
> We test with automated checks on every change and review the pages by hand. If something stops you, tell us: use
> **Help** at the bottom of every page, or write to accessibility@northline.example. We answer within two business
> days and can give you the information in another format.
>
> Last reviewed: October 2026.

### Français (Canada)

> **L’accessibilité chez Northline**
>
> Nous voulons que tout le monde puisse magasiner, réserver et gérer une entreprise sur Northline. Nos sites — le
> marché, Northline Studio et notre console interne — visent la conformité aux Règles pour l’accessibilité des
> contenus Web (WCAG) 2.2, niveau AA, en français et en anglais. Notre appli iOS et Android suit les mêmes règles.
>
> Ce que nous faisons : chaque page s’utilise au clavier et avec un lecteur d’écran, les textes et les commandes
> respectent les exigences de contraste, les pages s’adaptent aux petits écrans et au texte agrandi, nous respectons
> votre réglage de réduction des animations, et la connexion ne vous demande jamais de résoudre une énigme : vous
> pouvez utiliser une clé d’accès, une application d’authentification ou un code reçu par texto, et le coller.
>
> Ce qui n’est pas encore parfait : nous vérifions chaque page automatiquement, mais nous n’avons pas encore testé
> chaque parcours à la main avec chaque lecteur d’écran. Nous y travaillons.
>
> Nous testons chaque changement avec des vérifications automatisées et révisons les pages à la main. Si quelque chose
> vous bloque, dites-le-nous : utilisez **Aide** au bas de chaque page ou écrivez à accessibilite@northline.example.
> Nous répondons en deux jours ouvrables et pouvons vous fournir l’information sous un autre format.
>
> Dernière révision : octobre 2026.

The addresses are placeholders until the support mailbox exists; legal review of the statement belongs with the
other footer documents.
