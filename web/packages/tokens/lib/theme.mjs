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

/**
 * The npm package that ships a family's font files, self-hosted (no Google Fonts: nothing is fetched from a third
 * party, and the CSPs allow fonts from 'self' only). build.mjs copies the latin + latin-ext woff2 files of the palette's
 * weights into dist/fonts/ and writes their @font-face rules into tokens.css.
 */
export const fontPackage = family => `@fontsource/${family.toLowerCase().replace(/ /g, '-')}`;

/** Semantic colours (layer 2) from a palette's brand colours. Keys become `--color-<key>`. */
export function semanticColours(p, { chrome = 'light' } = {}) {
  const c = { ...FUNCTIONAL, ...p.color };
  const primary = ramp(c.primary), accent = ramp(c.accent), neutral = neutralRamp(c.ink);
  const danger = ramp(c.danger), success = ramp(c.success), warning = ramp(c.warning);
  const surfaces = [c.surface, c.ground];
  // the brand's dark colour: advertorial panels, toasts, the dark button (and the header when chrome is "dark")
  const inverseBg = c.headerBg ?? c.primaryHover ?? primary[800];
  const light = chrome !== 'dark';
  const headerBg = light ? c.surface : inverseBg;
  const primaryHover = c.primaryHover ?? primary[700];
  const accentHover = c.accentHover ?? mix(c.accent, BLACK, 0.1);
  const accentTint = c.accentTint ?? accent[50];
  const subheaderBg = light ? c.surface : c.subheaderBg ?? c.primary;
  // the accent, lightened where it would not show on the dark colour (focus and indicators on dark panels)
  const onDark = c.headerIndicator ?? readableOnAll(c.accent, [inverseBg, c.subheaderBg ?? c.primary], 3, WHITE);
  const footerBg = light ? mix(c.ground, c.ink, 0.035) : headerBg;
  const status = (seed, r) => {
    const bg = r[50];
    return { fg: readableOnAll(seed, [bg, ...surfaces], 4.5), bg };
  };
  const ok = status(c.success, success), warn = status(c.warning, warning), bad = status(c.danger, danger);
  const colours = {
    // surfaces & text
    // light: white pages, linen for sunken areas (image wells, bands, the footer); dark: the palette's ground
    canvas: light ? c.surface : c.ground, surface: c.surface, 'surface-muted': c.surfaceMuted ?? mix(c.ground, c.ink, 0.025), 'surface-sunken': c.ground,
    'surface-hover': mix(c.ground, c.ink, 0.04), 'surface-brand': primary[50], 'surface-accent': accentTint,
    text: c.ink, 'text-muted': c.textMuted, 'text-subtle': c.textSubtle ?? c.textMuted, 'text-inverse': WHITE,
    border: c.border, 'border-strong': c.borderStrong,
    // WCAG 1.4.11: the outline of an input, checkbox or radio needs 3:1 — the palette's hairline borders don't have it
    'border-control': readableOnAll(c.borderStrong, surfaces, 3, c.ink),
    // chrome. Light (brand.json "chrome", the owner's 2026-10-19 direction): a white header with ink text, the current
    // tab underlined in the primary colour, a linen footer. Dark (AfroTransact): the brand-dark header and footer.
    ...(light ? {
      'header-bg': headerBg, 'header-text': c.ink, 'header-muted': c.textMuted, 'header-hover-bg': mix(c.ground, c.ink, 0.04),
      'header-ring': readableOnAll(c.borderStrong, [headerBg], 3, c.ink), 'header-indicator': c.primary, 'header-focus': c.focus ?? c.primary,
      'header-border': c.border,
      'subheader-bg': subheaderBg, 'subheader-text': c.ink, 'subheader-hover-bg': mix(c.ground, c.ink, 0.04),
      'footer-bg': footerBg, 'footer-text': c.ink, 'footer-muted': readable(c.textMuted, footerBg, 4.5), 'footer-border': c.borderStrong,
    } : {
      'header-bg': headerBg, 'header-text': WHITE, 'header-muted': readable(mix(WHITE, headerBg, 0.3), headerBg, 4.5, WHITE),
      'header-hover-bg': mix(headerBg, WHITE, 0.12), 'header-ring': WHITE, 'header-indicator': onDark, 'header-focus': onDark,
      'header-border': headerBg,
      'subheader-bg': subheaderBg, 'subheader-text': WHITE, 'subheader-hover-bg': mix(subheaderBg, WHITE, 0.14),
      'footer-bg': footerBg, 'footer-text': WHITE, 'footer-muted': readable(mix(WHITE, headerBg, 0.25), headerBg, 4.5, WHITE),
      'footer-border': mix(headerBg, WHITE, 0.12),
    }),
    // the brand-dark panel (sign-in advertorials, the boot screen of the dark chrome, toasts)
    'inverse-bg': inverseBg, 'inverse-text': WHITE, 'inverse-muted': readable(mix(WHITE, inverseBg, 0.3), inverseBg, 4.5, WHITE),
    'inverse-hover-bg': mix(inverseBg, WHITE, 0.12), 'inverse-ring': mix(inverseBg, WHITE, 0.3), 'focus-on-inverse': onDark,
    // actions
    'action-primary': c.primary, 'action-primary-hover': primaryHover, 'action-primary-press': mix(primaryHover, BLACK, 0.12), 'action-primary-text': c.primaryText,
    'action-accent': c.accent, 'action-accent-hover': accentHover, 'action-accent-text': c.accentText,
    'action-neutral': c.surface, 'action-neutral-hover': mix(c.ground, c.ink, 0.04), 'action-neutral-text': c.ink, 'action-neutral-border': c.borderStrong,
    'action-dark': inverseBg, 'action-dark-hover': mix(inverseBg, WHITE, 0.12), 'action-dark-text': WHITE,
    'action-danger': bad.fg, 'action-danger-hover': mix(bad.fg, BLACK, 0.15), 'action-danger-text': WHITE,
    link: c.link ?? c.primary, 'link-hover': c.linkHover ?? primaryHover,
    focus: c.focus ?? c.primary, 'focus-ring': primary[200], 'focus-on-dark': onDark,
    // WCAG 1.4.11: the edge of a card, tile or panel the person acts on reads at 3:1 on the page and on linen
    'border-card': readableOnAll(c.borderStrong, [c.surface, c.ground, footerBg], 3, c.ink),
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
  // ── tokens v2 (S-167, E-15): the roles the Plainsight components draw with, derived for every palette so the older
  // palettes keep building; a palette's `roles` then pins any of them (and any role above) to an exact value ──
  const tint = mix(c.ground, c.ink, 0.04);
  Object.assign(colours, {
    // selected: segments, chips, tabs, checkboxes, the current page turn solid ink; the tint is a chip's applied state
    selected: c.ink, 'selected-text': WHITE, 'selected-tint': tint, 'selected-ink': c.ink,
    // commerce: deals, the cart count, the cut-off timer — never a button
    commerce: c.accent, 'commerce-on': c.accentText, 'commerce-text': readableOnAll(c.accentInk ?? c.accent, surfaces, 4.5), 'commerce-bg': accentTint,
    // the photo well: product on white sits in a grey well; the fallback is a dashed well with the category icon
    well: c.ground, 'well-ink': readable(c.textSubtle ?? c.textMuted, c.ground, 3), 'well-line': c.borderStrong,
    star: readableOnAll(c.accent, surfaces, 3), 'star-empty': c.borderStrong,
    info: c.info ?? c.primary, 'info-bg': c.infoBg ?? primary[50],
    'neutral-fg': readable(c.textMuted, neutral[100], 4.5), 'neutral-bg': neutral[100],
    'promo-bg': primary[50], 'promo-text': readable(primaryHover, primary[50], 4.5),
  });
  Object.assign(colours, p.roles ?? {});
  // status tags read one pattern, `--color-status-<tone>-{fg,bg}` (aliases of the roles above, after the pins)
  for (const [tone, fg, bg] of [['success', 'success', 'success-bg'], ['warning', 'warning', 'warning-bg'], ['danger', 'danger', 'danger-bg'], ['info', 'info', 'info-bg'], ['neutral', 'neutral-fg', 'neutral-bg']]) {
    colours[`status-${tone}-fg`] = colours[fg];
    colours[`status-${tone}-bg`] = colours[bg];
  }
  // the secondary and tertiary buttons are the neutral and the link roles, named as the components use them
  Object.assign(colours, {
    'action-secondary': colours['action-neutral'], 'action-secondary-hover': colours['action-neutral-hover'],
    'action-secondary-text': colours['action-neutral-text'], 'action-secondary-border': colours['action-neutral-border'],
    'action-tertiary-text': colours.link, 'action-tertiary-hover': colours['link-hover'],
  });
  return { colours, ramps: { primary, accent, neutral, danger, success, warning } };
}

/** Everything one palette resolves to — what generated/theme.json holds and the native apps read. */
export function resolveTheme(brand, palette, scale) {
  const { colours, ramps } = semanticColours(palette, { chrome: brand.chrome });
  const ink = palette.color.ink;
  // a palette may flatten or soften the shadows (Plainsight: flat cards, a shadow only for what floats)
  const shadow = Object.fromEntries(Object.entries({ ...scale.shadow, ...palette.shadow }).map(([k, layers]) => [k, {
    css: layers.map(l => `0 ${l.y}px ${l.blur}px rgb(${channels(ink)} / ${l.alpha})`).join(', '),
    native: { shadowColor: ink, shadowOffset: { width: 0, height: layers[0].y }, shadowOpacity: layers[0].alpha, shadowRadius: layers[0].blur / 2, elevation: Math.max(1, Math.round(layers[0].y * 1.5)) },
  }]));
  const sans = stack(palette.font.sans, SANS_FALLBACK);
  return {
    brand: { ...brand.identity, palette: brand.palette, paletteName: palette.name, direction: palette.direction, chrome: brand.chrome ?? 'light' },
    font: {
      sans, display: palette.font.display ? stack(palette.font.display, SERIF_FALLBACK) : sans,
      heading: palette.font.display ? stack(palette.font.display, SERIF_FALLBACK) : sans,
      families: [palette.font.sans, palette.font.display].filter(Boolean).map(f => ({ ...f, package: fontPackage(f.family) })),
    },
    color: colours,
    ramp: ramps,
    // a palette's own corners replace brand.json's (Plainsight: 3 px controls, 4 px panels); control/panel/image/status/round
    // are the v2 names, derived from the older steps for palettes that predate them
    radius: { control: brand.radius.sm, panel: brand.radius.lg, image: brand.radius.xs, status: brand.radius.xs, round: brand.radius.pill, ...brand.radius, ...palette.radius },
    space: scale.space, text: scale.text, type: scale.type, density: scale.density, weight: scale.weight, leading: scale.leading, tracking: scale.tracking,
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
