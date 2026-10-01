// Storefront pages on other hosts (S-54; S-31 routing contract, docs/runbooks/custom-domains.md § Routing): the same
// app serves a business's public page on `pages.<zone>/<slug>` and on the business's own domain (`book.example.ca/`).
//
//   site host (NL_SITE_ORIGIN)   → the whole app, as usual
//   pages host (NL_PAGES_HOST)   → /<slug> is that business's page; anything else goes to the site
//   any other host               → a merchant's own domain: GET {NL_BFF_URL}/api/v1/public/storefronts/by-host?host=
//                                  names the page (cached a minute, as the endpoint's Cache-Control says); / is the
//                                  page, anything else goes to the site; an unknown host is a 404
//
// Only business pages are served this way for now (store and menu pages are S-49 / S-57 work): other page kinds go to
// the site's home. The request reaches the app with `x-nl-page-*` headers (the browser's own are dropped), which the
// router uses to map the host's path onto /providers/<slug> (src/lib/pages.ts). Without NL_PAGES_HOST (local
// development) every host is the site.

export const PAGE_HEADERS = ['x-nl-page-mode', 'x-nl-page-host', 'x-nl-page-slug'];

const SLUG = /^[a-z0-9-]{3,40}$/;

/** `Host` without port or trailing dot, lower case. */
export function normalizeHost(value) {
  if (!value) return '';
  let host = String(value).trim().toLowerCase();
  if (host.startsWith('[')) return host.slice(0, host.indexOf(']') + 1); // IPv6 literal
  host = host.replace(/:\d+$/, '');
  return host.endsWith('.') ? host.slice(0, -1) : host;
}

/**
 * @param {{ siteOrigin?: string, pagesHost?: string, bffUrl?: string, fetch?: typeof fetch, ttlMs?: number, now?: () => number }} options
 */
export function createPageRouter({ siteOrigin = '', pagesHost = '', bffUrl = 'http://localhost:8081', fetch: fetcher = globalThis.fetch, ttlMs = 60_000, now = Date.now } = {}) {
  const site = siteOrigin.replace(/\/$/, '');
  const siteHost = site ? normalizeHost(new URL(site).host) : '';
  const pages = normalizeHost(pagesHost);
  const cache = new Map();

  async function byHost(host) {
    const hit = cache.get(host);
    if (hit && hit.until > now()) return hit.page;
    let page = null;
    try {
      const res = await fetcher(`${bffUrl.replace(/\/$/, '')}/api/v1/public/storefronts/by-host?host=${encodeURIComponent(host)}`, { headers: { accept: 'application/json' } });
      if (res.ok) {
        const body = await res.json();
        page = { slug: String(body.slug), kind: String(body.pageKind) };
      } else if (res.status !== 404) {
        return { error: true };
      }
    } catch {
      return { error: true };
    }
    cache.set(host, { page, until: now() + ttlMs });
    if (cache.size > 5_000) cache.delete(cache.keys().next().value);
    return page;
  }

  const toSite = path => ({ type: 'redirect', location: `${site}${path}` });

  /**
   * @returns {Promise<{ type: 'app', page?: { mode: 'custom' | 'pages', host: string, slug: string } } | { type: 'redirect', location: string } | { type: 'notFound' } | { type: 'unavailable' }>}
   */
  return async function route(rawHost, pathname, search = '') {
    const host = normalizeHost(rawHost);
    if (!pages || !site || !host || host === siteHost || host === 'localhost' || host === '127.0.0.1' || host === '[::1]') return { type: 'app' };
    if (host === pages) {
      const slug = pathname.replace(/^\/+|\/+$/g, '');
      if (SLUG.test(slug)) return { type: 'app', page: { mode: 'pages', host, slug } };
      return toSite(pathname === '/' ? '/' : pathname + search);
    }
    const found = await byHost(host);
    if (found?.error) return { type: 'unavailable' };
    if (!found) return { type: 'notFound' };
    if (found.kind !== 'business_page') return toSite('/');
    if (pathname === '/' || pathname === '') return { type: 'app', page: { mode: 'custom', host, slug: found.slug } };
    return toSite(pathname + search);
  };
}
