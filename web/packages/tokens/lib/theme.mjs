// Resolves brand.json + its palette + scale.json into the theme every app reads: AfroTransact's three token layers
// (docs/design/redesign-spec.md § 1) with our brand in them — 1. primitive ramps, 2. the semantic roles components
// use, 3. the merchant store theme (CSS only, build.mjs). Components only ever see layer 2 and 3.
import { channels, contrast, mix, readable } from './oklch.mjs';

const WHITE = '#FFFFFF', BLACK = '#000000';

/** Not brand: functional colours every palette shares unless it overrides them. */
const FUNCTIONAL = { danger: '#B42318', success: '#067647', warning: '#B54708' };

const RAMP_STEPS = { 50: [WHITE, 0.92], 100: [WHITE, 0.84], 200: [WHITE, 0.68], 300: [WHITE, 0.5], 400: [WHITE, 0.25], 500: [null, 0], 600: [BLACK, 0.12], 700: [BLACK, 0.25], 800: [BLACK, 0.4], 900: [BLACK, 0.55] };
const ramp = seed => Object.fromEntries(Object.entries(RAMP_STEPS).map(([k, [toward, w]]) => [k, toward ? mix(seed, toward, w) : seed.toUpperCase()]));
/** Greys of the brand's ink (warm or cool, as the ink is): 0 = white … 1000 = the ink. */
const NEUTRAL_STEPS = { 0: 0, 25: 0.015, 50: 0.03, 100: 0.06, 200: 0.12, 300: 0.22, 400: 0.38, 500: 0.52, 600: 0.64, 700: 0.76, 800: 0.86, 900: 0.94, 1000: 1 };
const neutralRamp = ink => Object.fromEntries(Object.entries(NEUTRAL_STEPS).map(([k, w]) => [k, mix(WHITE, ink, w)]));

/** The first of `fg` darkened towards `toward` that reaches `min` against every one of `bgs`. */
const readableOnAll = (fg, bgs, min, toward = BLACK) => {
  let c = fg;
  for (const bg of bgs) c = readable(c, bg, min, toward);
  return bgs.every(bg => contrast(c, bg) >= min) ? c : readable(fg, bgs[0], 21, toward);
};

const stack = (font, fallback) => `"${font.family}", ${font.fallback ?? fallback}`;
const SANS_FALLBACK = 'ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
const SERIF_FALLBACK = 'Georgia, "Times New Roman", serif';

/** The Google Fonts stylesheet for the palette's families (CSP: fonts.googleapis.com / fonts.gstatic.com). */
export function fontHref(font) {
  const fam = f => `family=${f.family.replace(/ /g, '+')}:wght@${[...f.weights].sort((a, b) => a - b).join(';')}`;
  return `https://fonts.googleapis.com/css2?${[font.sans, font.display].filter(Boolean).map(fam).join('&')}&display=swap`;
}

/** Semantic colours (layer 2) from a palette's brand colours. Keys become `--color-<key>`. */
export function semanticColours(p) {
  const c = { ...FUNCTIONAL, ...p.color };
  const primary = ramp(c.primary), accent = ramp(c.accent), neutral = neutralRamp(c.ink);
  const danger = ramp(c.danger), success = ramp(c.success), warning = ramp(c.warning);
  const surfaces = [c.surface, c.ground];
  const headerBg = c.headerBg ?? c.primaryHover ?? primary[800];
  const primaryHover = c.primaryHover ?? primary[700];
  const accentHover = c.accentHover ?? mix(c.accent, BLACK, 0.1);
  const accentTint = c.accentTint ?? accent[50];
  const subheaderBg = c.subheaderBg ?? c.primary;
  // the gold underline of AfroTransact's header: the accent, lightened where it would not show on the dark chrome
  const onDark = c.headerIndicator ?? readableOnAll(c.accent, [headerBg, subheaderBg], 3, WHITE);
  const status = (seed, r) => {
    const bg = r[50];
    return { fg: readableOnAll(seed, [bg, ...surfaces], 4.5), bg };
  };
  const ok = status(c.success, success), warn = status(c.warning, warning), bad = status(c.danger, danger);
  const colours = {
    // surfaces & text
    canvas: c.ground, surface: c.surface, 'surface-muted': c.surfaceMuted ?? mix(c.ground, c.ink, 0.025), 'surface-sunken': c.ground,
    'surface-hover': mix(c.ground, c.ink, 0.04), 'surface-brand': primary[50], 'surface-accent': accentTint,
    text: c.ink, 'text-muted': c.textMuted, 'text-subtle': c.textSubtle ?? c.textMuted, 'text-inverse': WHITE,
    border: c.border, 'border-strong': c.borderStrong,
    // WCAG 1.4.11: the outline of an input, checkbox or radio needs 3:1 — the palette's hairline borders don't have it
    'border-control': readableOnAll(c.borderStrong, surfaces, 3, c.ink),
    // chrome (AfroTransact: a dark header, a slightly lighter department bar, the footer in the header's colour)
    'header-bg': headerBg, 'header-text': WHITE, 'header-muted': readable(mix(WHITE, headerBg, 0.3), headerBg, 4.5, WHITE),
    'header-hover-bg': mix(headerBg, WHITE, 0.12), 'header-ring': WHITE, 'header-indicator': onDark,
    'subheader-bg': subheaderBg, 'subheader-text': WHITE, 'subheader-hover-bg': mix(subheaderBg, WHITE, 0.14),
    'footer-bg': headerBg, 'footer-text': WHITE, 'footer-muted': readable(mix(WHITE, headerBg, 0.25), headerBg, 4.5, WHITE),
    // actions
    'action-primary': c.primary, 'action-primary-hover': primaryHover, 'action-primary-press': mix(primaryHover, BLACK, 0.12), 'action-primary-text': c.primaryText,
    'action-accent': c.accent, 'action-accent-hover': accentHover, 'action-accent-text': c.accentText,
    'action-neutral': c.surface, 'action-neutral-hover': mix(c.ground, c.ink, 0.04), 'action-neutral-text': c.ink, 'action-neutral-border': c.borderStrong,
    'action-dark': headerBg, 'action-dark-hover': mix(headerBg, WHITE, 0.12), 'action-dark-text': WHITE,
    'action-danger': bad.fg, 'action-danger-hover': mix(bad.fg, BLACK, 0.15), 'action-danger-text': WHITE,
    link: c.link ?? c.primary, 'link-hover': c.linkHover ?? primaryHover,
    focus: c.focus ?? c.primary, 'focus-ring': primary[200], 'focus-on-dark': onDark,
    // commerce & status
    price: c.ink, rating: c.accent, 'rating-text': readableOnAll(c.accentInk ?? c.accent, surfaces, 4.5),
    success: ok.fg, 'success-bg': ok.bg, warning: warn.fg, 'warning-bg': warn.bg, danger: bad.fg, 'danger-bg': bad.bg, 'danger-border': c.danger,
    info: c.primary, 'info-bg': primary[50],
    'badge-neutral-bg': neutral[100], 'badge-neutral-text': readable(c.textMuted, neutral[100], 4.5),
    'badge-brand-bg': primary[50], 'badge-brand-text': readable(primaryHover, primary[50], 4.5),
    'badge-accent-bg': accentTint, 'badge-accent-text': readable(c.accentInk ?? accent[800], accentTint, 4.5),
    'cart-badge': bad.fg, 'cart-badge-text': WHITE,
    'nav-active-bg': primary[50], 'nav-active-text': readable(primaryHover, primary[50], 4.5),
    selection: accent[200], 'mark-highlight': c.markHighlight ?? c.accent,
    'placeholder-a': neutral[100], 'placeholder-b': neutral[50],
  };
  return { colours, ramps: { primary, accent, neutral, danger, success, warning } };
}

/** Everything one palette resolves to — what generated/theme.json holds and the native apps read. */
export function resolveTheme(brand, palette, scale) {
  const { colours, ramps } = semanticColours(palette);
  const ink = palette.color.ink;
  const shadow = Object.fromEntries(Object.entries(scale.shadow).map(([k, layers]) => [k, {
    css: layers.map(l => `0 ${l.y}px ${l.blur}px rgb(${channels(ink)} / ${l.alpha})`).join(', '),
    native: { shadowColor: ink, shadowOffset: { width: 0, height: layers[0].y }, shadowOpacity: layers[0].alpha, shadowRadius: layers[0].blur / 2, elevation: Math.max(1, Math.round(layers[0].y * 1.5)) },
  }]));
  const sans = stack(palette.font.sans, SANS_FALLBACK);
  return {
    brand: { ...brand.identity, palette: brand.palette, paletteName: palette.name, direction: palette.direction },
    font: {
      sans, display: palette.font.display ? stack(palette.font.display, SERIF_FALLBACK) : sans,
      heading: palette.font.display ? stack(palette.font.display, SERIF_FALLBACK) : sans,
      families: [palette.font.sans, palette.font.display].filter(Boolean),
      href: fontHref(palette.font),
    },
    color: colours,
    ramp: ramps,
    radius: brand.radius,
    space: scale.space, text: scale.text, weight: scale.weight, leading: scale.leading, tracking: scale.tracking,
    size: scale.size, page: scale.page, z: scale.z, motion: scale.motion,
    shadow,
    logo: { wordmark: brand.identity.wordmark ?? brand.identity.name, mark: palette.mark, markOnDark: palette.markOnDark ?? palette.mark },
  };
}

/** The mark as a standalone SVG (favicon), its colour references resolved. */
export function markSvg(theme, size = 32) {
  const { viewBox, shapes } = theme.logo.mark;
  const attr = (k, v) => `${k.replace(/[A-Z]/g, m => `-${m.toLowerCase()}`)}="${['fill', 'stroke'].includes(k) && v !== 'none' ? theme.color[v] : v}"`;
  const body = shapes.map(({ el, ...a }) => `<${el} ${Object.entries(a).map(([k, v]) => attr(k, v)).join(' ')}/>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${viewBox}">${body}</svg>\n`;
}
