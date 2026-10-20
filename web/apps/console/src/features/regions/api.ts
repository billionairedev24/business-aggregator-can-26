import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

export const STAGES = ['off', 'waitlist', 'pilot', 'live'] as const;
export const Stage = z.enum(STAGES);
export type Stage = z.infer<typeof Stage>;

const Market = z.object({ id: z.string(), city: z.string(), stage: Stage, lat: z.number().nullish(), lng: z.number().nullish(), radiusKm: z.number().nullish(), zones: z.number(), waitlist: z.number() });
export type Market = z.infer<typeof Market>;
const Zone = z.object({ id: z.string(), marketId: z.string(), name: z.string(), runsPerDay: z.number().nullish(), feeStdCents: z.number().nullish(),
  feePlusCents: z.number().nullish(), minBasketCents: z.number().nullish(), areaKm2: z.number().nullish() });
export type Zone = z.infer<typeof Zone>;

/** S-158: a place's language rules (S-116) as stored (null = not set) and as they apply (api `Switchboard.LanguageSetting`). */
export const LanguageSetting = z.object({
  regionId: z.string(), kind: z.enum(['province', 'market']), city: z.string().nullish(), frenchFirst: z.boolean().nullish(),
  frenchListings: z.enum(['off', 'warn', 'require']).nullish(), effectiveFrenchFirst: z.boolean(), effectiveFrenchListings: z.enum(['off', 'warn', 'require']),
  fromConfiguration: z.boolean(),
});
export type LanguageSetting = z.infer<typeof LanguageSetting>;

/** One province of `GET /api/v1/console/regions` (S-84; api `Switchboard.Province`). */
export const Province = z.object({
  id: z.string(), code: z.string(), names: z.record(z.string(), z.string()), stage: Stage, languages: z.array(z.string()), courierModel: z.string().nullish(),
  tax: z.record(z.string(), z.number()), timeZones: z.array(z.string()), holidays: z.array(z.string()), privacyLaw: z.string().nullish(),
  registries: z.array(z.string()), waitlist: z.number(), markets: z.array(Market), zones: z.array(Zone), checklist: z.record(z.string(), z.boolean()),
  languageRules: z.array(LanguageSetting).optional(),
});
export type Province = z.infer<typeof Province>;

export const boardQuery = queryOptions({
  queryKey: ['console', 'regions', 'board'],
  queryFn: () => http('/api/v1/console/regions', {}, z.object({ provinces: z.array(Province) })).then(b => b.provinces),
});

export interface ZoneInput { marketId: string; name: string; runsPerDay?: number | null; feeStdCents?: number | null; feePlusCents?: number | null; minBasketCents?: number | null; boundary?: string | null }

type Change =
  | { kind: 'provinceStage'; code: string; stage: Stage; confirm: string }
  | { kind: 'courierModel'; code: string; courierModel: string }
  | { kind: 'marketStage'; marketId: string; stage: Stage; confirm: string }
  | { kind: 'addMarket'; province: string; city: string; lat: number; lng: number; radiusKm: number }
  | { kind: 'saveZone'; zoneId?: string; zone: ZoneInput }
  | { kind: 'removeZone'; zoneId: string }
  | { kind: 'languages'; regionId: string; frenchFirst: boolean | null; frenchListings: 'off' | 'warn' | 'require' | null };

const BASE = '/api/v1/console/regions';
const call = (c: Change) => {
  switch (c.kind) {
    case 'provinceStage': return http(`${BASE}/provinces/${encodeURIComponent(c.code)}/stage`, { method: 'POST', body: { stage: c.stage, confirm: c.confirm } }, Province);
    case 'courierModel': return http(`${BASE}/provinces/${encodeURIComponent(c.code)}/courier-model`, { method: 'PUT', body: { courierModel: c.courierModel } }, Province);
    case 'marketStage': return http(`${BASE}/markets/${encodeURIComponent(c.marketId)}/stage`, { method: 'POST', body: { stage: c.stage, confirm: c.confirm } }, Province);
    case 'addMarket': return http(`${BASE}/markets`, { method: 'POST', body: { province: c.province, city: c.city, lat: c.lat, lng: c.lng, radiusKm: c.radiusKm } }, Province);
    case 'saveZone': return http(c.zoneId ? `${BASE}/zones/${encodeURIComponent(c.zoneId)}` : `${BASE}/zones`, { method: c.zoneId ? 'PUT' : 'POST', body: c.zone }, Province);
    case 'removeZone': return http(`${BASE}/zones/${encodeURIComponent(c.zoneId)}`, { method: 'DELETE' }, Province);
    case 'languages': return http(`${BASE}/${encodeURIComponent(c.regionId)}/languages`, { method: 'PUT', body: { frenchFirst: c.frenchFirst, frenchListings: c.frenchListings } }, Province);
  }
};

/** Every switchboard change (audited; the api re-reads the region model): the board and the region model reload. */
export function useSwitchboard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: call,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['console', 'regions'] });
      void qc.invalidateQueries({ queryKey: ['regions'] });
    },
  });
}
