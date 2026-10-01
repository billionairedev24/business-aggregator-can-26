import tokens from '@northline/tokens/tokens.json';

import { mixOklch as mix } from './oklch';

/**
 * Northline "Spruce & Honey" for React Native, from the same five base tokens as the web (`@northline/tokens`,
 * the only place colours are defined). Ramps follow web/packages/tokens/derived.css one for one.
 */
const base = tokens.base;
const WHITE = '#FFFFFF';
const BLACK = '#000000';

export const colors = {
  accent: base.accent,
  accent2: base['accent-2'],
  highlight: base.highlight,
  bg: base.bg,
  text: base.text,

  surface: mix(base.bg, WHITE, 0.7),
  surface2: mix(base.bg, base.text, 0.05),
  divider: mix(base.text, 'transparent', 0.88),
  onAccent: WHITE,
  onHighlight: mix(base.highlight, BLACK, 0.62),
  success: mix(base.accent, '#3FA06A', 0.55),
  warning: mix(base.highlight, BLACK, 0.25),

  accent100: mix(WHITE, base.accent, 0.09),
  accent200: mix(WHITE, base.accent, 0.2),
  accent300: mix(WHITE, base.accent, 0.38),
  accent600: mix(base.accent, BLACK, 0.14),
  accent700: mix(base.accent, BLACK, 0.26),

  accent2_100: mix(WHITE, base['accent-2'], 0.09),
  accent2_200: mix(WHITE, base['accent-2'], 0.2),
  accent2_700: mix(base['accent-2'], BLACK, 0.24),

  highlight100: mix(WHITE, base.highlight, 0.18),
  highlight300: mix(WHITE, base.highlight, 0.5),

  neutral100: mix(base.bg, base.text, 0.04),
  neutral200: mix(base.bg, base.text, 0.08),
  neutral300: mix(base.bg, base.text, 0.15),
  neutral500: mix(base.bg, base.text, 0.42),
  neutral700: mix(base.bg, base.text, 0.7),
} as const;

const px = (v: string) => Number.parseFloat(v);

export const radius = {
  sm: px(tokens.radius.sm),
  md: px(tokens.radius.md),
  lg: px(tokens.radius.lg),
  pill: px(tokens.radius.pill),
} as const;

export const space = {
  1: px(tokens.space['1']),
  2: px(tokens.space['2']),
  3: px(tokens.space['3']),
  4: px(tokens.space['4']),
  6: px(tokens.space['6']),
  8: px(tokens.space['8']),
} as const;

/**
 * Newsreader headings (weight from tokens), Instrument Sans body: the font families as loaded by
 * `@expo-google-fonts/*` in the app (`useFonts`). Falls back to the system font until they load.
 */
export const fonts = {
  heading: tokens.font['heading-weight'] === '500' ? 'Newsreader_500Medium' : 'Newsreader_400Regular',
  body: 'InstrumentSans_400Regular',
  bodyStrong: 'InstrumentSans_600SemiBold',
} as const;

/** WCAG 2.2 target size (and Material's 48 dp): every control is at least this tall and wide. */
export const MIN_TARGET = 48;

export const theme = { colors, radius, space, fonts, MIN_TARGET } as const;
export type Theme = typeof theme;
export { mixOklch, toOklch, fromOklch } from './oklch';
