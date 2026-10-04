import { describe, expect, it } from 'vitest';
import { buildFieldSpecs, buildSchema, convertValues, initialValues } from './internal/RecordForm';
import type { DataTableT } from './messages';
import {
  actionApplies,
  actionAllowed,
  buildToneMap,
  cellText,
  compareValues,
  computeLayout,
  facetCounts,
  facetOptions,
  matchesSearch,
  nextSorting,
  normalizeColumns,
  parseNumeric,
  passesFacet,
  passesRange,
  pluralize,
  resolvePermissions,
  resolveTone,
  sortRows,
  tableWidthFor,
} from './model';
import type { DataTableColumn } from './types';

interface Product { id: string; name: string; sku: string; type: string; price: number; stock: number | null; live: string; vet: string }
const products: Product[] = [
  { id: 'a', name: 'Brake inspection', sku: 'SVC-BI', type: 'Service', price: 8900, stock: null, live: 'Live', vet: 'Approved' },
  { id: 'b', name: 'Wiper blades · 22"', sku: 'WB-22', type: 'Product', price: 1900, stock: 22, live: 'Hidden', vet: 'Pending · 2 min' },
  { id: 'c', name: 'Cabin air filter', sku: 'CAF-01', type: 'Product', price: 2400, stock: 0, live: 'Draft', vet: 'Draft' },
  { id: 'd', name: 'Synthetic oil 5W-30 · 5 L', sku: 'OIL-5W30', type: 'Product', price: 4200, stock: 3, live: 'Live', vet: 'Approved' },
];
const columns: DataTableColumn<Product>[] = [
  { key: 'name', label: 'Listing', sub: 'sku', subLabel: 'SKU' },
  { key: 'type', label: 'Type', type: 'tag', options: ['Service', 'Product'] },
  { key: 'price', label: 'Price', type: 'money' },
  { key: 'stock', label: 'Stock', type: 'num' },
  { key: 'vet', label: 'Vetting', type: 'tag', editable: false },
  { key: 'live', label: 'Status', type: 'tag', options: ['Live', 'Hidden', 'Draft'], tones: { Live: 'tag-accent' } },
];
const cols = normalizeColumns(columns);
const col = (k: string) => cols.find((c) => c.key === k)!;

describe('normalizeColumns', () => {
  it('fills defaults from the design', () => {
    expect(col('name')).toMatchObject({ type: 'text', primary: true, filter: false, editable: true, isNum: false, priority: 100 });
    expect(col('type')).toMatchObject({ filter: 'facet', primary: false, priority: 99 });
    expect(col('price')).toMatchObject({ filter: 'range', isNum: true });
    expect(col('vet').editable).toBe(false);
  });
  it('honours an explicit primary column', () => {
    const n = normalizeColumns<Product>([{ key: 'sku', label: 'SKU' }, { key: 'name', label: 'Name', primary: true }]);
    expect(n.map((c) => c.primary)).toEqual([false, true]);
  });
});

describe('parseNumeric', () => {
  it.each([
    [42, 42],
    ['$1,912.40', 1912.4],
    ['−3', -3],
    ['-3.5', -3.5],
    ['14 min', 14],
    ['$0.00', 0],
  ])('%s → %s', (v, n) => expect(parseNumeric(v)).toBe(n));
  it.each([[null], [undefined], [''], ['Released'], [true]])('%s → NaN', (v) => expect(parseNumeric(v)).toBeNaN());
});

describe('compareValues (sorting comparator)', () => {
  it('compares numbers and money strings numerically', () => {
    expect(compareValues('$1,912.40', '$220.15')).toBeGreaterThan(0);
    expect(compareValues(9, 10)).toBeLessThan(0);
    expect(compareValues('14', '102')).toBeLessThan(0);
  });
  it('compares text naturally and case-insensitively', () => {
    expect(compareValues('Item 2', 'Item 10')).toBeLessThan(0);
    expect(compareValues('apple', 'Banana')).toBeLessThan(0);
    expect(compareValues('NL-48201', 'NL-48188')).toBeGreaterThan(0);
  });
  it('treats blanks as empty text (sorted first ascending)', () => {
    expect(compareValues(null, 'a')).toBeLessThan(0);
    expect(compareValues(undefined, null)).toBe(0);
  });
});

describe('nextSorting', () => {
  it('cycles asc → desc → off on plain click', () => {
    let s = nextSorting([], 'price', false);
    expect(s).toEqual([{ id: 'price', desc: false }]);
    s = nextSorting(s, 'price', false);
    expect(s).toEqual([{ id: 'price', desc: true }]);
    expect(nextSorting(s, 'price', false)).toEqual([]);
  });
  it('shift-click adds a secondary sort and cycles it independently', () => {
    let s = nextSorting([], 'type', false);
    s = nextSorting(s, 'price', true);
    expect(s).toEqual([{ id: 'type', desc: false }, { id: 'price', desc: false }]);
    s = nextSorting(s, 'price', true);
    expect(s).toEqual([{ id: 'type', desc: false }, { id: 'price', desc: true }]);
    expect(nextSorting(s, 'price', true)).toEqual([{ id: 'type', desc: false }]);
  });
  it('a plain click on a multi-sorted column collapses to that column', () => {
    const s = [{ id: 'type', desc: false }, { id: 'price', desc: false }];
    expect(nextSorting(s, 'price', false)).toEqual([{ id: 'price', desc: true }]);
    expect(nextSorting(s, 'stock', false)).toEqual([{ id: 'stock', desc: false }]);
  });
  it('sortRows applies sorts in rank order', () => {
    const out = sortRows(products, [{ id: 'type', desc: false }, { id: 'price', desc: true }]);
    expect(out.map((p) => p.id)).toEqual(['d', 'c', 'b', 'a']);
  });
});

describe('filter logic', () => {
  it('global search matches display text, raw value and sub field', () => {
    expect(matchesSearch(products[0]!, cols, 'svc-bi', 'en')).toBe(true); // sub (SKU)
    expect(matchesSearch(products[0]!, cols, 'brake', 'en')).toBe(true);
    expect(matchesSearch(products[0]!, cols, '$89.00', 'en')).toBe(true); // formatted money
    expect(matchesSearch(products[0]!, cols, 'nothing', 'en')).toBe(false);
    expect(matchesSearch(products[0]!, cols, '   ', 'en')).toBe(true);
  });
  it('facets: empty selection passes; otherwise value must be selected; blanks bucket as —', () => {
    expect(passesFacet(products[1]!, 'type', [])).toBe(true);
    expect(passesFacet(products[1]!, 'type', ['Product'])).toBe(true);
    expect(passesFacet(products[0]!, 'type', ['Product'])).toBe(false);
    expect(passesFacet(products[0]!, 'stock', ['—'])).toBe(true);
  });
  it('ranges compare money in dollars and parse money strings; bounds are inclusive', () => {
    expect(passesRange(products[0]!, col('price'), { min: '89' })).toBe(true);
    expect(passesRange(products[0]!, col('price'), { min: '89.01' })).toBe(false);
    expect(passesRange(products[1]!, col('price'), { min: '10', max: '20' })).toBe(true);
    expect(passesRange({ amt: '$1,912.40' }, { key: 'amt', type: 'num' }, { max: '2000' })).toBe(true);
    expect(passesRange(products[0]!, col('stock'), { min: '0' })).toBe(false); // blank never satisfies a bound
    expect(passesRange(products[0]!, col('stock'), { min: '', max: ' ' })).toBe(true);
  });
  it('facet options follow declared order, then data order; counts per bucket', () => {
    expect(facetOptions(col('live'), products)).toEqual(['Live', 'Hidden', 'Draft']);
    expect(facetOptions(col('vet'), products)).toEqual(['Approved', 'Pending · 2 min', 'Draft']);
    expect(Object.fromEntries(facetCounts('type', products))).toEqual({ Service: 1, Product: 3 });
  });
});

describe('cells, tones, actions', () => {
  it('formats money (cents), numbers and blanks by locale', () => {
    expect(cellText(col('price'), products[0]!, 'en')).toBe('$89.00');
    expect(cellText(col('price'), products[0]!, 'fr')).toMatch(/^89,00\s\$$/);
    expect(cellText(col('stock'), products[0]!, 'en')).toBe('—');
    expect(cellText(col('stock'), { ...products[1]!, stock: 1200 }, 'en')).toBe('1,200');
  });
  it('resolves tones: column tones, then per-row map, then shared value map, then neutral', () => {
    const rowTones = (r: Product) => (r.vet === 'Approved' ? { vet: 'tag-accent' as const } : undefined);
    const map = buildToneMap(products, cols, rowTones);
    expect(resolveTone(col('live'), products[0]!, map, rowTones)).toBe('tag-accent');
    expect(resolveTone(col('live'), products[1]!, map, rowTones)).toBe('tag-neutral');
    expect(resolveTone(col('vet'), products[0]!, map, rowTones)).toBe('tag-accent');
    expect(resolveTone(col('vet'), { ...products[1]!, vet: 'Approved' }, map)).toBe('tag-accent'); // via value map
  });
  it('action guards and permissions', () => {
    const publish = { when: { key: 'live' as const, in: ['Hidden'] }, perm: 'update' as const };
    expect(actionApplies(publish, products[1]!)).toBe(true);
    expect(actionApplies(publish, products[0]!)).toBe(false);
    expect(actionApplies({}, products[0]!)).toBe(true);
    expect(actionAllowed(publish, resolvePermissions({ update: false }))).toBe(false);
    expect(actionAllowed(publish, resolvePermissions({ update: true }))).toBe(true);
    expect(resolvePermissions(undefined)).toEqual({ create: false, update: false, delete: false, export: true });
  });
});

describe('computeLayout', () => {
  const layoutCols = cols.map((c) => ({ key: c.key, primary: c.primary, priority: c.priority }));
  it('shows everything when it fits', () => {
    const r = computeLayout({ width: 1200, columns: layoutCols, iconActions: 3, hasInlineAction: false });
    expect(r.shown).toHaveLength(6);
    expect(r.cardMode).toBe(false);
  });
  it('drops the lowest-priority (latest) columns first, never the primary', () => {
    const width = tableWidthFor(4, 3, false);
    const r = computeLayout({ width, columns: layoutCols, iconActions: 3, hasInlineAction: false });
    expect(r.autoHidden.map((c) => c.key)).toEqual(['live', 'vet']);
    expect(r.cardMode).toBe(false);
  });
  it('honours explicit priority', () => {
    const withPriority = layoutCols.map((c) => (c.key === 'type' ? { ...c, priority: 1 } : c));
    const r = computeLayout({ width: tableWidthFor(5, 3, false), columns: withPriority, iconActions: 3, hasInlineAction: false });
    expect(r.autoHidden.map((c) => c.key)).toEqual(['type']);
  });
  it('falls back to cards below 600px or when 3 columns still do not fit', () => {
    expect(computeLayout({ width: 599, columns: layoutCols.slice(0, 2), iconActions: 1, hasInlineAction: false }).cardMode).toBe(true);
    expect(computeLayout({ width: 640, columns: layoutCols, iconActions: 3, hasInlineAction: true }).cardMode).toBe(true);
  });
  it('drops more columns when the rendered table overflowed (S-141), then falls back to cards', () => {
    const width = tableWidthFor(6, 3, false);
    expect(computeLayout({ width, columns: layoutCols, iconActions: 3, hasInlineAction: false, squeeze: 1 }).autoHidden.map((c) => c.key)).toEqual(['live']);
    const two = computeLayout({ width, columns: layoutCols, iconActions: 3, hasInlineAction: false, squeeze: 2 });
    expect(two.shown).toHaveLength(4);
    expect(two.cardMode).toBe(false);
    expect(computeLayout({ width, columns: layoutCols, iconActions: 3, hasInlineAction: false, squeeze: 4 }).cardMode).toBe(true);
  });
});

describe('record form model', () => {
  const t = ((k: string, v?: Record<string, unknown>) => (v?.label ? `${v.label}:${k}` : k)) as DataTableT;
  const fields = buildFieldSpecs(cols, products, (c) => `${c.label} detail`);
  it('builds fields from editable columns (+ sub), with choices for options/tags', () => {
    expect(fields.map((f) => `${f.name}:${f.kind}`)).toEqual(['name:text', 'sku:text', 'type:choice', 'price:number', 'stock:number', 'live:choice']);
    expect(fields.find((f) => f.name === 'name')!.required).toBe(true);
    expect(fields.find((f) => f.name === 'sku')!.label).toBe('SKU');
  });
  it('round-trips money through dollars and cents', () => {
    const init = initialValues(fields, products[1]!);
    expect(init).toMatchObject({ price: '19.00', stock: '22', type: 'Product' });
    expect(convertValues(fields, { ...init, price: '19,5', stock: '' })).toMatchObject({ price: 1950, stock: null, name: 'Wiper blades · 22"' });
    expect(initialValues(fields, null)).toMatchObject({ name: '', type: 'Service', live: 'Live' });
  });
  it('validates required and numeric fields with localised messages', () => {
    const schema = buildSchema(fields, t);
    const bad = schema.safeParse({ ...initialValues(fields, null), price: 'abc' });
    expect(bad.success).toBe(false);
    const messages = bad.error!.issues.map((i) => `${i.path.join('.')}=${i.message}`);
    expect(messages).toEqual(['name=Listing:required', 'price=notNumber']);
    expect(schema.safeParse(initialValues(fields, products[0]!)).success).toBe(true);
  });
});

it('pluralize (English fallback)', () => {
  expect(['order', 'entry', 'key', 'tax', 'match'].map(pluralize)).toEqual(['orders', 'entries', 'keys', 'taxes', 'matches']);
});
