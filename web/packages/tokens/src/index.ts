/**
 * The brand and the resolved theme (generated/theme.json, written by build.mjs from brand.json, the chosen palette and
 * scale.json). Web components style with `var(--color-*)` from dist/tokens.css; these values are for code that needs
 * them as data: the product's name in copy (i18n `{brand}`), the Logo, page titles, the native apps' StyleSheets.
 */
import theme from '../generated/theme.json';

export type Theme = typeof theme;
export type SemanticColour = keyof Theme['color'];
export interface MarkShape { el: 'rect' | 'circle' | 'path'; fill?: string; stroke?: string; [attr: string]: string | number | undefined }

export { theme };
/** Name, short name, legal name, domain, support email and wordmark of the product (brand.json `identity`). */
export const brand = theme.brand;
/** The palette's web fonts (Google Fonts stylesheet; the CSPs allow fonts.googleapis.com / fonts.gstatic.com). */
export const fontHref = theme.font.href;
/** `var(--color-…)` for a semantic colour. */
export const cssVar = (name: SemanticColour | string) => `var(--color-${name})`;
