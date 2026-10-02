import { authFixtures, newAuthState, type AuthFixtureState } from './auth';
import { lower, type FixtureArea, type FixtureContext, type FixtureRequest } from './context';
import { geoFixtures } from './geo';
import { newServicesState, servicesFixtures } from './services';
import { newShopState, shopFixtures } from './shop';

/**
 * An in-memory northline-auth + api for the web smoke test, demos and screen tests (`EXPO_PUBLIC_FIXTURES=1`; never
 * in a production build). It answers like the real servers — DPoP token answers with a nonce, problem details,
 * 404 / 422 / 429 — so the app's whole stack (session, proofs, queries) runs against it. Places and people are made
 * up. Each journey adds its area in its own file (fixtures/<area>.ts) and one line in `areas` below.
 */
export interface FixtureOptions {
  now?: () => number;
}

export function createFixtureServer(options: FixtureOptions = {}) {
  const now = options.now ?? Date.now;
  const ctx: FixtureContext = {
    now,
    answer: (status, body, headers = {}) =>
      new Response(body === undefined || status === 204 ? null : JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json', date: new Date(now()).toUTCString(), ...headers },
      }),
    redirect: (location) => new Response(null, { status: 302, headers: { location, date: new Date(now()).toUTCString() } }),
  };
  const auth: AuthFixtureState = newAuthState();
  const geo = { waitlist: [] as Array<{ regionId: string; email?: string }> };
  const calls: Array<{ method: string; path: string; signed: boolean; guest?: string }> = [];
  const shop = newShopState();
  const servicesState = newServicesState(now);
  const areas: FixtureArea[] = [authFixtures(ctx, auth), geoFixtures(ctx, geo), shopFixtures(ctx, shop), servicesFixtures(ctx, servicesState)];

  async function handle(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
    const url = new URL(String(input), 'http://fixtures.invalid');
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = lower(init.headers);
    let body: Record<string, unknown> = {};
    if (typeof init.body === 'string' && init.body) {
      body = (headers['content-type'] ?? '').includes('x-www-form-urlencoded')
        ? Object.fromEntries(new URLSearchParams(init.body))
        : (JSON.parse(init.body) as Record<string, unknown>);
    }
    const path = url.pathname.includes('/api/v1') ? url.pathname.replace(/^.*\/api\/v1/, '') : url.pathname;
    calls.push({ method, path, signed: (headers.authorization ?? '').startsWith('DPoP '), guest: headers['x-northline-guest'] });
    const req: FixtureRequest = { method, url, path, headers, body };
    for (const area of areas) {
      const out = await area(req);
      if (out) return out;
    }
    return ctx.answer(404, { code: 'not_found', detail: `No fixture for ${method} ${path}` });
  }

  return {
    fetch: handle as typeof fetch,
    auth,
    geo,
    shop,
    services: servicesState,
    calls,
  };
}

export type FixtureServer = ReturnType<typeof createFixtureServer>;
