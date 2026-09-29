import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '../../lib/http';

/**
 * Client side of the catalogue / kitchen contract (IMPLEMENTATION_PLAN.md › Listings). The catalogue workstream owns
 * `GET …/listings`, `POST …/services`, `POST …/products`; the kitchen workstream owns `POST …/menu-items`.
 * `GET …/menus` and `GET …/modifier-groups` are NOT in the contract yet — see DECISIONS.md; a 404 means "none".
 */
export const Listing = z.object({
  id: z.string(),
  kind: z.enum(['service', 'product']),
  name: z.string(),
  sku: z.string().nullish(),
  meta: z.string().nullish(),
  priceCents: z.number().nullish(),
  stock: z.number().nullish(),
  sales30d: z.number().nullish(),
  vetting: z.enum(['draft', 'pending', 'approved', 'rejected']),
  status: z.enum(['live', 'hidden']).nullish(),
});
export type Listing = z.infer<typeof Listing>;
const Listings = z.union([z.array(Listing), z.object({ items: z.array(Listing) }).transform(r => r.items)]);

/** A created menu item (kitchen contract); only the fields the Listings step shows are required. */
export const MenuItem = z.object({ id: z.string(), name: z.string(), priceCents: z.number().nullish(), status: z.string().nullish() }).passthrough();
export type MenuItem = z.infer<typeof MenuItem>;

export const Menu = z.object({ id: z.string(), name: z.string(), sections: z.array(z.object({ id: z.string(), name: z.string() })).default([]) });
export type Menu = z.infer<typeof Menu>;
export const ModifierGroup = z.object({ id: z.string(), name: z.string() });
const itemsOf = <T extends z.ZodTypeAny>(item: T) => z.union([z.array(item), z.object({ items: z.array(item) }).transform(r => r.items)]);

const notFoundAsEmpty = async <T,>(run: () => Promise<T[]>): Promise<T[]> => {
  try { return await run(); } catch (e) { if (e instanceof ApiError && (e.status === 404 || e.status === 405)) return []; throw e; }
};

export const listingsQuery = (merchantId: string, kind?: 'service' | 'product', limit = 50) => queryOptions({
  queryKey: ['merchant', merchantId, 'listings', { kind: kind ?? 'all', limit }],
  queryFn: () => notFoundAsEmpty(() => http(`/api/v1/merchants/${merchantId}/listings?${new URLSearchParams({ ...(kind ? { kind } : {}), limit: String(limit) })}`, {}, Listings)),
});

export const menusQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'menus'],
  queryFn: () => notFoundAsEmpty(() => http(`/api/v1/merchants/${merchantId}/menus`, {}, itemsOf(Menu))),
});

export const modifierGroupsQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'modifier-groups'],
  queryFn: () => notFoundAsEmpty(() => http(`/api/v1/merchants/${merchantId}/modifier-groups`, {}, itemsOf(ModifierGroup))),
});

export interface NewService { name: string; categoryId?: string; pricingMode: 'fixed' | 'quote' | 'hourly'; priceCents?: number; durationMin: number; bufferMin: number; included: string; instantBook: boolean }
export interface NewProduct { gtin?: string; title: string; categoryId: string; priceCents: number; stock: number; variantTheme?: 'none' | 'size' | 'colour' | 'size_colour' }
export interface NewMenuItem { menuId: string; sectionId: string; name: string; description: string; priceCents: number; prepAddMin: number; allergens: string[]; modifierGroupIds: string[] }

function useCreate<B>(merchantId: string, path: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: B) => http<unknown>(`/api/v1/merchants/${merchantId}/${path}`, { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['merchant', merchantId, 'listings'] }),
  });
}
export const useCreateService = (merchantId: string) => useCreate<NewService>(merchantId, 'services');
export const useCreateProduct = (merchantId: string) => useCreate<NewProduct>(merchantId, 'products');
export function useCreateMenuItem(merchantId: string) {
  return useMutation({ mutationFn: (body: NewMenuItem) => http(`/api/v1/merchants/${merchantId}/menu-items`, { method: 'POST', body }, MenuItem) });
}
