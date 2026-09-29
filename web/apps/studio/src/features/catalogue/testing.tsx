import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { I18nProvider } from '@northline/ui';
import { vi } from 'vitest';

/** Test harness for catalogue screens: fresh QueryClient, English, and a fetch stub keyed by "METHOD path". */
export type Route = (body: unknown, init: RequestInit) => { status?: number; json?: unknown };
export function stubFetch(routes: Record<string, Route | unknown>) {
  const calls: { key: string; body: unknown }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://localhost');
    const key = `${init.method ?? 'GET'} ${url.pathname}`;
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ key, body });
    const route = routes[key];
    const res = typeof route === 'function' ? (route as Route)(body, init) : route === undefined ? { status: 404, json: {} } : { json: route };
    return new Response(res.json === undefined ? '' : JSON.stringify(res.json), { status: res.status ?? 200, headers: { 'content-type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}

export function renderScreen(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={qc}><I18nProvider initial="en">{ui}</I18nProvider></QueryClientProvider>);
}

export const M = '01J9ZD3V00000000000000PWP1';
