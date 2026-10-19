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
  ['footer-text', 'footer-bg', 4.5, 'footer headings'], ['link', 'footer-bg', 4.5, 'links in the footer'],
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
];

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
