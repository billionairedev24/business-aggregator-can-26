# Screen-reader pass — manual script (S-148)

The automated checks read the accessibility tree; they do not listen. This script is for a person to run the main
journeys with real screen readers before the pilot and record what they hear. It goes with the
[audit](audit.md) (WCAG 2.2 AA, S-109) and its follow-ups (S-140–S-147).

**Status:** written 2026-10-04; **not run yet**. Ticket S-148 stays open until the four passes below are recorded in
[audit.md § Screen-reader pass](audit.md#screen-reader-pass-s-148) and the accessibility statement's "last reviewed"
date is updated.

## What is already checked automatically

So the person running this can spend the time on what a machine cannot judge (is it understandable, in a sensible
order, not too chatty):

- axe on every component and on 32 screens × 2 (`make a11y`), keyboard models, focus traps and returns, live regions;
- the accessibility tree of 11 key screens — landmarks, heading levels, every control's role, name and state — pinned
  as Playwright ARIA snapshots (`web/packages/a11y/pages/journeys.spec.ts`);
- names and states in the apps' Testing Library tests (radio groups, menus, dialogs, tables, the header);
- the consumer app: role, name (no decorative glyphs) and 44 pt target of every control on 17 screens.

## Set-up

| reader | device and browser | version to note | turn on |
|---|---|---|---|
| VoiceOver (macOS) | Mac, Safari (and Chrome once) | macOS, Safari | Cmd + F5 |
| NVDA (Windows) | Windows, Firefox and Chrome | NVDA 2024.x or later | Ctrl + Alt + N |
| VoiceOver (iOS) | iPhone, Safari and the Northline consumer app | iOS | Settings › Accessibility › VoiceOver (or triple-click the side button) |
| TalkBack (Android) | Android phone, Chrome and the Northline consumer app | Android, TalkBack | Settings › Accessibility › TalkBack (or both volume keys) |

- **Environment:** staging (or dev) with the seeded personas of [runbooks/e2e.md](../runbooks/e2e.md): a consumer
  (Amara), a Studio owner of a provider, a seller and a kitchen, and a console admin. Stripe in test mode, so the
  Payment Element is Stripe's own frame. Never production data.
- **Languages:** run each journey once in English; run the consumer web checkout and the Studio sign-in again in
  French (switch with the header's language button or the account menu) and listen for French pronunciation — a
  French page read with an English voice means a wrong `lang`.
- **Speech settings:** default verbosity and punctuation. Note the speech rate you used.
- **Keys you will need** (desktop): VoiceOver — VO = Ctrl + Option; VO + U rotor (headings, landmarks, links, form
  controls); VO + Right next item; VO + Space activate. NVDA — H / Shift + H headings, D landmarks, F form fields,
  B buttons, Tab, Insert + F7 elements list, Enter / Space activate, Insert + Space toggles browse/focus mode.
  Mobile: swipe right / left next / previous, double-tap activate, rotor (iOS: two-finger twist; TalkBack: swipe down
  then right, or the reading-controls menu) for headings and links.

## How to record

For every step: **Do** what it says, then compare with **Expect**. Mark each step ✔ (as expected), ~ (works, but
confusing or too chatty) or ✘ (blocks or misleads), with a note in your words of what you heard. A ✘ or ~ becomes a
finding: add a row to [tickets.csv](tickets.csv) (E-11, the next free id) and a line to the audit's findings table.

---

## A. Studio (desktop: VoiceOver + Safari, NVDA + Firefox)

### A1 Sign in to the KDS

1. **Do** open the Studio sign-in page. **Expect** the page title and language announced; the first Tab reaches
   "Skip to content"; headings list (rotor / Insert + F7) shows one level-1 heading "Sign in".
2. **Do** type the owner's email, press Continue. **Expect** the progress bar read as "Step 2 of 3"; the text
   "Second factor required for …".
3. **Do** Tab to the factor choices. **Expect** one radio group named after that sentence, "Passkey, radio button,
   checked, 1 of 3" (wording varies by reader). **Do** press Down Arrow. **Expect** "Authenticator app … checked, 2 of
   3", and the code field appears after the group. Tab leaves the group in one press.
4. **Do** submit an empty code. **Expect** the error read at once (an alert) and again when focus is on the field
   (its description).
5. **Do** sign in, open a kitchen business, go to Live orders (KDS). **Expect** one level-1 heading with the time of
   day and the number of open orders; buttons named with the order reference ("Accept · start cooking" for FD-…).
6. **Do** wait for a new order (or have a colleague place one). **Expect** one polite announcement naming the new
   order — not the whole board, nothing on the 30-second clock tick.

### A2 Onboarding and finance

1. **Do** start onboarding a new business. **Expect** the business type as one radio group; arrows move and select.
2. **Do** open a verification check that is a choice (returns policy). **Expect** a dialog named after the check,
   focus inside it, the choices as a radio group; Escape closes the dialog and returns focus to the check's button.
3. **Do** open Payouts. **Expect** headings: "Payouts" (level 1), then the sections at level 2 (payout history, bank
   account, schedule, tax documents). **Do** change the schedule. **Expect** the frequency choices as one radio
   group with the fee read as part of each option.
4. **Do** open a page while offline (or after the session expired). **Expect** the page still has its level-1
   heading (the screen's name) above the error, and "Retry" is reachable.

## B. Consumer web (desktop: VoiceOver + Safari, NVDA + Chrome; mobile: VoiceOver + Safari, TalkBack + Chrome)

### B1 Search to checkout

1. **Do** open the home page signed out. **Expect** landmarks: banner, navigation "Main", main, contentinfo;
   the header's language button read as "EN — Switch language: Français" with "Français" in a French voice.
2. **Do** search "sourdough". **Expect** a combobox named "Search"; results announced as a count; each result a link
   whose name has the product, price and shop.
3. **Do** open a product, add it to the cart. **Expect** the quantity group named after the product; the cart link
   in the header now reads "Cart, 1 item".
4. **Do** open the cart signed out. **Expect** the guest banner text, then "Sign in to pay". **Do** sign in (passkey
   or code) and come back.
5. **Do** go through checkout. **Expect** the delivery windows as one radio group (arrows move and select; each
   option reads its time, how it is delivered and its fee); the substitution choices as another; the address and
   its Change button; the total in the "Total" region.
6. **Do** press Pay. **Expect** Stripe's card form announced as a frame ("Secure payment input frame" or Stripe's
   own title), its fields named by Stripe; after confirming, the order page with its level-1 heading.
7. **Do** use the footer's **Help** link, signed out. **Expect** the Help page: level-1 "Help", level-2 sections,
   a link to sign in for help with an order.

### B2 Book a service

1. **Do** open Services, a category, a provider, then Book. **Expect** the booking steps as buttons with their
   number and name ("1 Job details"), the current one marked.
2. **Do** pick a day and time. **Expect** each slot named with day and time; the choice confirmed in text.
3. **Do** pay the deposit (Stripe test card). **Expect** as B1 step 6.

## C. Console (desktop: NVDA + Firefox, VoiceOver + Safari)

1. **Do** sign in as an admin. **Expect** as A1 steps 1–4 (the console's own sign-in).
2. **Do** open the verification queue. **Expect** a table named after the queue, column headers that are sort
   buttons; pressing one announces "Sorted by …, ascending"; filtering announces the number of matching rows.
3. **Do** open a row's actions and a decision dialog; cancel. **Expect** focus returns to the row's button.
4. **Do** open Delivery ops. **Expect** the map as one image named "Couriers and zones in …" with the runs and
   couriers tables after it saying which run is stuck in text (the map's stuck marker is a diamond for sighted users;
   the tables carry the same information for screen-reader users).
5. **Do** open the support queue and a ticket. **Expect** the ticket in a named region next to the list; replies and
   macros reachable in order.

## D. Consumer app (iOS VoiceOver, Android TalkBack)

1. **Do** open the app, go through welcome and sign-in. **Expect** every button named, no "button" without a name,
   headers announced as headings.
2. **Do** on Home, swipe to the address. **Expect** "Delivery address: …, button" — no "down-pointing triangle".
3. **Do** search, then the sort control. **Expect** "Sort: relevance, button, changes the order of the results";
   double-tap → "Sort: price, low to high". In French: "Tri : pertinence".
4. **Do** open a provider list. **Expect** each card read as "name, tier, rated 4.9, 312 reviews, …" — no "star".
5. **Do** check out with PaymentSheet (test card). **Expect** Stripe's sheet read by the system; back in the app,
   the confirmation read.
6. **Do** open Orders. **Expect** each order one element naming what, reference, date and status.

---

## After the pass

- Fill the results table in [audit.md § Screen-reader pass](audit.md#screen-reader-pass-s-148) (reader + version,
  device, browser/app, journeys, date, who), add any findings, and close S-148 in [tickets.csv](tickets.csv).
- Update "Last reviewed" in both languages of the accessibility statement (audit.md).
- If the screen's tree changed on purpose since the snapshots, refresh them:
  `pnpm --filter @northline/a11y a11y -- --update-snapshots` after `make a11y`'s builds, and review the diff.
