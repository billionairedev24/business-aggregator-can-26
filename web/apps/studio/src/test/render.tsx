import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider } from '@northline/ui';
import { vi } from 'vitest';

export interface Call { method: string; url: string; body: unknown }
type Handler = (call: Call) => { status?: number; body?: unknown } | undefined;

/**
 * Stubs `fetch` with `handler` (first match wins; unhandled requests answer 404) and records every call.
 * Bodies are parsed as JSON when possible (FormData is passed through).
 */
export function mockFetch(handler: Handler) {
  const calls: Call[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const raw = init?.body;
    const body = typeof raw === 'string' ? JSON.parse(raw) : raw;
    const call = { method: init?.method ?? 'GET', url, body };
    calls.push(call);
    const res = handler(call) ?? { status: 404, body: { detail: 'not found' } };
    return new Response(res.body === undefined ? '' : JSON.stringify(res.body), { status: res.status ?? 200, headers: { 'content-type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fn);
  return calls;
}

export function renderWithProviders(ui: ReactElement, { locale = 'en' as 'en' | 'fr' } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const result = render(<I18nProvider initial={locale}><QueryClientProvider client={client}>{ui}</QueryClientProvider></I18nProvider>);
  return { ...result, client };
}
