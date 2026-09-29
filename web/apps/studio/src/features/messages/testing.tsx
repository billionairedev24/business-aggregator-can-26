import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { I18nProvider, type Locale } from '@northline/ui';
import { vi } from 'vitest';

/** Test harness for the messaging / help / reviews screens: a fetch stub keyed by "METHOD path" and a fresh QueryClient. */
export type Route = (body: unknown, init: RequestInit, url: URL) => { status?: number; json?: unknown };
export interface Call { key: string; url: URL; body: unknown }

export function stubFetch(routes: Record<string, Route | unknown>) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://localhost');
    const key = `${init.method ?? 'GET'} ${url.pathname}`;
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ key, url, body });
    const route = routes[key];
    const res = typeof route === 'function' ? (route as Route)(body, init, url) : route === undefined ? { status: 404, json: {} } : { json: route };
    return new Response(res.json === undefined ? '' : JSON.stringify(res.json), { status: res.status ?? 200, headers: { 'content-type': 'application/json' } });
  }));
  return calls;
}

export function renderScreen(ui: ReactNode, locale: Locale = 'en') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, refetchInterval: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={qc}><I18nProvider initial={locale}>{ui}</I18nProvider></QueryClientProvider>);
}

export const M = '01J9ZD3V00000000000000PWM1';
export const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
export const H = 3_600_000;
export const D = 24 * H;
