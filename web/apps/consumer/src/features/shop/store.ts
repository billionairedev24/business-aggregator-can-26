import { z } from 'zod';
import { queryOptions, type QueryClient } from '@tanstack/react-query';
import { http } from '@northline/client';
import { SearchPage } from '../search/api';
import type { Locale } from '@northline/ui';
import { departmentQuery, landingQuery, type Department, type Landing, type ShopCard } from './api';
import { withMarket } from './market';

/**
 * A shop's page (E-15 S-172, "the store page"): `/shop/stores/<merchantId>?dept=<department>&market=<city>`. The api has
 * no store endpoint (no contract change): the shop's card comes from its department's page (`dept`, the department the
 * link came from) or the Shop landing, and its goods from the public search by its name, kept to this merchant.
 */
export const storeHref = (merchantId: string, department: string | null | undefined, market: string | undefined) =>
  withMarket(`/shop/stores/${encodeURIComponent(merchantId)}${department ? `?dept=${encodeURIComponent(department)}` : ''}`, market);

export const StoreSearch = z.object({
  dept: z.string().max(80).optional().catch(undefined),
  market: z.string().max(60).optional().catch(undefined),
});
export type StoreSearch = z.infer<typeof StoreSearch>;

export interface StoreData { shop: ShopCard; landing: Landing; department: Department | null }

/** The shop's card from its department (when the link names it), else the landing; null when neither lists it. */
export async function loadStore(qc: QueryClient, merchantId: string, search: StoreSearch, locale: Locale): Promise<StoreData | null> {
  const [landing, department] = await Promise.all([
    qc.ensureQueryData(landingQuery(search.market, locale)),
    search.dept ? qc.ensureQueryData(departmentQuery(search.dept, search.market, locale)).catch(() => null) : Promise.resolve(null),
  ]);
  const shop = department?.shops.find(s => s.merchantId === merchantId) ?? landing.shops.find(s => s.merchantId === merchantId);
  if (!shop) return null;
  await qc.ensureQueryData(storeGoodsQuery(shop.name, locale)).catch(() => undefined); // server-rendered; the page retries
  return { shop, landing, department };
}

/** The public search for the shop's goods: its name, products only. */
export const storeGoodsQuery = (name: string, locale: Locale) => {
  const qs = new URLSearchParams({ q: name, kind: 'product', lang: locale, size: '48' });
  return queryOptions({
    queryKey: ['search', 'store', qs.toString()],
    queryFn: ({ signal }) => http(`/api/v1/search?${qs}`, { signal }, SearchPage),
    staleTime: 60_000,
  });
};
