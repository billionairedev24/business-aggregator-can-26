import type { Locale } from '@northline/ui';

/**
 * Per-route SEO tags (S-63): title, description, canonical, hreflang en/fr, Open Graph and JSON-LD, for a route's
 * `head()`. The language of a page is the `nl.locale` cookie / Accept-Language (S-45) or an explicit `?lang=` — the
 * URL crawlers get for each language: English is the plain URL (also `x-default`), French adds `lang=fr`.
 */

export const LANG_PARAM = 'lang';

export interface SeoContext { locale: Locale; config: { siteOrigin: string } }

export interface SeoInput {
  title: string;
  description?: string | null;
  /** The page's path on `origin` (no query): `/providers/glenmore`, `/` on a custom domain. */
  path: string;
  /** Query parameters that are part of the page's identity (`market`); undefined values are left out. */
  query?: Record<string, string | undefined>;
  /** Default: the site (`NL_SITE_ORIGIN`); a business page with its own domain passes that. */
  origin?: string;
  /** Pages crawlers should not keep (personal, transactional, endless). No canonical or hreflang then. */
  noindex?: boolean;
  /** Open Graph type: `website` (default), `product`, `business.business`, `restaurant.restaurant`. */
  type?: string;
  /** Absolute or site-relative image. */
  image?: string | null;
  /** schema.org objects (`@context` added). */
  jsonLd?: readonly object[];
}

type Meta = Record<string, string>;
type Link = Record<string, string>;
interface Script { type: string; children: string }

/** `origin + path ? query` with `lang=fr` for French; the parameters sorted so one page has one URL. */
export function pageUrl(origin: string, path: string, query: Record<string, string | undefined> = {}, locale: Locale = 'en'): string {
  const params = Object.entries({ ...query, [LANG_PARAM]: locale === 'fr' ? 'fr' : undefined })
    .filter((e): e is [string, string] => e[1] !== undefined && e[1] !== '')
    .sort(([a], [b]) => a.localeCompare(b));
  const qs = new URLSearchParams(params).toString();
  return `${origin.replace(/\/$/, '')}${path}${qs ? `?${qs}` : ''}`;
}

/** JSON for a <script> element: `<` escaped so text can never close it. */
export const scriptJson = (value: unknown) => JSON.stringify(value).replace(/</g, '\\u003c');

export function seo(context: SeoContext, input: SeoInput): { meta: Meta[]; links: Link[]; scripts: Script[] } {
  const origin = input.origin ?? context.config.siteOrigin;
  const url = pageUrl(origin, input.path, input.query, context.locale);
  const absolute = (src: string) => (/^https?:\/\//.test(src) ? src : `${context.config.siteOrigin.replace(/\/$/, '')}${src}`);
  const description = input.description?.replace(/\s+/g, ' ').trim().slice(0, 300) || undefined;
  const meta: Meta[] = [
    { title: input.title },
    ...(description ? [{ name: 'description', content: description }] : []),
    ...(input.noindex ? [{ name: 'robots', content: 'noindex' }] : []),
    { property: 'og:site_name', content: 'Northline' },
    { property: 'og:type', content: input.type ?? 'website' },
    { property: 'og:title', content: input.title },
    ...(description ? [{ property: 'og:description', content: description }] : []),
    { property: 'og:locale', content: context.locale === 'fr' ? 'fr_CA' : 'en_CA' },
    { property: 'og:locale:alternate', content: context.locale === 'fr' ? 'en_CA' : 'fr_CA' },
    ...(input.noindex ? [] : [{ property: 'og:url', content: url }]),
    ...(input.image ? [{ property: 'og:image', content: absolute(input.image) }] : []),
    { name: 'twitter:card', content: input.image ? 'summary_large_image' : 'summary' },
  ];
  const links: Link[] = input.noindex ? [] : [
    { rel: 'canonical', href: url },
    { rel: 'alternate', hrefLang: 'en-CA', href: pageUrl(origin, input.path, input.query, 'en') },
    { rel: 'alternate', hrefLang: 'fr-CA', href: pageUrl(origin, input.path, input.query, 'fr') },
    { rel: 'alternate', hrefLang: 'x-default', href: pageUrl(origin, input.path, input.query, 'en') },
  ];
  const scripts = (input.jsonLd ?? []).map(data => ({
    type: 'application/ld+json',
    children: scriptJson({ '@context': 'https://schema.org', ...data }),
  }));
  return { meta, links, scripts };
}
