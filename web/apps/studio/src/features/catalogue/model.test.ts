import { describe, expect, it } from 'vitest';
import type { Category } from './api';
import { bundleFacts, emptyProduct, fees, productPayload, permissions, portalOf, productCompleteness, validateProductDraft, vetState, variantSku, validateServiceDraft, emptyService, type ProductForm } from './model';
import { MSG, gtinProblem, parseMoney } from './validation';

const autoParts: Category = {
  id: 'shop.hardware-and-auto.auto-parts', parentId: 'shop.hardware-and-auto', name: 'Auto parts', leaf: true, regulatedRegistry: null, banned: false, perishable: false,
  attributes: [{ key: 'partType', label: 'Part type', options: ['Wiper blades'], required: true }], variantThemes: ['length'], medianPriceCents: 2100,
};
const filled = (patch: Partial<ProductForm> = {}): ProductForm => ({
  ...emptyProduct(), identifierType: 'none', title: 'Wiper blades · 22"', categoryId: autoParts.id, attributes: { partType: 'Wiper blades' }, price: '19.00', stock: '22', ...patch,
});

describe('validation helpers', () => {
  it('checks GTIN length and check digit', () => {
    expect(gtinProblem('028851200226')).toBeUndefined();
    expect(gtinProblem('028851200220')).toBe(MSG.GTIN_CHECK_DIGIT);
    expect(gtinProblem('12345')).toBe(MSG.GTIN_FORMAT);
  });
  it('parses money into cents', () => {
    expect(parseMoney('$19.00')).toBe(1900);
    expect(parseMoney('1,299.5')).toBe(129950);
    expect(parseMoney('')).toBeNull();
    expect(parseMoney('abc')).toBeNaN();
  });
});

describe('product draft validation (same messages as the server)', () => {
  it('requires title, category, price and stock', () => {
    const e = validateProductDraft({ ...emptyProduct(), identifierType: 'none' }, undefined);
    expect(e).toMatchObject({ title: MSG.TITLE_REQUIRED, categoryId: MSG.CATEGORY_REQUIRED, priceCents: MSG.PRICE_REQUIRED, stock: MSG.STOCK_REQUIRED });
  });
  it('flags promo words, bad GTINs, compare-at and final sale', () => {
    const e = validateProductDraft(filled({ title: 'Wipers · best price', identifierType: 'gtin', gtin: '028851200220', compareAt: '10', returnsPolicy: 'final_sale' }), autoParts);
    expect(e).toMatchObject({ title: MSG.TITLE_PROMO, gtin: MSG.GTIN_CHECK_DIGIT, compareAtCents: MSG.COMPARE_AT_HIGHER, returnsPolicy: MSG.FINAL_SALE_PERISHABLE });
  });
  it('checks variant rows by server field path', () => {
    const row = { key: 'a', value: '20 in', sku: 'WB-20', gtin: '', price: '17', stock: '1', images: [] };
    const e = validateProductDraft(filled({ variantTheme: 'length', variants: [row, { ...row, key: 'b', sku: '' }, { ...row, key: 'c' }] }), autoParts);
    expect(e['variants[1].sku']).toBe(MSG.SKU_REQUIRED);
    expect(e['variants[2].value']).toBe(MSG.VARIANT_DUPLICATE);
  });
  it('accepts a valid draft', () => {
    expect(validateProductDraft(filled(), autoParts)).toEqual({});
  });
  it('service drafts need a price unless quoted', () => {
    expect(validateServiceDraft({ ...emptyService(), name: 'Brake inspection' }, undefined).priceCents).toBe(MSG.PRICE_REQUIRED);
    expect(validateServiceDraft({ ...emptyService(), name: 'Rebuild', pricingMode: 'quote' }, undefined)).toEqual({});
  });
});

describe('completeness meter', () => {
  it('counts six sections, preview always complete', () => {
    expect(productCompleteness(filled(), autoParts, null).percent).toBe(67); // identity, variants, offer, preview
    const done = productCompleteness(filled({ images: [{ id: 'm', url: '/x', width: 1200, height: 1200, onWhite: true }], countryOfOrigin: 'CA', restrictedOk: true, bilingualOk: true }), autoParts, null);
    expect(done.complete).toBe(true);
    expect(done.percent).toBe(100);
  });
});

describe('side panels and table helpers', () => {
  it('reproduces the design fees line for a Master seller', () => {
    expect(fees(1900, 1120, 'master')).toEqual({ rate: 9, fee: 171, processing: 85, net: 1644, margin: 32 });
  });
  it('maps vetting to table states and portals', () => {
    expect(vetState({ vetting: 'pending', vettingFlags: [] })).toBe('pending');
    expect(vetState({ vetting: 'pending', vettingFlags: ['price_outlier'] })).toBe('review');
    expect(portalOf('both')).toBe('both');
    expect(permissions('technician')).toMatchObject({ update: true, delete: false });
    expect(permissions('bookkeeper')).toMatchObject({ create: false, update: false });
    expect(variantSku('WB-22', '26 in')).toBe('WB-26');
  });
});

describe('bundles and variant images (S-65)', () => {
  const item = (offerId: string, qty: string, stock: number, unitPriceCents: number) => ({ key: offerId, offerId, variantId: null, qty, name: offerId, option: null, unitPriceCents, stock });
  it('counts whole bundles the items allow and the price bought separately', () => {
    expect(bundleFacts({ bundle: [item('a', '2', 9, 500), item('b', '1', 3, 1200)] })).toEqual({ available: 3, separateCents: 2200 });
    expect(bundleFacts({ bundle: [] })).toEqual({ available: 0, separateCents: 0 });
  });
  it('needs two units for the contents section and sends items, no stock, no variants', () => {
    const one = filled({ ...emptyProduct('bundle'), title: 'Wiper pair', categoryId: autoParts.id, attributes: { partType: 'Wiper blades' }, price: '30', bundle: [item('a', '1', 5, 1900)] });
    expect(productCompleteness(one, autoParts, null).done.variants).toBe(false);
    const two = { ...one, bundle: [item('a', '2', 5, 1900)] };
    expect(productCompleteness(two, autoParts, null).done.variants).toBe(true);
    const p = productPayload(two);
    expect(p).toMatchObject({ type: 'bundle', identifierType: 'none', stock: 0, lowStockAt: null, variants: [], bundleItems: [{ offerId: 'a', variantId: null, qty: 2 }] });
  });
  it('flags the same item twice and a quantity out of range', () => {
    const f = filled({ ...emptyProduct('bundle'), title: 'Pair', categoryId: autoParts.id, price: '30', bundle: [item('a', '1', 5, 1), item('a', '100', 5, 1)] });
    const e = validateProductDraft(f, autoParts);
    expect(e['bundleItems[1].offerId']).toBe(MSG.BUNDLE_ITEM_DUPLICATE);
    expect(e['bundleItems[1].qty']).toBe(MSG.BUNDLE_QTY_RANGE);
  });
  it("sends each variant's own images", () => {
    const image = { id: 'M1', url: '/m/M1', width: 1200, height: 1200, onWhite: true };
    const row = { key: 'a', value: '20 in', sku: 'WB-20', gtin: '', price: '17', stock: '1', images: [image] };
    expect(productPayload(filled({ variantTheme: 'length', variants: [row] })).variants[0]!.imageIds).toEqual(['M1']);
  });
});
