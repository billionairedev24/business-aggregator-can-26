import { queryOptions, useQuery } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext, useEffect, useMemo } from 'react';
import { z } from 'zod';
import { http } from '@northline/client';
import { configurePlatformTimeZone, defineMessages, MessageValues, platformTimeZone, useLocale, type Locale } from '@northline/ui';
import { chosenLocale, LOCALE_COOKIE, readCookie } from '../../lib/locale';
import { useDeliveryLocation, useKnownLocation } from './useDeliveryLocation';

/*
 * The region model on the consumer site (S-134, region-neutral): province names, launch status, privacy laws, markets
 * and their time zones come from `GET /api/v1/geo/regions` — no list of places in this app. Copy that names a place
 * takes it as a parameter, filled once for the page by <VisitorPlace> from the visitor's location (else the default
 * province).
 */

const Status = z.enum(['off', 'waitlist', 'pilot', 'live']);
export const Regions = z.object({
  platformTimeZone: z.string(),
  defaultProvince: z.string().nullish(),
  provinces: z.array(z.object({
    code: z.string(), name: z.string(), nameIn: z.string(), nameOf: z.string(), status: Status, timeZone: z.string(),
    privacyLaw: z.string(),
    // S-116: the province's language rule (region configuration); absent from older servers
    frenchFirst: z.boolean().nullish(),
  })),
  markets: z.array(z.object({ id: z.string(), city: z.string(), province: z.string(), timeZone: z.string(), status: Status, frenchFirst: z.boolean().nullish() })),
});
export type Regions = z.infer<typeof Regions>;

export const regionsQuery = (locale: Locale) => queryOptions({
  queryKey: ['regions', locale],
  queryFn: async () => {
    const regions = await http(`/api/v1/geo/regions?lang=${locale}`, {}, Regions);
    configurePlatformTimeZone(regions.platformTimeZone); // the same for every request: safe on the server
    return regions;
  },
  staleTime: 5 * 60_000,
});

export function useRegions(): Regions | undefined {
  const { locale } = useLocale();
  return useQuery(regionsQuery(locale)).data;
}

/** "Alberta" · "Alberta and Ontario" · "Alberta et Ontario" from the region model; empty when no province is known. */
export function useRegionNames(codes: readonly string[]): string {
  const regions = useRegions();
  const { locale } = useLocale();
  const names = codes.map(c => regions?.provinces.find(p => p.code === c)?.name ?? c);
  return names.length === 0 ? '' : new Intl.ListFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { type: 'conjunction' }).format(names);
}

/** The time zone of a market (a city), else the platform zone (undefined = let the formatter use the platform zone). */
export function useMarketZone(city: string | null | undefined): string | undefined {
  const regions = useRegions();
  if (!city) return undefined;
  return regions?.markets.find(m => m.city.toLowerCase() === city.toLowerCase())?.timeZone;
}

const ZoneContext = createContext<string | undefined>(undefined);

/** Dates below read in the market's zone (a page for one market: the shop, a product, the cart). */
export function MarketZone({ city, children }: { city: string | null | undefined; children: ReactNode }) {
  const zone = useMarketZone(city);
  return <ZoneContext.Provider value={zone}>{children}</ZoneContext.Provider>;
}

/**
 * The zone to show times in: the given market's, else the enclosing {@link MarketZone}'s, else the platform zone. Passed
 * to the formatters explicitly — never module state, which server-side rendering shares between requests.
 */
export function useZone(city?: string | null): string {
  const own = useMarketZone(city);
  const enclosing = useContext(ZoneContext);
  return own ?? enclosing ?? platformTimeZone();
}

/**
 * S-116 (Loi 96 readiness): whether a place is French-first — its market's rule (by id, else city), else its
 * province's. Region configuration only: this app never names the place.
 */
export function frenchFirstPlace(regions: Regions | undefined, place: { province?: string | null; marketId?: string | null; city?: string | null }): boolean {
  if (!regions) return false;
  const market = regions.markets.find(m => (place.marketId && m.id === place.marketId) || (place.city && m.city.toLowerCase() === place.city.toLowerCase()));
  if (market?.frenchFirst != null) return market.frenchFirst;
  const province = place.province ?? market?.province;
  return regions.provinces.find(p => p.code === province)?.frenchFirst ?? false;
}

/**
 * Whether the visitor's place (their delivery location, else the default province) is French-first. Works outside
 * the location provider too (registration reads the location saved in the browser).
 */
export function useFrenchFirst(): boolean {
  const regions = useRegions();
  const location = useKnownLocation();
  if (location?.status === 'locating') return false;
  return frenchFirstPlace(regions, { province: location?.province ?? regions?.defaultProvince, marketId: location?.marketId, city: location?.city });
}

/**
 * Switches the page to French when the visitor's place is French-first and they haven't chosen a language (no
 * `nl.locale` cookie, no `?lang=`). The switch is remembered like the FR/EN toggle, which always wins afterwards.
 */
function FrenchFirstLocale() {
  const first = useFrenchFirst();
  const { locale, setLocale } = useLocale();
  useEffect(() => {
    if (!first || locale === 'fr') return;
    if (chosenLocale(readCookie(LOCALE_COOKIE), window.location.href)) return;
    setLocale('fr');
  }, [first]); // eslint-disable-line react-hooks/exhaustive-deps -- once per place
  return null;
}

const useLawT = defineMessages({
  en: { law_pipeda: 'PIPEDA', law_pipa: 'PIPEDA and {province} PIPA', law_law25: 'PIPEDA and Law 25 ({province})', law_any: 'PIPEDA and provincial privacy law' },
  fr: { law_pipeda: 'la LPRPDE', law_pipa: 'la LPRPDE et la PIPA {provinceOf}', law_law25: 'la LPRPDE et la Loi 25 ({province})', law_any: 'la LPRPDE et la loi provinciale sur la protection des renseignements personnels' },
});

/**
 * Fills {province}, {provinceIn}, {provinceOf} and {privacyLaw} for the page — the province of the visitor's location,
 * else the configured default province — and makes the visitor's market's zone the page's ({@link useZone}). Before the region model answers, the law reads "PIPEDA and provincial privacy
 * law" and the province is empty.
 */
export function VisitorPlace({ children }: { children: ReactNode }) {
  const regions = useRegions();
  const { location } = useDeliveryLocation();
  const t = useLawT();
  const code = (location.status !== 'locating' ? location.province : undefined) ?? regions?.defaultProvince;
  const p = code ? regions?.provinces.find(r => r.code === code) : undefined;
  const values = useMemo(() => {
    const province = p?.name ?? '';
    const names = { province, provinceIn: p?.nameIn ?? province, provinceOf: p?.nameOf ?? province };
    const law = !p ? t('law_any')
      : p.privacyLaw === 'qc_law25' ? t('law_law25', names)
      : p.privacyLaw.endsWith('_pipa') ? t('law_pipa', names)
      : t('law_pipeda');
    return { ...names, privacyLaw: law };
  }, [p, t]);
  const city = location.status !== 'locating' ? location.city : undefined;
  return (
    <MessageValues values={values}>
      <FrenchFirstLocale />
      <MarketZone city={city}>{children}</MarketZone>
    </MessageValues>
  );
}
