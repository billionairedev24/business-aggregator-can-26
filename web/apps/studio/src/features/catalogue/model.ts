import type { Category, CatalogMatch, Fulfilment, HandlingTime, IdentifierType, ItemCondition, ListingItem, Media, PricingMode, ProductDetail, ProductPayload, ReturnsPolicy, ServiceDetail, ServicePayload, VariantTheme } from './api';
import type { MerchantType } from '../shell/api';
import { MSG, PROMO, centsToInput, gtinProblem, issuesByField, parseCount, parseMoney, productDraftSchema, serviceDraftSchema } from './validation';

// ── portal ────────────────────────────────────────────────────────────────────────────────────────────────────────
export type Portal = 'seller' | 'provider' | 'both';
/** Which catalogue a business sees (design: sellerOnly / provOnly / both). Kitchens have no listings screen. */
export const portalOf = (type: MerchantType): Portal => (type === 'seller' ? 'seller' : type === 'provider' ? 'provider' : 'both');
export const kindsOf = (portal: Portal) => ({ products: portal !== 'provider', services: portal !== 'seller' });

/** Role gating (design `_tables`: owner full CRUD, technician edit no delete, bookkeeper view-only). */
export function permissions(role: string) {
  const canEdit = role !== 'bookkeeper';
  return { create: canEdit, update: canEdit, delete: role === 'owner', export: true, manage: role === 'owner' };
}

// ── table rows ────────────────────────────────────────────────────────────────────────────────────────────────────
export type VetState = 'approved' | 'pending' | 'review' | 'draft' | 'rejected';
export const vetState = (l: Pick<ListingItem, 'vetting' | 'vettingFlags'>): VetState =>
  l.vetting === 'pending' ? (l.vettingFlags.length ? 'review' : 'pending') : l.vetting;
export const minutesSince = (iso: string | null | undefined, now = Date.now()) => (iso ? Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000)) : 0);

// ── product form ──────────────────────────────────────────────────────────────────────────────────────────────────
export interface VariantRow { key: string; id?: string | null; value: string; sku: string; gtin: string; price: string; stock: string }

export interface ProductForm {
  identifierType: IdentifierType; gtin: string; title: string; brand: string; mpn: string; categoryId: string;
  attributes: Record<string, string>; description: string; bullets: string[];
  variantTheme: VariantTheme; variants: VariantRow[];
  imageSource: 'shared' | 'own'; images: Media[];
  sku: string; price: string; compareAt: string; cost: string; condition: ItemCondition; stock: string; lowStockAt: string;
  fulfilment: Fulfilment[]; handlingTime: HandlingTime | ''; returnsPolicy: ReturnsPolicy | '';
  countryOfOrigin: string; restrictedOk: boolean; bilingualOk: boolean; warranty: boolean; searchKeywords: string;
}

/** The shared catalogue record behind the listing (after a GTIN lookup, or from the saved listing). */
export interface CatalogueLink { ref: string; title: string; sellerCount: number; locked: boolean; images: Media[]; readOnly: boolean }

let rowSeq = 0;
export const rowKey = () => `v${++rowSeq}`;

export const emptyProduct = (): ProductForm => ({
  identifierType: 'gtin', gtin: '', title: '', brand: '', mpn: '', categoryId: '', attributes: {}, description: '', bullets: [],
  variantTheme: 'none', variants: [], imageSource: 'own', images: [],
  sku: '', price: '', compareAt: '', cost: '', condition: 'new', stock: '', lowStockAt: '5',
  fulfilment: ['pooled'], handlingTime: 'same_day', returnsPolicy: 'standard_14',
  countryOfOrigin: '', restrictedOk: false, bilingualOk: false, warranty: false, searchKeywords: '',
});

export const productFromDetail = (d: ProductDetail): ProductForm => ({
  identifierType: d.identifierType, gtin: d.gtin ?? '', title: d.title, brand: d.brand ?? '', mpn: d.mpn ?? '', categoryId: d.categoryId ?? '',
  attributes: { ...d.attributes }, description: d.description ?? '', bullets: [...d.bullets],
  variantTheme: d.variantTheme,
  variants: d.variants.map(v => ({ key: rowKey(), id: v.id, value: v.value, sku: v.sku, gtin: v.gtin ?? '', price: centsToInput(v.priceCents), stock: String(v.stock) })),
  imageSource: d.imageSource, images: d.images,
  sku: d.sku ?? '', price: centsToInput(d.priceCents), compareAt: centsToInput(d.compareAtCents), cost: centsToInput(d.costCents), condition: d.condition,
  stock: String(d.stock), lowStockAt: d.lowStockAt == null ? '' : String(d.lowStockAt),
  fulfilment: d.fulfilment, handlingTime: d.handlingTime ?? '', returnsPolicy: d.returnsPolicy ?? '',
  countryOfOrigin: d.countryOfOrigin ?? '', restrictedOk: d.restrictedOk, bilingualOk: d.bilingualOk, warranty: d.warranty, searchKeywords: d.searchKeywords ?? '',
});

export const linkFromDetail = (d: ProductDetail): CatalogueLink | null =>
  d.sharedRecord ? { ref: d.catalogRef, title: d.catalogTitle, sellerCount: d.sellerCount, locked: d.contentLocked, images: d.catalogueImages, readOnly: d.contentShared } : null;

export const linkFromMatch = (m: CatalogMatch): CatalogueLink => ({ ref: m.ref, title: m.title, sellerCount: m.sellerCount, locked: m.locked, images: m.images, readOnly: true });

/** Pre-fills shared content after a GTIN match ("shared title, images and attributes are pre-filled"). */
export const applyMatch = (f: ProductForm, m: CatalogMatch): ProductForm => ({
  ...f, title: f.title || m.title, brand: m.brand ?? '', mpn: m.mpn ?? '', categoryId: m.categoryId ?? f.categoryId, attributes: { ...m.attributes },
  description: m.description ?? '', bullets: [...m.bullets], imageSource: 'shared',
});

const nullIfBlank = (s: string) => (s.trim() ? s.trim() : null);

export function productPayload(f: ProductForm): ProductPayload {
  const hasId = f.identifierType !== 'none';
  return {
    identifierType: f.identifierType, gtin: hasId ? nullIfBlank(f.gtin) : null, title: f.title.trim(), brand: nullIfBlank(f.brand), mpn: nullIfBlank(f.mpn),
    categoryId: f.categoryId, attributes: f.attributes, description: nullIfBlank(f.description), bullets: f.bullets.map(b => b.trim()).filter(Boolean),
    variantTheme: f.variantTheme,
    variants: f.variantTheme === 'none' ? [] : f.variants.map(v => ({ id: v.id ?? null, value: v.value.trim(), sku: v.sku.trim(), gtin: nullIfBlank(v.gtin), priceCents: parseMoney(v.price) ?? Number.NaN, stock: parseCount(v.stock) ?? Number.NaN })),
    imageSource: f.imageSource, imageIds: f.images.map(i => i.id),
    sku: nullIfBlank(f.sku), priceCents: parseMoney(f.price) ?? Number.NaN, compareAtCents: parseMoney(f.compareAt), costCents: parseMoney(f.cost), condition: f.condition,
    stock: parseCount(f.stock) ?? Number.NaN, lowStockAt: parseCount(f.lowStockAt), fulfilment: f.fulfilment,
    handlingTime: f.handlingTime || null, returnsPolicy: f.returnsPolicy || null,
    countryOfOrigin: nullIfBlank(f.countryOfOrigin), restrictedOk: f.restrictedOk, bilingualOk: f.bilingualOk, warranty: f.warranty, searchKeywords: nullIfBlank(f.searchKeywords),
  };
}

/** "Save draft" checks (server: Bean Validation + ProductDetails.validate). Keys are the server's field paths. */
export function validateProductDraft(f: ProductForm, category: Category | undefined): Record<string, string> {
  const p = productPayload(f);
  const parsed = productDraftSchema.safeParse({ ...p, stock: Number.isNaN(p.stock) && !f.stock.trim() ? undefined : p.stock, priceCents: !f.price.trim() ? undefined : p.priceCents });
  const errors = parsed.success ? {} : issuesByField(parsed.error);
  const add = (k: string, m: string) => { errors[k] ??= m; };
  if (f.identifierType !== 'none') {
    if (!f.gtin.trim()) add('gtin', MSG.GTIN_REQUIRED);
    else { const g = gtinProblem(f.gtin); if (g) add('gtin', g); }
  }
  if (f.categoryId && category) {
    if (category.id.startsWith('service.')) add('categoryId', MSG.CATEGORY_WRONG_ROOT);
    else if (!category.leaf) add('categoryId', MSG.CATEGORY_LEAF);
  }
  if (p.compareAtCents != null && !Number.isNaN(p.priceCents) && p.compareAtCents <= p.priceCents) add('compareAtCents', MSG.COMPARE_AT_HIGHER);
  if (f.returnsPolicy === 'final_sale' && !category?.perishable) add('returnsPolicy', MSG.FINAL_SALE_PERISHABLE);
  const values = new Set<string>(); const skus = new Set<string>();
  p.variants.forEach((v, i) => {
    if (v.value && values.has(v.value.toLowerCase())) add(`variants[${i}].value`, MSG.VARIANT_DUPLICATE);
    values.add(v.value.toLowerCase());
    if (v.sku && skus.has(v.sku.toLowerCase())) add(`variants[${i}].sku`, MSG.SKU_DUPLICATE);
    skus.add(v.sku.toLowerCase());
    if (v.gtin) { const g = gtinProblem(v.gtin); if (g) add(`variants[${i}].gtin`, g); }
  });
  return errors;
}

export type Section = 'identity' | 'variants' | 'images' | 'offer' | 'compliance';
export const SECTIONS: Section[] = ['identity', 'variants', 'images', 'offer', 'compliance'];

/** Completeness meter (server: ProductDetails.completeness): 5 sections + Preview (always complete) = 6. */
export function productCompleteness(f: ProductForm, category: Category | undefined, link: CatalogueLink | null) {
  const price = parseMoney(f.price);
  const done: Record<Section, boolean> = {
    identity: !!f.title.trim() && !!category?.leaf && category.attributes.every(a => !a.required || !!f.attributes[a.key]),
    variants: f.variantTheme === 'none' || f.variants.length > 0,
    images: f.imageSource === 'shared' ? (link?.images.length ?? 0) > 0 : f.images.length > 0,
    offer: price !== null && price > 0 && f.fulfilment.length > 0,
    compliance: !!f.countryOfOrigin && f.restrictedOk && f.bilingualOk,
  };
  const count = SECTIONS.filter(s => done[s]).length + 1;
  return { done, percent: Math.round((count / 6) * 100), complete: count === 6 };
}

export const variantReady = (v: VariantRow) => !!v.gtin.trim() && !gtinProblem(v.gtin);
/** SKU for a new variant row: the listing SKU stem + the value's digits (design: WB-22 → WB-26). */
export function variantSku(baseSku: string, value: string) {
  const stem = (baseSku || 'VAR').replace(/-[^-]*$/, '') || baseSku || 'VAR';
  const suffix = value.replace(/\D/g, '') || value.replace(/[^A-Za-z0-9]/g, '').slice(0, 3).toUpperCase();
  return `${stem}-${suffix}`;
}

// ── service form ──────────────────────────────────────────────────────────────────────────────────────────────────
export interface ServiceForm { name: string; categoryId: string; pricingMode: PricingMode; price: string; durationMin: number; bufferMin: number; included: string; instantBook: boolean; sku: string }
export const emptyService = (): ServiceForm => ({ name: '', categoryId: '', pricingMode: 'fixed', price: '', durationMin: 60, bufferMin: 0, included: '', instantBook: true, sku: '' });
export const serviceFromDetail = (d: ServiceDetail): ServiceForm => ({
  name: d.name, categoryId: d.categoryId ?? '', pricingMode: d.pricingMode, price: centsToInput(d.priceCents), durationMin: d.durationMin, bufferMin: d.bufferMin,
  included: d.included ?? '', instantBook: d.instantBook, sku: d.sku ?? '',
});
export const servicePayload = (f: ServiceForm): ServicePayload => ({
  name: f.name.trim(), categoryId: f.categoryId || null, pricingMode: f.pricingMode, priceCents: f.pricingMode === 'quote' ? null : parseMoney(f.price),
  durationMin: f.durationMin, bufferMin: f.bufferMin, included: nullIfBlank(f.included), instantBook: f.instantBook, sku: nullIfBlank(f.sku),
});
export function validateServiceDraft(f: ServiceForm, category: Category | undefined): Record<string, string> {
  const parsed = serviceDraftSchema.safeParse(servicePayload(f));
  const errors = parsed.success ? {} : issuesByField(parsed.error);
  if (f.categoryId && category) {
    if (!category.id.startsWith('service.')) errors.categoryId ??= MSG.CATEGORY_WRONG_ROOT_SERVICE;
    else if (!category.leaf) errors.categoryId ??= MSG.CATEGORY_LEAF;
  }
  return errors;
}
export type ServiceSection = 'details' | 'pricing' | 'schedule' | 'included';
export function serviceCompleteness(f: ServiceForm, category: Category | undefined) {
  const price = parseMoney(f.price);
  const done: Record<ServiceSection, boolean> = {
    details: !!f.name.trim() && !!category?.leaf,
    pricing: f.pricingMode === 'quote' || (price !== null && price > 0),
    schedule: f.durationMin > 0,
    included: !!f.included.trim(),
  };
  const count = Object.values(done).filter(Boolean).length;
  return { done, percent: Math.round((count / 4) * 100), complete: count === 4 };
}

// ── side panels ───────────────────────────────────────────────────────────────────────────────────────────────────
/** Take rate by tier (design: 15% to start, 9% at Master; Trusted 12%) and card processing (2.9% + 30¢). */
export function fees(priceCents: number, costCents: number | null, tier: string | null | undefined) {
  const rate = tier === 'master' ? 9 : tier === 'trusted' ? 12 : 15;
  const fee = Math.round((priceCents * rate) / 100);
  const processing = Math.round(priceCents * 0.029 + 30);
  const net = priceCents - fee - processing;
  const margin = costCents !== null && net > 0 ? Math.round(((net - costCents) / net) * 100) : null;
  return { rate, fee, processing, net, margin };
}

export type Check = { ok: boolean | null; key: string; values?: Record<string, string | number> };
const RESTRICTED = /\b(cbd|thc|vape|e-?cig|cannabis|nicotine|firearm|ammo|replica)\b/i;

/** The vetting preview panel: what automated vetting will look at, computed from the form. */
export function vettingChecks(input: {
  category: Category | undefined; priceCents: number | null; images: Media[]; imageSource: 'shared' | 'own'; link: CatalogueLink | null;
  text: string; gtin: { type: IdentifierType; value: string } | null; money: (c: number) => string;
}): Check[] {
  const { category, priceCents, images, imageSource, link, text, gtin, money } = input;
  const checks: Check[] = [];
  checks.push(!category ? { ok: null, key: 'vpCategoryMissing' } : category.banned ? { ok: false, key: 'vpCategoryBanned' } : { ok: true, key: 'vpCategoryOk' });
  const median = category?.medianPriceCents;
  if (median && priceCents && priceCents > 0) {
    const dev = Math.round(((priceCents - median) / median) * 100);
    checks.push(Math.abs(priceCents - median) <= 0.6 * median ? { ok: true, key: 'vpPriceOk', values: { median: money(median) } } : { ok: false, key: 'vpPriceOut', values: { pct: Math.abs(dev), dir: dev < 0 ? 'below' : 'above', median: money(median) } });
  } else checks.push({ ok: null, key: 'vpPriceNoMedian' });
  if (imageSource === 'shared' && link?.images.length) checks.push({ ok: true, key: 'vpImagesShared' });
  else if (images.length === 0) checks.push({ ok: null, key: 'vpImagesMissing' });
  else if (!images[0]!.onWhite) checks.push({ ok: false, key: 'vpImagesNotWhite' });
  else checks.push({ ok: true, key: 'vpImagesOwn', values: { count: images.length } });
  checks.push(RESTRICTED.test(text) || PROMO.test(text) ? { ok: false, key: 'vpKeywordsBad' } : { ok: true, key: 'vpKeywordsOk' });
  if (gtin) {
    if (gtin.type === 'none') checks.push({ ok: true, key: 'vpGtinNone' });
    else if (link) checks.push({ ok: true, key: 'vpGtinMatched' });
    else checks.push(gtin.value && !gtinProblem(gtin.value) ? { ok: true, key: 'vpGtinValid' } : { ok: false, key: 'vpGtinBad' });
  }
  if (category?.regulatedRegistry) checks.push({ ok: null, key: 'vpLicence', values: { registry: category.regulatedRegistry } });
  return checks;
}

// ── categories ────────────────────────────────────────────────────────────────────────────────────────────────────
/** Path root → leaf for a category id (for the cascading dropdowns). */
export function categoryPath(all: Category[], id: string): Category[] {
  const byId = new Map(all.map(c => [c.id, c]));
  const path: Category[] = [];
  for (let c = byId.get(id); c; c = c.parentId ? byId.get(c.parentId) : undefined) path.unshift(c);
  return path;
}
export const childrenOf = (all: Category[], parentId: string | null) => all.filter(c => (c.parentId ?? null) === parentId);
