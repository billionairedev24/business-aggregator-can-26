import type { Locale } from '@northline/ui';

/**
 * The consumer's language: the FR/EN toggle writes the `nl.locale` cookie, so the server renders the next page in it
 * (SEO pages are rendered on the server). First visit: the browser's Accept-Language (fr* → French), else English.
 * A `?lang=en|fr` in the URL wins (S-63: each language has its own URL for search engines) and is remembered.
 * S-116 (Loi 96 readiness): the browser "asks for French" when French is its most preferred of the two (q-weights, like
 * the api's MessageCatalogue.prefersFrench); a French-first place switches the page to French later
 * (<FrenchFirstLocale>, features/location/regions.tsx) unless the visitor chose a language.
 */
export const LOCALE_COOKIE = 'nl.locale';

export function pickLocale(cookie: string | undefined | null, acceptLanguage: string | undefined | null, explicit?: string | null): Locale {
  // S-63: `?lang=` names the page's language outright (the hreflang URLs crawlers follow)
  if (explicit === 'fr' || explicit === 'en') return explicit;
  if (cookie === 'fr' || cookie === 'en') return cookie;
  return prefersFrench(acceptLanguage) ? 'fr' : 'en';
}

/** Whether an Accept-Language (or navigator.languages joined) puts French before English; `*` or none = English. */
export function prefersFrench(acceptLanguage: string | undefined | null): boolean {
  const ranges = (acceptLanguage ?? '').split(',').map((part, i) => {
    const [tag = '', ...params] = part.trim().toLowerCase().split(';');
    const q = params.map(p => /^\s*q=([\d.]+)\s*$/.exec(p)?.[1]).find(Boolean);
    return { lang: tag.trim().split('-')[0] ?? '', q: q === undefined ? 1 : Number(q), i };
  }).filter(r => r.lang && r.q > 0 && !Number.isNaN(r.q));
  ranges.sort((a, b) => b.q - a.q || a.i - b.i);
  const first = ranges.find(r => r.lang === 'fr' || r.lang === 'en' || r.lang === '*');
  return first?.lang === 'fr';
}

/** Whether the visitor chose a language: the `nl.locale` cookie or a `?lang=` in the URL (S-116: a French-first
 * place never overrides a choice). */
export function chosenLocale(cookie: string | undefined | null, href: string | undefined): Locale | undefined {
  const explicit = href ? urlLocale(href) : undefined;
  if (explicit) return explicit;
  return cookie === 'fr' || cookie === 'en' ? cookie : undefined;
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
