import { render } from '@testing-library/react';
import { configureAuthOrigin } from '@northline/auth-kit';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router';
import { I18nProvider, type Locale } from '@northline/ui';
import { vi } from 'vitest';
import { routeTree } from '../routeTree.gen';
import { applyRole } from '../features/shell/roleView';
import type { RoleCode } from '../features/shell/api';

export interface Call { method: string; url: string; body: unknown; headers: Record<string, string> }
type Handler = (call: Call) => { status?: number; body?: unknown } | undefined;

/** Stubs `fetch` (first match wins; unhandled requests answer 404) and records every call. */
export function mockFetch(handler: Handler) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const raw = init?.body;
    const call = { method: init?.method ?? 'GET', url, body: typeof raw === 'string' ? JSON.parse(raw) : raw, headers: (init?.headers ?? {}) as Record<string, string> };
    calls.push(call);
    const res = handler(call) ?? { status: 404, body: { detail: 'not found' } };
    return new Response(res.body === undefined ? '' : JSON.stringify(res.body), { status: res.status ?? 200, headers: { 'content-type': 'application/json' } });
  }));
  return calls;
}

/** Test data: the region model with one live province and one pilot (S-134; names come from here, never from code). */
export const REGIONS = {
  platformTimeZone: 'America/Edmonton',
  provinces: [
    { code: 'AB', name: 'Alberta', status: 'live' },
    { code: 'BC', name: 'British Columbia', status: 'pilot' },
    { code: 'ON', name: 'Ontario', status: 'waitlist' },
  ],
  markets: [{ id: 'calgary', city: 'Calgary', province: 'AB', status: 'live' }],
};

export const SESSION = { user: { id: '01J9ZD3V00000000000000PNA1', firstName: 'Priya', lastName: 'Natarajan', initials: 'PN', locale: 'en-CA' }, acr: 'mfa' };

const ALL = ['overview', 'orders', 'disputes', 'delivery', 'sellers', 'verify', 'pilot', 'vetting', 'trust', 'taxonomy', 'support', 'regions', 'finance', 'reports', 'privacy', 'uat', 'api', 'team', 'profile', 'oncall'];
/** The api's grants (StaffRole), for `GET /api/v1/console/me`. */
export const GRANTS: Record<RoleCode, { role: RoleCode; screens: string[]; actions: string[] }> = {
  admin: { role: 'admin', screens: ALL, actions: ['suspend', 'decide', 'refund', 'province', 'payouts', 'keys', 'verify', 'vet', 'dispatch', 'support', 'macros', 'privacy', 'onboard', 'uat'] },
  trust_safety: { role: 'trust_safety', screens: ['overview', 'disputes', 'sellers', 'verify', 'vetting', 'trust', 'support', 'team', 'pilot'], actions: ['suspend', 'decide', 'verify', 'vet', 'support'] },
  dispatch: { role: 'dispatch', screens: ['overview', 'orders', 'delivery', 'support'], actions: ['dispatch'] },
  finance: { role: 'finance', screens: ['overview', 'disputes', 'finance', 'reports', 'team'], actions: ['refund', 'payouts'] },
  support: { role: 'support', screens: ['overview', 'orders', 'disputes', 'sellers', 'support', 'uat'], actions: ['support', 'uat'] },
  support_lead: { role: 'support_lead', screens: ['overview', 'orders', 'disputes', 'sellers', 'support', 'privacy', 'uat'], actions: ['support', 'macros', 'privacy', 'onboard', 'uat'] },
  analyst: { role: 'analyst', screens: ['overview', 'reports'], actions: [] },
  privacy: { role: 'privacy', screens: ['overview', 'privacy', 'support'], actions: ['privacy'] },
  merchant_success: { role: 'merchant_success', screens: ['overview', 'pilot', 'sellers', 'support', 'uat'], actions: ['onboard', 'uat'] },
};

/** A signed-in staff member holding `roles`; `extra` answers anything else first. */
export function staffApi(roles: RoleCode[], extra?: Handler) {
  return mockFetch(call => {
    const hit = extra?.(call);
    if (hit) return hit;
    if (call.url.endsWith('/bff/session')) return { body: SESSION };
    if (call.url.endsWith('/api/v1/console/me')) return { body: { userId: SESSION.user.id, roles: roles.map(r => GRANTS[r]) } };
    if (call.url.includes('/api/v1/console/me/role-view')) return { body: GRANTS[(call.body as { role: RoleCode }).role] };
    if (call.url.includes('/api/v1/geo/regions')) return { body: REGIONS };
    return undefined;
  });
}

/** The whole console (routes/, the real route tree) at `path`, in a memory history. */
export function renderConsole(path: string, { locale = 'en' as Locale } = {}) {
  configureAuthOrigin('http://auth.test');
  applyRole(undefined);
  try { localStorage.clear(); } catch { /* ignore */ }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createRouter({ routeTree, context: { queryClient }, history: createMemoryHistory({ initialEntries: [path] }) });
  const result = render(
    <I18nProvider initial={locale}>
      <QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>
    </I18nProvider>,
  );
  return { ...result, router, queryClient };
}
