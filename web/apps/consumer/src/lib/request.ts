import { createIsomorphicFn } from '@tanstack/react-start';
import { getCookie, getRequestHeader } from '@tanstack/react-start/server';
import type { Locale } from '@northline/ui';
import { LOCALE_COOKIE, pickLocale, readCookie } from './locale';

/** The locale of this request: on the server from the cookie / Accept-Language, in the browser from the cookie. */
export const requestLocale = createIsomorphicFn()
  .server((): Locale => pickLocale(getCookie(LOCALE_COOKIE), getRequestHeader('accept-language')))
  .client((): Locale => pickLocale(readCookie(LOCALE_COOKIE), navigator.language));

/** Public runtime configuration (one image serves every environment; the chart sets NL_AUTH_ORIGIN). */
export interface PublicConfig { authOrigin: string }
declare global { interface Window { __NL_CONFIG__?: PublicConfig } }

export const publicConfig = createIsomorphicFn()
  .server((): PublicConfig => ({ authOrigin: process.env.NL_AUTH_ORIGIN ?? 'http://localhost:9000' }))
  .client((): PublicConfig => window.__NL_CONFIG__ ?? { authOrigin: 'http://localhost:9000' });
