import { queryOptions, useQuery } from '@tanstack/react-query';
import { type ReactNode, useMemo } from 'react';
import { z } from 'zod';
import { configurePlatformTimeZone, defineMessages, MessageValues, setTimeZone, useLocale, type Locale } from '@northline/ui';
import { http } from '../../lib/http';

/*
 * Where things are (S-134, region-neutral): province names, launch status, time zones and privacy laws come from the
 * region model (`GET /api/v1/geo/regions`), never from a list in this app. Copy that names a place takes it as a
 * parameter ({province}, {city}, {privacyLaw}), filled once for the screen by <PlaceValues>.
 */

export const LaunchStatus = z.enum(['off', 'waitlist', 'pilot', 'live']);
export const RegionProvince = z.object({
  code: z.string(), name: z.string(), nameIn: z.string(), nameOf: z.string(), status: LaunchStatus, timeZone: z.string(), timeZones: z.array(z.string()),
  privacyLaw: z.string(), taxBps: z.number(),
});
export type RegionProvince = z.infer<typeof RegionProvince>;
export const RegionMarket = z.object({
  id: z.string(), city: z.string(), province: z.string(), timeZone: z.string(), status: LaunchStatus,
  lat: z.number().nullish(), lng: z.number().nullish(),
});
export const Regions = z.object({
  platformTimeZone: z.string(), defaultProvince: z.string().nullish(),
  provinces: z.array(RegionProvince), markets: z.array(RegionMarket),
});
export type Regions = z.infer<typeof Regions>;

/** The region model in the language; also configures the platform zone (dates that belong to no market). */
export const regionsQuery = (locale: Locale) => queryOptions({
  queryKey: ['regions', locale],
  queryFn: async () => {
    const regions = await http(`/api/v1/geo/regions?lang=${locale}`, {}, Regions);
    configurePlatformTimeZone(regions.platformTimeZone);
    return regions;
  },
  staleTime: 5 * 60_000,
});

export function useRegions(): Regions | undefined {
  const { locale } = useLocale();
  return useQuery(regionsQuery(locale)).data;
}

/** A province's names in the current language ({@code name}, "in …", "of …"); the code until the model has loaded. */
export function useProvince(code: string | null | undefined): { name: string; nameIn: string; nameOf: string } {
  const regions = useRegions();
  const p = code ? regions?.provinces.find(r => r.code === code) : undefined;
  const name = p?.name ?? code ?? '';
  return { name, nameIn: p?.nameIn ?? name, nameOf: p?.nameOf ?? name };
}

const usePlaceT = defineMessages({
  en: { law_pipeda: 'PIPEDA', law_pipa: 'PIPEDA / {province} PIPA', law_law25: 'PIPEDA / Law 25 ({province})' },
  fr: { law_pipeda: 'LPRPDE', law_pipa: 'LPRPDE / PIPA ({province})', law_law25: 'LPRPDE / Loi 25 ({province})' },
});

/** The private-sector privacy law to cite for a province (region data: pipeda | ab_pipa | bc_pipa | qc_law25). */
export function usePrivacyLaw(code: string | null | undefined, province: string): string {
  const t = usePlaceT();
  if (code === 'qc_law25') return t('law_law25', { province });
  if (code && code.endsWith('_pipa')) return t('law_pipa', { province });
  return t('law_pipeda');
}

/**
 * Fills {province} ("Alberta"), {provinceIn} ("in Alberta" / "au Québec"), {provinceOf} ("Alberta" / "de l'Alberta"),
 * {city} and {privacyLaw} for every message below it, and (with `timeZone`) makes the screen's dates read in that
 * zone — the merchant's market's.
 */
export function PlaceValues({ province, provinceIn, provinceOf, city, privacyLaw, timeZone, children }: {
  province: string; provinceIn?: string; provinceOf?: string; city?: string | null; privacyLaw?: string | null;
  timeZone?: string | null; children: ReactNode;
}) {
  if (timeZone) setTimeZone(timeZone);
  const law = usePrivacyLaw(privacyLaw, province);
  const values = useMemo(
    () => ({ province, provinceIn: provinceIn ?? province, provinceOf: provinceOf ?? province, city: city ?? '', privacyLaw: law }),
    [province, provinceIn, provinceOf, city, law],
  );
  return <MessageValues values={values}>{children}</MessageValues>;
}

/**
 * {@link PlaceValues} for a province code (onboarding: the application's province, else the configured default) and an
 * optional city; a screen with no city yet uses the province's first live market as its example city.
 */
export function ProvincePlace({ code, city, children }: { code?: string | null; city?: string | null; children: ReactNode }) {
  const regions = useRegions();
  const p = regions?.provinces.find(r => r.code === (code ?? regions.defaultProvince));
  const markets = regions?.markets.filter(m => m.province === p?.code) ?? [];
  const market = city ? markets.find(m => m.city.toLowerCase() === city.toLowerCase()) : undefined;
  const example = city ?? markets.find(m => m.status === 'live')?.city ?? markets[0]?.city ?? '';
  return (
    <PlaceValues province={p?.name ?? ''} provinceIn={p?.nameIn} provinceOf={p?.nameOf} city={example} privacyLaw={p?.privacyLaw} timeZone={market?.timeZone ?? p?.timeZone}>
      {children}
    </PlaceValues>
  );
}
