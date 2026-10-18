import theme from '@northline/tokens/theme.json';

import { mixOklch as mix } from './oklch';

/**
 * The brand for React Native (redesign 2026-10-18): the resolved theme the web's token build writes
 * (web/packages/tokens/generated/theme.json, from brand.json and its palette — the only place colours are defined), so
 * changing the palette re-colours the native apps too. `semantic` holds AfroTransact's role names (the same keys as
 * the web's `--color-*`); the older names below map onto them until the native screens are rebuilt (phase 4).
 */
const c = theme.color;

/** Semantic roles, as `--color-<key>` on the web ("action-primary", "text-muted", …). */
export const semantic = c;

export const colors = {
  // deprecated "Spruce & Honey" names, kept for the screens not yet rebuilt
  accent: c['action-primary'],
  accent2: c['danger-border'],
  highlight: c['action-accent'],
  bg: c.canvas,
  text: c.text,

  surface: c.surface,
  surface2: c['surface-muted'],
  divider: c.border,
  onAccent: c['action-primary-text'],
  onHighlight: c['action-accent-text'],
  success: c.success,
  warning: c.warning,

  accent100: c['surface-brand'],
  accent200: theme.ramp.primary['100'],
  accent300: theme.ramp.primary['300'],
  accent600: c['action-primary-hover'],
  accent700: c.link,
  accent800: c['badge-brand-text'],
  accent900: c['badge-brand-text'],

  accent2_100: c['danger-bg'],
  accent2_200: theme.ramp.danger['100'],
  accent2_700: c.danger,
  accent2_800: c.danger,

  highlight100: c['badge-accent-bg'],
  highlight300: theme.ramp.accent['300'],

  neutral100: c['surface-muted'],
  neutral200: theme.ramp.neutral['100'],
  neutral300: c['border-strong'],
  neutral400: theme.ramp.neutral['300'],
  neutral500: theme.ramp.neutral['400'],
  neutral600: c['border-control'],
  neutral700: c['text-muted'],
  neutral800: theme.ramp.neutral['800'],
  neutral900: theme.ramp.neutral['900'],

  // the semantic roles, camel-cased
  canvas: c.canvas,
  textMuted: c['text-muted'],
  textSubtle: c['text-subtle'],
  border: c.border,
  borderStrong: c['border-strong'],
  borderControl: c['border-control'],
  headerBg: c['header-bg'],
  headerText: c['header-text'],
  actionPrimary: c['action-primary'],
  actionPrimaryText: c['action-primary-text'],
  actionAccent: c['action-accent'],
  actionAccentText: c['action-accent-text'],
  link: c.link,
  focus: c.focus,
  danger: c.danger,
  dangerBg: c['danger-bg'],
  rating: c.rating,
  cartBadge: c['cart-badge'],
  overlay: mix(c.text, 'transparent', 0.45),
} as const;

/** Radii of brand.json (xs 4 · sm 6 · md 8 · lg 12 · pill). */
export const radius = { ...theme.radius } as const;

/** The 4px spacing scale (scale.json): 0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. */
const s = theme.space;
export const space = { 0: s['0'], 1: s['1'], 2: s['2'], 3: s['3'], 4: s['4'], 5: s['5'], 6: s['6'], 8: s['8'], 10: s['10'], 12: s['12'], 16: s['16'] } as const;

/** The type scale in px (scale.json): 2xs 11 … 5xl 52. */
export const text = { ...theme.text } as const;

/** React Native shadows of the scale's sm / md / lg. */
export const shadow = { sm: theme.shadow.sm.native, md: theme.shadow.md.native, lg: theme.shadow.lg.native } as const;

/**
 * Font families as loaded by `@expo-google-fonts/*` in the apps (`useFonts`). The native apps still load the previous
 * families; switching them to the palette's (theme.font.families) comes with the native reskin (redesign phase 4).
 */
export const fonts = {
  heading: 'Newsreader_500Medium',
  body: 'InstrumentSans_400Regular',
  bodyStrong: 'InstrumentSans_600SemiBold',
} as const;

/** The brand's name, short name and domain (brand.json `identity`). */
export const brand = theme.brand;

/** WCAG 2.2 target size (and Material's 48 dp): every control is at least this tall and wide. */
export const MIN_TARGET = 48;

export const nativeTheme = { colors, semantic, radius, space, text, shadow, fonts, brand, MIN_TARGET } as const;
export { nativeTheme as theme };
export type Theme = typeof nativeTheme;
export { mixOklch, toOklch, fromOklch } from './oklch';
