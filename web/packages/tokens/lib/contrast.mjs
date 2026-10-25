// The contrast gate (port of AfroTransact's vet(), design/at-theme.js): every text pair the components draw, checked
// on every palette at build time. Text 4.5:1 (WCAG 1.4.3); control outlines, focus indicators and icons 3:1 (1.4.11).
// A failing pair fails `pnpm --filter @northline/tokens build`, so an owner trying colours cannot ship an unreadable one.
import { contrast } from './oklch.mjs';

/** [foreground, background, minimum, where]. Names are semantic colour keys (`--color-<key>`). */
export const PAIRS = [
  // text
  ['text', 'canvas', 4.5, 'body text on the page'], ['text', 'surface', 4.5, 'text on cards, inputs, menus'],
  ['text', 'surface-muted', 4.5, 'text in table headers and addons'], ['text', 'surface-hover', 4.5, 'hovered rows and menu items'],
  ['text-muted', 'canvas', 4.5, 'muted text on the page'], ['text-muted', 'surface', 4.5, 'muted text on cards'],
  ['text-muted', 'surface-muted', 4.5, 'table header labels'], ['text-muted', 'surface-hover', 4.5, 'menu meta on hover'],
  ['text-subtle', 'surface', 4.5, 'meta text, placeholders'], ['text-subtle', 'canvas', 4.5, 'meta text on the page'],
  ['link', 'surface', 4.5, 'links on cards'], ['link', 'canvas', 4.5, 'links on the page'], ['link-hover', 'surface', 4.5, 'hovered links'],
  ['price', 'surface', 4.5, 'prices'], ['rating-text', 'surface', 4.5, 'rating value and count'],
  // actions
  ['action-primary-text', 'action-primary', 4.5, 'primary buttons'], ['action-primary-text', 'action-primary-hover', 4.5, 'primary buttons, hovered'],
  ['action-primary-text', 'action-primary-press', 4.5, 'primary buttons, pressed'],
  ['action-accent-text', 'action-accent', 4.5, 'accent buttons (add to cart, search)'], ['action-accent-text', 'action-accent-hover', 4.5, 'accent buttons, hovered'],
  ['action-neutral-text', 'action-neutral', 4.5, 'neutral buttons'], ['action-neutral-text', 'action-neutral-hover', 4.5, 'neutral buttons, hovered'],
  ['action-dark-text', 'action-dark', 4.5, 'dark buttons'], ['action-dark-text', 'action-dark-hover', 4.5, 'dark buttons, hovered'],
  ['action-danger-text', 'action-danger', 4.5, 'danger buttons'], ['action-danger-text', 'action-danger-hover', 4.5, 'danger buttons, hovered'],
  // chrome
  ['header-text', 'header-bg', 4.5, 'header'], ['header-muted', 'header-bg', 4.5, 'header labels ("Deliver to")'],
  ['header-text', 'header-hover-bg', 4.5, 'header items, hovered'], ['subheader-text', 'subheader-bg', 4.5, 'department bar'],
  ['subheader-text', 'subheader-hover-bg', 4.5, 'department bar, hovered'], ['footer-text', 'footer-bg', 4.5, 'footer links'], ['footer-muted', 'footer-bg', 4.5, 'footer meta'],
  ['header-text', 'subheader-hover-bg', 4.5, 'header tabs, hovered'], ['header-muted', 'header-hover-bg', 4.5, 'header labels, hovered'],
  ['footer-text', 'footer-bg', 4.5, 'footer headings and links (S-188: white on violet, never the action colour)'],
  ['inverse-text', 'inverse-bg', 4.5, 'dark panels (sign-in advertorial)'], ['inverse-muted', 'inverse-bg', 4.5, 'dark panels, muted'],
  ['inverse-text', 'inverse-hover-bg', 4.5, 'dark panels, numbered points'],
  ['text', 'surface-sunken', 4.5, 'text on linen bands'], ['text-muted', 'surface-sunken', 4.5, 'muted text on linen bands'],
  ['link', 'surface-sunken', 4.5, 'links on linen bands'], ['text-muted', 'surface-muted', 4.5, 'muted text on the muted surface'],
  ['action-accent-text', 'action-accent', 4.5, 'Add / Book buttons'],
  ['nav-active-text', 'nav-active-bg', 4.5, 'current sidebar item'], ['text', 'nav-active-bg', 4.5, 'sidebar items on the brand tint'],
  // status and badges
  ['danger', 'surface', 4.5, 'inline errors'], ['danger', 'canvas', 4.5, 'inline errors on the page'], ['danger', 'danger-bg', 4.5, 'error alerts'],
  ['success', 'success-bg', 4.5, 'success alerts and tags'], ['success', 'surface', 4.5, 'deltas, in-stock'],
  ['warning', 'warning-bg', 4.5, 'warning alerts and tags'], ['info', 'info-bg', 4.5, 'info alerts'],
  ['badge-neutral-text', 'badge-neutral-bg', 4.5, 'neutral tags'], ['badge-brand-text', 'badge-brand-bg', 4.5, 'brand tags'],
  ['badge-accent-text', 'badge-accent-bg', 4.5, 'accent tags'], ['cart-badge-text', 'cart-badge', 4.5, 'cart count'],
  ['text-inverse', 'text', 4.5, 'toasts'],
  // non-text (1.4.11)
  ['border-control', 'surface', 3, 'input, checkbox and radio outlines'], ['border-control', 'canvas', 3, 'input outlines on the page'],
  ['focus', 'surface', 3, 'focus ring'], ['focus', 'canvas', 3, 'focus ring on the page'],
  ['header-focus', 'header-bg', 3, 'focus in the header'], ['header-indicator', 'subheader-bg', 3, 'current tab underline'],
  ['header-indicator', 'header-bg', 3, 'current tab underline in the header bar'], ['header-ring', 'header-bg', 3, 'header field and item outlines'],
  ['focus-on-inverse', 'inverse-bg', 3, 'focus on dark panels'],
  ['border-card', 'surface', 3, 'card and tile edges on white'], ['border-card', 'canvas', 3, 'card edges on the page'],
  ['border-card', 'surface-sunken', 3, 'card edges on linen'], ['border-control', 'surface-sunken', 3, 'input outlines on linen'],
  ['action-primary', 'canvas', 3, 'primary buttons on the page (shape)'],
  ['focus', 'surface-sunken', 3, 'focus ring on linen'], ['nav-active-text', 'surface', 3, 'sidebar active bar'],
  ['danger-border', 'surface', 3, 'invalid input outline'], ['action-primary', 'surface', 3, 'checked checkbox, switch track'],
  ['surface', 'action-primary', 3, 'switch knob on the on track'], ['surface', 'border-control', 3, 'switch knob on the off track'],
  // tokens v2 (S-167): the Plainsight roles
  ['selected-text', 'selected', 4.5, 'selected segment, tile, page, tab count'], ['selected-ink', 'selected-tint', 4.5, 'applied filter chip'],
  ['selected', 'surface', 3, 'checked checkbox and radio, tab underline, chip edge'], ['selected', 'canvas', 3, 'selection on the page'],
  ['commerce-on', 'commerce', 4.5, 'deal badge, cart count'], ['commerce-text', 'surface', 4.5, 'cut-off timer, deal text'],
  ['commerce-text', 'surface-sunken', 4.5, 'deal text on strips'], ['commerce-text', 'commerce-bg', 4.5, 'commerce tags'],
  ['text', 'well', 4.5, 'text in a photo well'], ['text-subtle', 'well', 4.5, 'fallback caption in an empty photo well'],
  ['well-ink', 'well', 3, 'fallback icon in an empty photo well'],
  ['star', 'surface', 3, 'rating stars'], ['price', 'well', 4.5, 'prices on strips'],
  ['status-success-fg', 'status-success-bg', 4.5, 'success status tag'], ['status-warning-fg', 'status-warning-bg', 4.5, 'warning status tag'],
  ['status-danger-fg', 'status-danger-bg', 4.5, 'danger status tag'], ['status-info-fg', 'status-info-bg', 4.5, 'info status tag'],
  ['status-neutral-fg', 'status-neutral-bg', 4.5, 'neutral status tag'],
  ['info', 'surface', 4.5, 'info text'], ['warning', 'surface', 4.5, 'low stock'],
  ['action-secondary-text', 'action-secondary', 4.5, 'secondary buttons'], ['action-tertiary-text', 'surface', 4.5, 'tertiary buttons'],
  ['action-dark-text', 'action-dark', 4.5, 'bulk-action bar'], ['promo-text', 'promo-bg', 4.5, 'promo strip'],
  ['text-muted', 'selected-tint', 4.5, 'muted text in an applied chip or a selected row'],
  // S-188 brand layer: every pair of the hybrid mockup's contrast table (design/revamp/hybrid/index.html § Contrast)
  ['link', 'well', 4.5, 'action link on the well'], ['link', 'surface-muted', 4.5, 'action link on the Studio and console page'],
  ['commerce-text', 'commerce', 4.5, 'ink on deal yellow: the cut-off and deal-timer pills'], ['text', 'commerce-bg', 4.5, 'ink on the soft deal surface'],
  ['subheader-deal', 'subheader-bg', 4.5, '"Today\'s deals" in yellow on the department strip'],
  ['header-focus', 'subheader-bg', 3, 'focus on the department strip'], ['header-scope-text', 'header-scope-bg', 4.5, 'search scope on its tint'],
  ['header-muted', 'subheader-bg', 4.5, 'header meta over the strip'],
  ['hero-accent', 'hero-bg', 3, 'hero accent ("tonight.", icons) on violet'], ['hero-accent', 'hero-glow-peak', 3, 'hero accent at the brightest glow'],
  ['hero-text', 'hero-bg', 4.5, 'hero trust line on violet'], ['hero-text', 'hero-glow-peak', 4.5, 'hero trust line at the brightest glow'],
  ['header-text', 'hero-bg', 4.5, 'hero headline and run time'], ['header-text', 'hero-glow-peak', 4.5, 'hero headline at the brightest glow'],
  ['header-muted', 'hero-bg', 4.5, 'run card caption'],
  ['hero-tile-text', 'hero-scrim', 4.5, 'department tile title on the photo scrim'], ['hero-tile-meta', 'hero-scrim', 4.5, 'department tile meta on the scrim'],
  ['mark-text', 'mark', 3, 'the mark\'s glyph on its disc'],
  ['nav-active-marker', 'nav-active-bg', 3, 'Studio and console current-page marker and icon'], ['text', 'nav-active-bg', 4.5, 'current page label'],
  ['nav-active-marker', 'surface', 3, 'current-page marker on white'],
  ['topbar-text', 'topbar-bg', 4.5, 'back-office top bar'], ['topbar-muted', 'topbar-bg', 4.5, 'top bar meta'],
  ['topbar-text', 'topbar-hover-bg', 4.5, 'top bar items, hovered'], ['topbar-focus', 'topbar-bg', 3, 'focus in the top bar'],
  ['topbar-ring', 'topbar-bg', 3, 'top bar outlines'], ['focus-on-dark', 'header-bg', 3, 'focus on the violet chrome'],
  ['sticker-edge', 'surface', 3, 'the sticker\'s hard edge on white and pale wells'], ['sticker-edge', 'well', 3, 'the sticker\'s edge on the well'],
];

/**
 * Fill-only roles (S-188): yellow is a fill, never text on white (#FFD23F on white is 1.44:1). They may be the
 * background of a pair, or text on the violet chrome (`subheader-deal` is its own role for that), never the text of a
 * pair on a light surface. `vetRoles` fails a palette or a PAIRS edit that would use one as text; the web lint
 * (scripts/check-legacy-tokens.mjs) refuses `color: var(--color-commerce)` and the like in CSS.
 */
export const FILL_ONLY = ['commerce', 'cart-badge', 'mark-highlight', 'subheader-deal', 'hero-glow'];
/** Pairs the mockup rejects: they must stay below their minimum, so nothing can be tuned into using them. */
export const REJECTED = [
  ['commerce', 'surface', 4.5, 'yellow text on white'], ['action-primary', 'header-bg', 4.5, 'violet text on the violet chrome'],
];
const LIGHT = ['surface', 'canvas', 'surface-muted', 'surface-sunken', 'well', 'commerce-bg', 'selected-tint', 'nav-active-bg', 'promo-bg'];
export function vetRoles(colours, pairs = PAIRS) {
  const failures = [];
  for (const [fg, bg, min, where] of pairs) {
    if (FILL_ONLY.includes(fg) && LIGHT.includes(bg)) failures.push({ fg, bg, min, where, ratio: NaN, reason: `${fg} is a fill, never text on a light surface` });
  }
  for (const [fg, bg, min, where] of REJECTED) {
    const ratio = Math.round(contrast(colours[fg], colours[bg]) * 100) / 100;
    if (ratio >= min) failures.push({ fg, bg, min, where, ratio, reason: `a rejected pair now passes (${ratio}:1): make it a role of its own instead of reusing ${fg}` });
  }
  return failures;
}

/** Every pair below its minimum, for one resolved theme. */
export function vet(colours, pairs = PAIRS) {
  const failures = [];
  for (const [fg, bg, min, where] of pairs) {
    const a = colours[fg], b = colours[bg];
    if (!a || !b) { failures.push({ fg, bg, min, where, ratio: NaN, reason: `unknown colour ${!a ? fg : bg}` }); continue; }
    const ratio = Math.round(contrast(a, b) * 100) / 100;
    if (ratio < min) failures.push({ fg, bg, min, where, ratio, a, b });
  }
  return failures;
}
