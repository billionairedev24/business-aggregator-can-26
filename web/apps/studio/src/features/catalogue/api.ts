import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '../../lib/http';

/**
 * Catalogue API (server: ca.northline.catalogue). Listings table, product / service editors, categories, the GTIN
 * lookup, images, bulk upload and commerce integrations. Money is always cents.
 */
const base = (merchantId: string) => `/api/v1/merchants/${merchantId}`;
const items = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item) }).transform(r => r.items);

export const Vetting = z.enum(['draft', 'pending', 'approved', 'rejected']);
export type Vetting = z.infer<typeof Vetting>;
export const ListingStatus = z.enum(['live', 'hidden']);
export const ListingKind = z.enum(['service', 'product']);
export type ListingKind = z.infer<typeof ListingKind>;
export const PricingMode = z.enum(['fixed', 'quote', 'hourly']);
export type PricingMode = z.infer<typeof PricingMode>;
export const VariantTheme = z.enum(['none', 'size', 'colour', 'size_colour', 'length', 'length_position']);
export type VariantTheme = z.infer<typeof VariantTheme>;
export const IdentifierType = z.enum(['gtin', 'ean', 'isbn', 'none']);
export type IdentifierType = z.infer<typeof IdentifierType>;
export const Fulfilment = z.enum(['pooled', 'install', 'pickup', 'ship']);
export type Fulfilment = z.infer<typeof Fulfilment>;
export const ItemCondition = z.enum(['new', 'open_box', 'refurbished', 'used_good']);
export type ItemCondition = z.infer<typeof ItemCondition>;
export const HandlingTime = z.enum(['same_day', 'next_day', 'two_days']);
export type HandlingTime = z.infer<typeof HandlingTime>;
export const ReturnsPolicy = z.enum(['standard_14', 'final_sale']);
export type ReturnsPolicy = z.infer<typeof ReturnsPolicy>;

/** GET /listings — one table row (the onboarding contract + additive fields). */
export const ListingItem = z.object({
  id: z.string(), kind: ListingKind, name: z.string(), sku: z.string().nullish(), meta: z.string(),
  priceCents: z.number().nullish(), stock: z.number().nullish(), sales30d: z.number(),
  vetting: Vetting, status: ListingStatus, vettingFlags: z.array(z.string()).default([]),
  submittedAt: z.string().nullish(), categoryId: z.string().nullish(), pricingMode: PricingMode.nullish(), updatedAt: z.string(),
});
export type ListingItem = z.infer<typeof ListingItem>;

const Completeness = z.object({ percent: z.number(), done: z.number(), total: z.number(), missing: z.array(z.object({ field: z.string(), message: z.string() })) });
export const Media = z.object({ id: z.string(), url: z.string(), width: z.number(), height: z.number(), onWhite: z.boolean() });
export type Media = z.infer<typeof Media>;
export const Variant = z.object({ id: z.string().nullish(), value: z.string(), sku: z.string(), gtin: z.string().nullish(), priceCents: z.number(), stock: z.number() });
export type Variant = z.infer<typeof Variant>;

const lifecycle = {
  id: z.string(), vetting: Vetting, status: ListingStatus, vettingFlags: z.array(z.string()), submittedAt: z.string().nullish(), updatedAt: z.string(),
  completeness: Completeness,
};

export const ProductDetail = z.object({
  ...lifecycle, kind: z.literal('product'),
  catalogRef: z.string(), catalogTitle: z.string(), sharedRecord: z.boolean(), contentShared: z.boolean(), contentLocked: z.boolean(), sellerCount: z.number(),
  identifierType: IdentifierType, gtin: z.string().nullish(), title: z.string(), brand: z.string().nullish(), mpn: z.string().nullish(),
  categoryId: z.string().nullish(), attributes: z.record(z.string(), z.string()), description: z.string().nullish(), bullets: z.array(z.string()),
  variantTheme: VariantTheme, variants: z.array(Variant), imageSource: z.enum(['shared', 'own']), images: z.array(Media), catalogueImages: z.array(Media),
  sku: z.string().nullish(), priceCents: z.number(), compareAtCents: z.number().nullish(), costCents: z.number().nullish(), condition: ItemCondition,
  stock: z.number(), lowStockAt: z.number().nullish(), fulfilment: z.array(Fulfilment), handlingTime: HandlingTime.nullish(), returnsPolicy: ReturnsPolicy.nullish(),
  countryOfOrigin: z.string().nullish(), restrictedOk: z.boolean(), bilingualOk: z.boolean(), warranty: z.boolean(), searchKeywords: z.string().nullish(),
});
export type ProductDetail = z.infer<typeof ProductDetail>;

export const ServiceDetail = z.object({
  ...lifecycle, kind: z.literal('service'),
  name: z.string(), categoryId: z.string().nullish(), pricingMode: PricingMode, priceCents: z.number().nullish(), durationMin: z.number(), bufferMin: z.number(),
  included: z.string().nullish(), instantBook: z.boolean(), sku: z.string().nullish(),
});
export type ServiceDetail = z.infer<typeof ServiceDetail>;
export const ListingDetail = z.discriminatedUnion('kind', [ProductDetail, ServiceDetail]);
export type ListingDetail = z.infer<typeof ListingDetail>;

export const Category = z.object({
  id: z.string(), parentId: z.string().nullish(), name: z.string(), leaf: z.boolean(), regulatedRegistry: z.string().nullish(), banned: z.boolean(), perishable: z.boolean(),
  attributes: z.array(z.object({ key: z.string(), label: z.string(), options: z.array(z.string()), required: z.boolean() })),
  variantThemes: z.array(VariantTheme), medianPriceCents: z.number().nullish(),
});
export type Category = z.infer<typeof Category>;

export const CatalogMatch = z.object({
  id: z.string(), ref: z.string(), gtin: z.string().nullish(), brand: z.string().nullish(), title: z.string(), mpn: z.string().nullish(), categoryId: z.string().nullish(),
  attributes: z.record(z.string(), z.string()), description: z.string().nullish(), bullets: z.array(z.string()), images: z.array(Media), sellerCount: z.number(), locked: z.boolean(),
});
export type CatalogMatch = z.infer<typeof CatalogMatch>;

export const ImportTemplate = z.enum(['auto_parts', 'groceries', 'clothing', 'services', 'price_stock']);
export type ImportTemplate = z.infer<typeof ImportTemplate>;
export const ImportBatch = z.object({
  id: z.string(), fileName: z.string(), template: ImportTemplate, rowCount: z.number(), createCount: z.number(), updateCount: z.number(), errorCount: z.number(),
  errors: z.array(z.object({ row: z.number(), sku: z.string().nullish(), error: z.string() })), status: z.enum(['validated', 'imported']), createdAt: z.string(), importedAt: z.string().nullish(),
});
export type ImportBatch = z.infer<typeof ImportBatch>;

export const CommerceProvider = z.enum(['shopify', 'square', 'lightspeed']);
export type CommerceProvider = z.infer<typeof CommerceProvider>;
export const Connection = z.object({ provider: CommerceProvider, connected: z.boolean(), accountLabel: z.string().nullish(), connectedAt: z.string().nullish(), lastSyncAt: z.string().nullish(), lastSyncCount: z.number().nullish() });
export type Connection = z.infer<typeof Connection>;

// ── queries ─────────────────────────────────────────────────────────────────────────────────────────────────────────
export const catalogueKeys = {
  listings: (m: string) => ['merchant', m, 'listings'] as const,
  listing: (m: string, id: string) => ['merchant', m, 'listings', 'detail', id] as const,
  categories: (m: string, root: string, locale: string) => ['merchant', m, 'categories', root, locale] as const,
  imports: (m: string) => ['merchant', m, 'imports'] as const,
  integrations: (m: string) => ['merchant', m, 'integrations'] as const,
};

export const listingsQuery = (m: string) => queryOptions({ queryKey: catalogueKeys.listings(m), queryFn: () => http(`${base(m)}/listings`, {}, items(ListingItem)) });
export const listingQuery = (m: string, id: string) => queryOptions({ queryKey: catalogueKeys.listing(m, id), queryFn: () => http(`${base(m)}/listings/${id}`, {}, ListingDetail) });
export const categoriesQuery = (m: string, root: 'shop' | 'service', locale: string) => queryOptions({
  queryKey: catalogueKeys.categories(m, root, locale), staleTime: 5 * 60_000,
  queryFn: () => http(`${base(m)}/catalogue/categories?root=${root}`, { headers: { 'accept-language': locale === 'fr' ? 'fr-CA' : 'en-CA' } }, items(Category)),
});
export const importsQuery = (m: string) => queryOptions({ queryKey: catalogueKeys.imports(m), queryFn: () => http(`${base(m)}/listings/imports`, {}, items(ImportBatch)) });
export const integrationsQuery = (m: string) => queryOptions({ queryKey: catalogueKeys.integrations(m), queryFn: () => http(`${base(m)}/listings/integrations`, {}, items(Connection)) });

/** GTIN lookup: the matched shared record, or null when the catalogue has none (404). */
export async function lookupGtin(m: string, gtin: string): Promise<CatalogMatch | null> {
  try { return await http(`${base(m)}/catalogue/products/lookup?gtin=${encodeURIComponent(gtin)}`, {}, CatalogMatch); }
  catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
}

// ── mutations ───────────────────────────────────────────────────────────────────────────────────────────────────────
export type ProductPayload = Omit<ProductDetail, 'id' | 'kind' | 'vetting' | 'status' | 'vettingFlags' | 'submittedAt' | 'updatedAt' | 'completeness' | 'catalogRef' | 'catalogTitle' | 'sharedRecord' | 'contentShared' | 'contentLocked' | 'sellerCount' | 'images' | 'catalogueImages'> & { imageIds: string[] };
export type ServicePayload = Pick<ServiceDetail, 'name' | 'pricingMode' | 'durationMin' | 'bufferMin' | 'instantBook'> & { categoryId: string | null; priceCents: number | null; included: string | null; sku: string | null };

function useInvalidateListings(m: string) {
  const qc = useQueryClient();
  return () => Promise.all([qc.invalidateQueries({ queryKey: catalogueKeys.listings(m) }), qc.invalidateQueries({ queryKey: ['merchant', m, 'nav-badges'] })]);
}

/** Save draft: POST for a new listing, PUT for an existing one. */
export function useSaveProduct(m: string) {
  const qc = useQueryClient(); const invalidate = useInvalidateListings(m);
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: ProductPayload }) =>
      http(id ? `${base(m)}/products/${id}` : `${base(m)}/products`, { method: id ? 'PUT' : 'POST', body }, ProductDetail),
    onSuccess: async d => { qc.setQueryData(catalogueKeys.listing(m, d.id), d); await invalidate(); },
  });
}

export function useSaveService(m: string) {
  const qc = useQueryClient(); const invalidate = useInvalidateListings(m);
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: ServicePayload }) =>
      http(id ? `${base(m)}/services/${id}` : `${base(m)}/services`, { method: id ? 'PUT' : 'POST', body }, ServiceDetail),
    onSuccess: async d => { qc.setQueryData(catalogueKeys.listing(m, d.id), d); await invalidate(); },
  });
}

export function useSubmitListing(m: string) {
  const qc = useQueryClient(); const invalidate = useInvalidateListings(m);
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/listings/${id}/submit`, { method: 'POST' }, ListingDetail),
    onSuccess: async d => { qc.setQueryData(catalogueKeys.listing(m, d.id), d); await invalidate(); },
  });
}

/** Publish / hide (inline table actions) and delete (owner). */
export function useListingActions(m: string) {
  const invalidate = useInvalidateListings(m);
  const qc = useQueryClient();
  const visibility = useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'publish' | 'hide' }) => http(`${base(m)}/listings/${id}/${action}`, { method: 'POST' }),
    onMutate: async ({ id, action }) => {
      await qc.cancelQueries({ queryKey: catalogueKeys.listings(m) });
      const prev = qc.getQueryData<ListingItem[]>(catalogueKeys.listings(m));
      qc.setQueryData<ListingItem[]>(catalogueKeys.listings(m), rows => rows?.map(r => r.id === id ? { ...r, status: action === 'publish' ? 'live' : 'hidden' } : r));
      return { prev };
    },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(catalogueKeys.listings(m), ctx.prev); },
    onSettled: invalidate,
  });
  const remove = useMutation({ mutationFn: (id: string) => http(`${base(m)}/listings/${id}`, { method: 'DELETE' }), onSettled: invalidate });
  return { visibility, remove };
}

export function useUploadImage(m: string) {
  return useMutation({
    mutationFn: (file: File) => { const form = new FormData(); form.append('file', file); return http(`${base(m)}/media`, { method: 'POST', body: form }, Media); },
  });
}

export function useUploadImport(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ file, template }: { file: File; template: ImportTemplate }) => {
      const form = new FormData(); form.append('file', file);
      return http(`${base(m)}/listings/imports?template=${template}`, { method: 'POST', body: form }, ImportBatch);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: catalogueKeys.imports(m) }),
  });
}

export function useCommitImport(m: string) {
  const qc = useQueryClient(); const invalidate = useInvalidateListings(m);
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/listings/imports/${id}/commit`, { method: 'POST' }, ImportBatch),
    onSuccess: async () => { await Promise.all([qc.invalidateQueries({ queryKey: catalogueKeys.imports(m) }), invalidate()]); },
  });
}

export function useIntegrationAction(m: string) {
  const qc = useQueryClient(); const invalidate = useInvalidateListings(m);
  return useMutation({
    mutationFn: ({ provider, action }: { provider: CommerceProvider; action: 'connect' | 'disconnect' | 'sync' }) =>
      http(`${base(m)}/listings/integrations/${provider}/${action}`, { method: 'POST' }, Connection),
    onSuccess: async (c, { action }) => {
      qc.setQueryData<Connection[]>(catalogueKeys.integrations(m), list => list?.map(x => x.provider === c.provider ? c : x));
      if (action === 'sync') await invalidate();
    },
  });
}
