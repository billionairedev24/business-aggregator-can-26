import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { configureAuthOrigin } from '@northline/auth-kit';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { I18nProvider, SiteLinkProvider, type Locale } from '@northline/ui';
import { vi } from 'vitest';
import { ConsumerLayout } from '../features/shell/ConsumerLayout';
import { NotFound } from '../features/shell/NotFound';
import { RouteError } from '../features/shell/RouteError';
import { ScreenPending } from '../features/shell/ScreenPending';
import { SCREENS, type ScreenKey } from '../features/shell/screens';
import { persistLocale } from '../lib/locale';
import { RouterSiteLink } from '../lib/SiteLinkAdapter';

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

/**
 * The consumer shell around every screen route (each rendering its <ScreenPending>), at `path`, without the SSR
 * document — the same providers as routes/__root.tsx.
 */
export function renderApp(path: string, { locale = 'en' as Locale, geolocation = null as Geolocation | null, routes: screens = {} as Partial<Record<ScreenKey, () => ReactNode>> } = {}) {
  configureAuthOrigin('http://auth.test');
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const root = createRootRoute({
    component: () => (
      <I18nProvider initial={locale} onChange={persistLocale}>
        <SiteLinkProvider value={RouterSiteLink}>
          <ConsumerLayout geolocation={geolocation}><Outlet /></ConsumerLayout>
        </SiteLinkProvider>
      </I18nProvider>
    ),
  });
  const routes = (Object.entries(SCREENS) as [ScreenKey, (typeof SCREENS)[ScreenKey]][]).map(([key, s]) =>
    createRoute({ getParentRoute: () => root, path: s.path, component: screens[key] ?? (() => <ScreenPending screen={key} />) }));
  const router = createRouter({ routeTree: root.addChildren(routes), history: createMemoryHistory({ initialEntries: [path] }), defaultErrorComponent: RouteError, defaultNotFoundComponent: NotFound });
  const result = render(<QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>);
  return { ...result, router, queryClient };
}
