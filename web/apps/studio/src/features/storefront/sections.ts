import type { MerchantType } from '../shell/api';

/**
 * Mirror of docs/spec/storefront-sections.json (section kinds, recommended orders, CTA labels) and the curated brand
 * swatches of design 02. `sections.test.ts` checks this file against the spec.
 */
export const SECTION_KINDS = ['hero', 'cta', 'about', 'services', 'reviews', 'area', 'gallery', 'faq', 'featured', 'catalogue', 'delivery', 'policies', 'menu', 'hours', 'fulfil', 'permit'] as const;
export type SectionKind = (typeof SECTION_KINDS)[number];
export type PageKind = 'business_page' | 'store' | 'menu_page';

export const REQUIRED_SECTIONS: readonly SectionKind[] = ['hero', 'cta'];
export const isRequired = (k: SectionKind) => REQUIRED_SECTIONS.includes(k);

/** "Reset to recommended order" (default_order / both_default_order). */
export const DEFAULT_ORDER: Record<MerchantType, readonly SectionKind[]> = {
  provider: ['hero', 'about', 'services', 'reviews', 'area', 'gallery', 'faq', 'cta'],
  seller: ['hero', 'about', 'featured', 'catalogue', 'delivery', 'reviews', 'policies', 'cta'],
  kitchen: ['hero', 'about', 'menu', 'hours', 'fulfil', 'reviews', 'permit', 'cta'],
  both: ['hero', 'about', 'services', 'featured', 'catalogue', 'reviews', 'area', 'policies', 'cta'],
};

export const PAGE_KIND: Record<MerchantType, PageKind> = { provider: 'business_page', seller: 'store', kitchen: 'menu_page', both: 'business_page' };

export const CTA_LABELS = ['book_visit', 'request_quote', 'order_now', 'reserve'] as const;
export type CtaLabel = (typeof CTA_LABELS)[number];

/** Curated swatches (design 02 `sw`). Brand colours are merchant data, not theme tokens; all pass 4.5:1 with white. */
export const SWATCHES = [
  { key: 'forest', hex: '#2f5d3a' },
  { key: 'rust', hex: '#9a4a1f' },
  { key: 'plum', hex: '#5b2a5e' },
  { key: 'ink', hex: '#15231b' },
  { key: 'cyan', hex: '#006786' },
  { key: 'slate', hex: '#3b4a5a' },
] as const;
export type SwatchKey = (typeof SWATCHES)[number]['key'];

/** WCAG 2.x contrast ratio of `#rrggbb` against white (same formula as the api's BrandColor). */
export function contrastWithWhite(hex: string): number {
  const rgb = Number.parseInt(hex.slice(1), 16);
  const ch = (v: number) => { const c = (v & 0xff) / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
  const l = 0.2126 * ch(rgb >> 16) + 0.7152 * ch(rgb >> 8) + 0.0722 * ch(rgb);
  return 1.05 / (l + 0.05);
}
export const MIN_CONTRAST = 4.5;

/** Moves one entry of `order` from index `from` to index `to`. */
export function move<T>(order: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= order.length || to >= order.length) return [...order];
  const next = [...order];
  const [it] = next.splice(from, 1);
  next.splice(to, 0, it!);
  return next;
}
