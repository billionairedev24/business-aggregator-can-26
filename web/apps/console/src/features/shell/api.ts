import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http as clientHttp } from '@northline/client';
import { http } from '../../lib/http';
import { applyRole } from './roleView';
import type { ScreenKey } from './screens';

/** Console roles in design order (design 03 `ROLES`; api `StaffRole`; support lead: S-83; privacy officer: S-105). */
export const ROLE_CODES = ['admin', 'trust_safety', 'dispatch', 'finance', 'support', 'support_lead', 'analyst', 'privacy'] as const;
export const RoleCode = z.enum(ROLE_CODES);
export type RoleCode = z.infer<typeof RoleCode>;

/** One held role: the screens it opens and what it may change (api `StaffProfile.RoleGrant`). */
export const RoleGrant = z.object({ role: RoleCode, screens: z.array(z.string()), actions: z.array(z.string()) });
export type RoleGrant = z.infer<typeof RoleGrant>;
export const Me = z.object({ userId: z.string(), roles: z.array(RoleGrant) });
export type Me = z.infer<typeof Me>;

/**
 * `GET /api/v1/console/me` — sent without the role view header (the stored view may name a role taken away since);
 * the held roles decide the views the role switch offers.
 */
export const meQuery = queryOptions({
  queryKey: ['console', 'me'],
  queryFn: () => clientHttp('/api/v1/console/me', {}, Me),
  staleTime: 5 * 60_000,
});

/** Whether a role grant (none = no console role) opens a screen. */
export function opens(grant: RoleGrant | undefined, screen: ScreenKey): boolean {
  return screen === 'profile' || screen === 'oncall' || (grant?.screens.includes(screen) ?? false);
}

/**
 * "Switch role view": records the switch (audit log, `POST /api/v1/console/me/role-view`), then narrows every later
 * api call to that role and reloads what the console shows.
 */
export function useSwitchRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (role: RoleCode) => http('/api/v1/console/me/role-view', { method: 'POST', body: { role } }, RoleGrant),
    onSuccess: grant => {
      applyRole(grant.role);
      void qc.invalidateQueries({ predicate: q => q.queryKey[0] !== 'session' && !(q.queryKey[0] === 'console' && q.queryKey[1] === 'me') });
    },
  });
}

// ── region model (S-134): the top bar's "Ops · {live provinces} + {pilot} pilot" ──────────────────────────────────

const LaunchStatus = z.enum(['off', 'waitlist', 'pilot', 'live']);
export const Regions = z.object({
  platformTimeZone: z.string(),
  provinces: z.array(z.object({ code: z.string(), name: z.string(), status: LaunchStatus })),
  markets: z.array(z.object({ id: z.string(), city: z.string(), province: z.string(), status: LaunchStatus })),
});
export type Regions = z.infer<typeof Regions>;

export const regionsQuery = (lang: 'en' | 'fr') => queryOptions({
  queryKey: ['regions', lang],
  queryFn: () => http(`/api/v1/geo/regions?lang=${lang}`, {}, Regions),
  staleTime: 10 * 60_000,
});
export const useRegions = (lang: 'en' | 'fr') => useQuery(regionsQuery(lang));
