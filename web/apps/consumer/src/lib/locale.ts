import type { Locale } from '@northline/ui';

/**
 * The consumer's language: the FR/EN toggle writes the `nl.locale` cookie, so the server renders the next page in it
 * (SEO pages are rendered on the server). First visit: the browser's Accept-Language (fr* → French), else English.
 */
export const LOCALE_COOKIE = 'nl.locale';

export function pickLocale(cookie: string | undefined | null, acceptLanguage: string | undefined | null): Locale {
  if (cookie === 'fr' || cookie === 'en') return cookie;
  const first = (acceptLanguage ?? '').split(',')[0]?.trim().toLowerCase() ?? '';
  return first.startsWith('fr') ? 'fr' : 'en';
}

export const htmlLang = (l: Locale) => (l === 'fr' ? 'fr-CA' : 'en-CA');

/** Reads a cookie in the browser. */
export function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined;
  const hit = document.cookie.split('; ').find(c => c.startsWith(`${name}=`));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : undefined;
}

/** Remembers the choice for a year (not HttpOnly: the page reads it; nothing secret). */
export function persistLocale(locale: Locale) {
  if (typeof document === 'undefined') return;
  const secure = location.protocol === 'https:' ? '; secure' : '';
  document.cookie = `${LOCALE_COOKIE}=${locale}; path=/; max-age=31536000; samesite=lax${secure}`;
}
