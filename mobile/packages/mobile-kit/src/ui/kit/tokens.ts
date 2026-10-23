import theme from '@northline/tokens/theme.json';
import { StyleSheet, type Insets } from 'react-native';

import { MIN_TARGET, fonts, semantic } from '../../theme';

/**
 * The kit's design tokens (S-169, tokens v2 · Plainsight), read from the generated theme like everything else in the
 * kit: the type roles of `scale.json` (`type`), control sizes (`size`), line heights (`leading`). Components use only
 * these, `space`, `radius` and `semantic`: no literal colour, font size or spacing (the kit's rules test checks it).
 */
export const TYPE = theme.type;
export const SIZE = theme.size;
export const LEADING = theme.leading;
export const DENSITY = theme.density;

/** Hairline widths: the 1 pt edge of controls and cards, the 2 pt underline of a selected tab, the 1.5 pt box of a checkbox. */
export const LINE = { hair: 1, bold: 2, box: 1.5 } as const;

/** React Native wants a number: line height = size × leading, rounded. */
const lh = (size: number, leading: number = LEADING.normal) => Math.round(size * leading);

/**
 * The text roles (Plainsight: IBM Plex Sans for everything, no display face): page title 24 / 700, section 18 / 700,
 * sub 16 / 600, card title 13.5 / 500, body 14, label 13 / 600, meta 12, caps 11 / 700 uppercase, prices 15 / 17 / 28
 * at 700 with tabular figures. Sizes are points and scale with Dynamic Type / the Android font size (React Native's
 * default); one-line labels in a bar cap it with {@link CAP}.
 */
export const textStyles = StyleSheet.create({
  pageTitle: { fontFamily: fonts.bold, fontSize: TYPE['page-title'], lineHeight: lh(TYPE['page-title'], LEADING.tight), color: semantic.text },
  section: { fontFamily: fonts.bold, fontSize: TYPE.section, lineHeight: lh(TYPE.section, LEADING.tight), color: semantic.text },
  sub: { fontFamily: fonts.semibold, fontSize: TYPE.sub, lineHeight: lh(TYPE.sub, LEADING.tight), color: semantic.text },
  cardTitle: { fontFamily: fonts.medium, fontSize: TYPE['card-title'], lineHeight: lh(TYPE['card-title'], LEADING.tight), color: semantic.text },
  body: { fontFamily: fonts.regular, fontSize: TYPE.body, lineHeight: lh(TYPE.body), color: semantic.text },
  bodyStrong: { fontFamily: fonts.semibold, fontSize: TYPE.body, lineHeight: lh(TYPE.body), color: semantic.text },
  label: { fontFamily: fonts.semibold, fontSize: TYPE.label, lineHeight: lh(TYPE.label, LEADING.tight), color: semantic.text },
  meta: { fontFamily: fonts.regular, fontSize: TYPE.meta, lineHeight: lh(TYPE.meta), color: semantic['text-muted'] },
  metaStrong: { fontFamily: fonts.semibold, fontSize: TYPE.meta, lineHeight: lh(TYPE.meta), color: semantic.text },
  caps: { fontFamily: fonts.bold, fontSize: TYPE.caps, lineHeight: lh(TYPE.caps, LEADING.tight), letterSpacing: TYPE.caps * 0.06, textTransform: 'uppercase', color: semantic['text-muted'] },
  priceSm: { fontFamily: fonts.bold, fontSize: TYPE['price-sm'], lineHeight: lh(TYPE['price-sm'], LEADING.tight), color: semantic.price, fontVariant: ['tabular-nums'] },
  price: { fontFamily: fonts.bold, fontSize: TYPE.price, lineHeight: lh(TYPE.price, LEADING.tight), color: semantic.price, fontVariant: ['tabular-nums'] },
  priceLg: { fontFamily: fonts.bold, fontSize: TYPE['price-lg'], lineHeight: lh(TYPE['price-lg'], LEADING.tight), color: semantic.price, fontVariant: ['tabular-nums'] },
  link: { fontFamily: fonts.semibold, color: semantic.link },
});

export type TextRole = keyof typeof textStyles;

/** Dynamic Type caps for one-line labels inside a fixed bar (tab labels, counts, tags); body text is never capped. */
export const CAP = { bar: 1.3, badge: 1.2, control: 1.6 } as const;

/** The vertical hit slop that makes a control drawn `drawn` pt tall a 44 pt target (iOS HIG; WCAG 2.5.8 asks 24). */
export function slopFor(drawn: number): Insets | undefined {
  const extra = Math.max(0, Math.ceil((MIN_TARGET - drawn) / 2));
  return extra ? { top: extra, bottom: extra, left: 0, right: 0 } : undefined;
}
