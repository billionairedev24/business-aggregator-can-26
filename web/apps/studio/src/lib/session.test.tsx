import { renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { sessionQuery, useSignOut } from './session';

describe('useSignOut ("Sign out", onboarding "Not you? Sign out")', () => {
  const realLocation = window.location;
  afterEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(window, 'location', { value: realLocation, configurable: true, writable: true });
  });

  it('ends the BFF session and the auth-server session, clears the cache and shows /sign-in', async () => {
    const fetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    const assign = vi.fn();
    Object.defineProperty(window, 'location', { value: { ...realLocation, assign }, configurable: true, writable: true });
    document.cookie = 'XSRF-TOKEN=abc123';

    const qc = new QueryClient();
    qc.setQueryData(sessionQuery.queryKey, { user: { id: 'u', firstName: 'Ravi', lastName: 'Sandhu', initials: 'RS' }, acr: 'mfa' } as never);
    qc.setQueryData(['businesses'], [{ id: 'b' }]);
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => useSignOut(), { wrapper });

    await result.current();

    const bff = fetch.mock.calls.find(([u]) => u === '/bff/logout') as unknown as [string, RequestInit];
    expect(bff[1].method).toBe('POST');
    expect((bff[1].headers as Record<string, string>)['x-xsrf-token']).toBe('abc123');
    expect(fetch.mock.calls.some(([u]) => String(u).endsWith('/api/auth/sign-out'))).toBe(true);
    expect(qc.getQueryData(sessionQuery.queryKey)).toBeNull();
    expect(qc.getQueryData(['businesses'])).toBeUndefined();
    expect(assign).toHaveBeenCalledWith('/sign-in');
  });

  it('still signs out locally when the servers are unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline'); }));
    const assign = vi.fn();
    Object.defineProperty(window, 'location', { value: { ...realLocation, assign }, configurable: true, writable: true });
    const qc = new QueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => useSignOut(), { wrapper });
    await result.current();
    expect(assign).toHaveBeenCalledWith('/sign-in');
  });
});
