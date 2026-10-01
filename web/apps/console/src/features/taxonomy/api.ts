import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

const Rule = z.object({ province: z.string(), regulator: z.string().nullish() });
/** One category (S-94; api `ManageTaxonomy.Row`). */
export const Category = z.object({
  id: z.string(), parentId: z.string().nullish(), root: z.enum(['service', 'shop', 'food']), group: z.boolean(), nameEn: z.string(), nameFr: z.string().nullish(),
  bookingType: z.string().nullish(), regulatedRegistry: z.string().nullish(), requiresVsCheck: z.boolean(), regulators: z.array(Rule),
  sellers: z.number(), liveIn: z.array(z.string()), liveListings: z.number(), medianPriceCents: z.number().nullish(), priceMode: z.string().nullish(),
});
export type Category = z.infer<typeof Category>;
export const Regulator = z.object({ code: z.string(), name: z.string(), province: z.string(), website: z.string().nullish(), categories: z.number() });
export type Regulator = z.infer<typeof Regulator>;
export const Limit = z.object({ merchantType: z.enum(['provider', 'seller', 'both', 'kitchen']), max: z.number(), businessesAbove: z.number(), updatedAt: z.string(), updatedBy: z.string() });
export type Limit = z.infer<typeof Limit>;
export const Suggestion = z.object({
  id: z.string(), name: z.string(),
  businesses: z.array(z.object({ id: z.string(), name: z.string(), type: z.string(), province: z.string().nullish(), status: z.string().nullish() })),
});
export type Suggestion = z.infer<typeof Suggestion>;
export const Screen = z.object({
  asOf: z.string(), serviceCategories: z.number(), shopDepartments: z.number(), categories: z.array(Category), regulators: z.array(Regulator),
  limits: z.array(Limit), suggestions: z.array(Suggestion),
});
export type Screen = z.infer<typeof Screen>;
const Resolved = z.object({ category: Category, moved: z.number(), alreadyHeld: z.number() });
export type Resolved = z.infer<typeof Resolved>;

export interface CategoryInput {
  root?: string; parentId?: string; nameEn: string; nameFr?: string; bookingType?: string; regulatedRegistry?: string; requiresVsCheck: boolean;
}
export interface RegulatorInput { code?: string; name: string; province: string; website?: string }

const BASE = '/api/v1/console/taxonomy';
const enc = encodeURIComponent;
export const taxonomyQuery = queryOptions({ queryKey: ['console', 'taxonomy'], queryFn: () => http(BASE, {}, Screen) });

function useTaxonomyMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: ['console', 'taxonomy'] }) });
}
export const useCreateCategory = () => useTaxonomyMutation((v: CategoryInput) => http(`${BASE}/categories`, { method: 'POST', body: v }, Category));
export const useUpdateCategory = () => useTaxonomyMutation(({ id, ...v }: CategoryInput & { id: string }) => http(`${BASE}/categories/${enc(id)}`, { method: 'PUT', body: v }, Category));
/** `regulator`: a code, `none` (not regulated there) or null (back to the default registry). */
export const useRegulate = () => useTaxonomyMutation(({ id, province, regulator }: { id: string; province: string; regulator: string | null }) =>
  http(`${BASE}/categories/${enc(id)}/regulators/${enc(province)}`, { method: 'PUT', body: { regulator } }, Category));
export const useSaveRegulator = () => useTaxonomyMutation(({ create, ...v }: RegulatorInput & { create: boolean }) => (create
  ? http(`${BASE}/regulators`, { method: 'POST', body: v }, Regulator)
  : http(`${BASE}/regulators/${enc(v.code ?? '')}`, { method: 'PUT', body: v }, Regulator)));
export const useSetLimit = () => useTaxonomyMutation(({ type, max }: { type: string; max: number }) => http(`${BASE}/limits/${enc(type)}`, { method: 'PUT', body: { max } }, Limit));
export const useApproveSuggestion = () => useTaxonomyMutation(({ id, ...v }: CategoryInput & { id: string }) =>
  http(`${BASE}/suggestions/${enc(id)}/approve`, { method: 'POST', body: v }, Resolved));
export const useMergeSuggestion = () => useTaxonomyMutation(({ id, categoryId }: { id: string; categoryId: string }) =>
  http(`${BASE}/suggestions/${enc(id)}/merge`, { method: 'POST', body: { categoryId } }, Resolved));
