import { isRegions, regionsBody } from './regions';
import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider, type Locale } from '@northline/ui';
import { vi } from 'vitest';

/** Renders a feature screen with a fresh QueryClient and the i18n provider (operations workstream tests). */
export function renderWithProviders(ui: ReactNode, locale: Locale = 'en') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return { client, ...render(<I18nProvider initial={locale}><QueryClientProvider client={client}>{ui}</QueryClientProvider></I18nProvider>) };
}

type Handler = (url: string, init: RequestInit) => unknown;

/** fetch stub: first matching `METHOD path-prefix` handler answers with JSON (a thrown `{status, body}` becomes that response). */
export function mockFetch(routes: Record<string, Handler>) {
  const calls: { method: string; url: string; body: unknown }[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    const method = init.method ?? 'GET';
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ method, url, body });
    const key = Object.keys(routes).find(k => { const [m, p] = k.split(' '); return m === method && url.startsWith(p!); });
    // the region model (S-134) answers like the launch configuration unless the test mocks it
    if (!key && isRegions(url)) return new Response(JSON.stringify(regionsBody(url)), { status: 200, headers: { 'content-type': 'application/json' } });
    if (!key) return new Response(JSON.stringify({ detail: 'not mocked' }), { status: 404 });
    try {
      const data = routes[key]!(url, init);
      return new Response(data === undefined ? '' : JSON.stringify(data), { status: data === undefined ? 204 : 200, headers: { 'content-type': 'application/json' } });
    } catch (e) {
      const err = e as { status: number; body: unknown };
      return new Response(JSON.stringify(err.body), { status: err.status });
    }
  });
  vi.stubGlobal('fetch', fn);
  return calls;
}
