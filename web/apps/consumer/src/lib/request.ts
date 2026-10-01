import { createIsomorphicFn } from '@tanstack/react-start';
import { getCookie, getRequestHeader, getRequestUrl } from '@tanstack/react-start/server';
import type { Locale } from '@northline/ui';
import { FALLBACK_CONFIG, type PublicConfig } from './config';
import { LOCALE_COOKIE, pickLocale, readCookie, urlLocale } from './locale';
import type { PageHost } from './pages';

export type { PublicConfig } from './config';

/** The locale of this request: on the server from the cookie / Accept-Language, in the browser from the cookie. */
export const requestLocale = createIsomorphicFn()
  .server((): Locale => pickLocale(getCookie(LOCALE_COOKIE), getRequestHeader('accept-language'), urlLocale(getRequestUrl().href)))
  .client((): Locale => pickLocale(readCookie(LOCALE_COOKIE), navigator.language, urlLocale(window.location.href)));

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

export const publicConfig = createIsomorphicFn()
  .server((): PublicConfig => ({
    authOrigin: process.env.NL_AUTH_ORIGIN ?? FALLBACK_CONFIG.authOrigin,
    siteOrigin: (process.env.NL_SITE_ORIGIN ?? FALLBACK_CONFIG.siteOrigin).replace(/\/$/, ''),
    legalEntity: process.env.NL_LEGAL_ENTITY?.trim() || FALLBACK_CONFIG.legalEntity,
    page: pageFromHeaders(),
  }))
  .client((): PublicConfig => ({ ...FALLBACK_CONFIG, ...window.__NL_CONFIG__ }));
