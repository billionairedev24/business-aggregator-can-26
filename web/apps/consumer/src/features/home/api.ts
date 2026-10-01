import { queryOptions, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';

/**
 * `GET /api/v1/public/home?city=` (S-46, public): the city's numbers for the Services / Shop / Food chips, businesses
 * per category, open kitchens per cuisine, and "Trusted near you". The same for everyone in a city, so it's fetched
 * once the location is known (in the browser — the server doesn't know where the visitor is).
 */
export const TrustedProvider = z.object({
  merchantId: z.string(),
  name: z.string(),
  slug: z.string().nullish(),
  tier: z.string(),
  brandColor: z.string().nullish(),
  category: z.object({ id: z.string(), name: z.string() }).nullish(),
  rating: z.number(),
  reviews: z.number().int(),
});
export type TrustedProvider = z.infer<typeof TrustedProvider>;

export const HomeSummary = z.object({
  city: z.string(),
  providers: z.number().int(),
  shops: z.number().int(),
  kitchensOpen: z.number().int(),
  categories: z.record(z.string(), z.number().int()),
  cuisines: z.record(z.string(), z.number().int()),
  trusted: z.array(TrustedProvider),
});
export type HomeSummary = z.infer<typeof HomeSummary>;

export const homeQuery = (city: string) => queryOptions({
  queryKey: ['public', 'home', city.toLowerCase()],
  queryFn: () => http(`/api/v1/public/home?city=${encodeURIComponent(city)}`, {}, HomeSummary),
  staleTime: 60_000,
});

export const useHomeSummary = (city: string | undefined) => useQuery({ ...homeQuery(city ?? ''), enabled: !!city });

/**
 * "Your week" (design 06): the signed-in person's orders, bookings and quotes for the next seven days. Contract for
 * the account workstream (S-58; docs/CONSUMER_WEB_PLAN.md § Your week): `GET /api/v1/me/upcoming` →
 * `{ items: [{ id, title, subtitle, state, tone: accent|neutral|accent-2, href }] }`, texts in the caller's language.
 * Until the endpoint exists (404) the section shows its empty line.
 */
export const Upcoming = z.object({
  id: z.string(),
  title: z.string(),
  subtitle: z.string().nullish(),
  state: z.string(),
  tone: z.enum(['accent', 'neutral', 'accent-2']).catch('neutral'),
  href: z.string(),
});
export type Upcoming = z.infer<typeof Upcoming>;

export const upcomingQuery = queryOptions({
  queryKey: ['me', 'upcoming'],
  queryFn: async (): Promise<Upcoming[]> => {
    try { return (await http('/api/v1/me/upcoming', {}, z.object({ items: z.array(Upcoming) }))).items; } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return [];
      throw e;
    }
  },
  staleTime: 60_000,
});

export const useUpcoming = (enabled: boolean) => useQuery({ ...upcomingQuery, enabled });
