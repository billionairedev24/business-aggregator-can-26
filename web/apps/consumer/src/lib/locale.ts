import type { Locale } from '@northline/ui';

/**
 * The consumer's language: the FR/EN toggle writes the `nl.locale` cookie, so the server renders the next page in it
 * (SEO pages are rendered on the server). First visit: the browser's Accept-Language (fr* → French), else English.
 * A `?lang=en|fr` in the URL wins (S-63: each language has its own URL for search engines) and is remembered.
 */
export const LOCALE_COOKIE = 'nl.locale';

export function pickLocale(cookie: string | undefined | null, acceptLanguage: string | undefined | null, explicit?: string | null): Locale {
  // S-63: `?lang=` names the page's language outright (the hreflang URLs crawlers follow)
  if (explicit === 'fr' || explicit === 'en') return explicit;
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

/** `?lang=` of a URL (`en` | `fr`), else undefined. */
export function urlLocale(href: string): Locale | undefined {
  try {
    const lang = new URL(href, 'http://x').searchParams.get('lang');
    return lang === 'fr' || lang === 'en' ? lang : undefined;
  } catch {
    return undefined;
  }
}
