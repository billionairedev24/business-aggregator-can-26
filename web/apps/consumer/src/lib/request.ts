import { createIsomorphicFn } from '@tanstack/react-start';
import { getCookie, getRequestHeader, getRequestUrl } from '@tanstack/react-start/server';
import type { Locale } from '@northline/ui';
import { countryList, FALLBACK_CONFIG, onRequestHost, type PublicConfig } from './config';
import { LOCALE_COOKIE, pickLocale, readCookie, urlLocale } from './locale';
import type { PageHost } from './pages';

export type { PublicConfig } from './config';

/** The locale of this request: on the server from the cookie / Accept-Language, in the browser from the cookie. */
export const requestLocale = createIsomorphicFn()
  .server((): Locale => pickLocale(getCookie(LOCALE_COOKIE), getRequestHeader('accept-language'), urlLocale(getRequestUrl().href)))
  .client((): Locale => pickLocale(readCookie(LOCALE_COOKIE), navigator.languages?.join(',') || navigator.language, urlLocale(window.location.href)));

/** Which business page this request is on another host (headers set by server/page-hosts.mjs only). */
function pageFromHeaders(): PageHost | null {
  try {
    const mode = getRequestHeader('x-nl-page-mode');
    const host = getRequestHeader('x-nl-page-host');
    const slug = getRequestHeader('x-nl-page-slug');
    return (mode === 'custom' || mode === 'pages') && host && slug ? { mode, host, slug } : null;
  } catch {
    return null; // outside a request (nothing to serve on another host)
  }
}

/**
 * The visitor's address chain for the api's per-client search limit (S-44: "the SSR server should add the browser's
 * address to X-Forwarded-For", otherwise every server-rendered search counts against the SSR pod). Set by
 * server/node-server.mjs only (`x-nl-forwarded-for`; a browser's own is dropped); the browser sends nothing — the
 * consumer-bff sees it directly.
 */
export const forwardedFor = createIsomorphicFn()
  .server((): string | undefined => {
    try { return getRequestHeader('x-nl-forwarded-for') || undefined; } catch { return undefined; }
  })
  .client((): string | undefined => undefined);

/** S-192: the edge's visitor location headers (Cloudflare's, and the generic X-Geo-* set) the api's detector reads. */
const VISITOR_HEADERS = [
  'cf-ipcountry', 'cf-ipcity', 'cf-region', 'cf-region-code', 'cf-postal-code', 'cf-iplatitude', 'cf-iplongitude',
  'x-geo-country', 'x-geo-region', 'x-geo-region-code', 'x-geo-city', 'x-geo-postal-code', 'x-geo-latitude', 'x-geo-longitude',
] as const;

/**
 * S-192: what the server render forwards to `GET /api/v1/geo/visitor` so the api can tell where this visitor is — the
 * edge's visitor location headers and the address chain (for a GeoIP database). In the browser nothing: its own call
 * through the consumer-bff carries them.
 */
export const visitorHeaders = createIsomorphicFn()
  .server((): Record<string, string> => {
    const out: Record<string, string> = {};
    try {
      for (const name of VISITOR_HEADERS) { const v = getRequestHeader(name); if (v) out[name] = v; }
      const xff = getRequestHeader('x-nl-forwarded-for');
      if (xff) out['x-forwarded-for'] = xff;
    } catch { /* outside a request */ }
    return out;
  })
  .client((): Record<string, string> => ({}));

/** S-192: the saved place's cookie (`nl.loc`), read by the server render and the browser alike. */
export const requestPlaceCookie = createIsomorphicFn()
  .server((): string | undefined => { try { return getCookie('nl.loc') || undefined; } catch { return undefined; } })
  .client((): string | undefined => {
    const hit = document.cookie.split('; ').find(c => c.startsWith('nl.loc='));
    if (!hit) return undefined;
    try { return decodeURIComponent(hit.slice('nl.loc='.length)); } catch { return undefined; }
  });

/**
 * This page's Content-Security-Policy nonce (S104-09). On the server: the one node-server.mjs chose for this request
 * (`x-nl-csp-nonce`, never a client's — the server replaces it); the router puts it on every inline script it and React
 * render. In the browser: read back from a server-rendered script (`.nonce`; browsers hide the attribute), so scripts
 * the router inserts after hydration carry it too. Undefined in the dev server and tests (no CSP there).
 */
export const cspNonce = createIsomorphicFn()
  .server((): string | undefined => {
    try { return getRequestHeader('x-nl-csp-nonce') || undefined; } catch { return undefined; }
  })
  .client((): string | undefined => document.querySelector<HTMLScriptElement>('script[nonce]')?.nonce || undefined);

/** The Host of this request when the dev server listens for phones (NL_DEV_HOST), else none. */
function devRequestHost(): string | undefined {
  if (!process.env.NL_DEV_HOST) return undefined;
  try { return getRequestHeader('host') || undefined; } catch { return undefined; }
}

export const publicConfig = createIsomorphicFn()
  .server((): PublicConfig => ({
    authOrigin: onRequestHost(process.env.NL_AUTH_ORIGIN ?? FALLBACK_CONFIG.authOrigin, devRequestHost()),
    siteOrigin: onRequestHost((process.env.NL_SITE_ORIGIN ?? FALLBACK_CONFIG.siteOrigin).replace(/\/$/, ''), devRequestHost()),
    legalEntity: process.env.NL_LEGAL_ENTITY?.trim() || FALLBACK_CONFIG.legalEntity,
    studioOrigin: (process.env.NL_STUDIO_ORIGIN ?? FALLBACK_CONFIG.studioOrigin).replace(/\/$/, ''),
    supportEmail: process.env.NL_SUPPORT_EMAIL?.trim() || null,
    photosBaseUrl: process.env.NL_PHOTOS_BASE_URL?.trim() || null,
    country: countryList(process.env.NL_SITE_COUNTRY)?.[0] ?? null,
    commonCountries: countryList(process.env.NL_COMMON_COUNTRIES),
    page: pageFromHeaders(),
  }))
  .client((): PublicConfig => ({ ...FALLBACK_CONFIG, ...window.__NL_CONFIG__ }));
