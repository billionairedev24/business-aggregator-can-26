import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CTA_LABELS, DEFAULT_ORDER, REQUIRED_SECTIONS, SECTION_KINDS, SWATCHES, contrastWithWhite, move } from './sections';

const spec = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../docs/spec/storefront-sections.json'), 'utf8')) as {
  cta_labels: string[];
  page_kinds: Record<string, { default_order: string[]; both_default_order?: string[] }>;
  sections: Record<string, { required?: boolean }>;
};

describe('sections.ts mirrors docs/spec/storefront-sections.json', () => {
  it('has every section kind, the required ones and the CTA labels', () => {
    expect([...SECTION_KINDS].sort()).toEqual(Object.keys(spec.sections).sort());
    expect([...REQUIRED_SECTIONS].sort()).toEqual(Object.entries(spec.sections).filter(([, s]) => s.required).map(([k]) => k).sort());
    expect([...CTA_LABELS]).toEqual(spec.cta_labels);
  });

  it('uses the recommended orders ("Reset to recommended order")', () => {
    expect(DEFAULT_ORDER.provider).toEqual(spec.page_kinds.business_page!.default_order);
    expect(DEFAULT_ORDER.both).toEqual(spec.page_kinds.business_page!.both_default_order);
    expect(DEFAULT_ORDER.seller).toEqual(spec.page_kinds.store!.default_order);
    expect(DEFAULT_ORDER.kitchen).toEqual(spec.page_kinds.menu_page!.default_order);
  });
});

describe('brand swatches', () => {
  it('all pass 4.5:1 with white (validation-rules.md › Storefront)', () => {
    for (const s of SWATCHES) expect(contrastWithWhite(s.hex)).toBeGreaterThanOrEqual(4.5);
    expect(contrastWithWhite('#2f5d3a').toFixed(1)).toBe('7.6');
    expect(contrastWithWhite('#d9a441')).toBeLessThan(4.5);
  });
});

describe('move', () => {
  it('moves one entry and leaves the input untouched', () => {
    const order = ['hero', 'about', 'services', 'cta'];
    expect(move(order, 1, 2)).toEqual(['hero', 'services', 'about', 'cta']);
    expect(move(order, 3, 0)).toEqual(['cta', 'hero', 'about', 'services']);
    expect(move(order, 0, 9)).toEqual(order);
    expect(order).toEqual(['hero', 'about', 'services', 'cta']);
  });
});
