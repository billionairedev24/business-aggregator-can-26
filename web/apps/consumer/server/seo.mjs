// robots.txt and the XML sitemaps of the consumer site (S-63), served by node-server.mjs (and the dev server):
//
//   site host         /robots.txt          allow everything public, keep crawlers out of personal and transactional pages
//                     /sitemap.xml         sitemap index: /sitemaps/pages.xml + /sitemaps/<section>-<n>.xml
//                     /sitemaps/pages.xml  the landing pages and the legal documents
//                     /sitemaps/<section>-<n>.xml   page n of a section of GET {NL_BFF_URL}/api/v1/public/sitemap
//   pages.<zone>      /robots.txt          allow, no sitemap: its pages name the site (or the business's domain) as canonical
//   a merchant's own domain   /robots.txt + /sitemap.xml  that domain's one page, /
//
// Every page URL carries its French alternate (`?lang=fr`, src/lib/seo.ts) as xhtml:link hreflang entries. A business
// page with a live custom domain is listed on that domain, not on the site (its canonical lives there).

const SECTIONS = {
  providers: key => `/providers/${encodeURIComponent(key)}`,
  kitchens: key => `/food/${encodeURIComponent(key)}`,
  products: key => `/products/${encodeURIComponent(key)}`,
  services: key => `/services/${encodeURIComponent(key)}`,
  departments: key => `/shop/${encodeURIComponent(key)}`,
};

/** Landing pages (localised) and the legal documents (English only, verbatim design 09/10). */
const STATIC_PAGES = [
  { path: '/', alternates: true }, { path: '/services', alternates: true }, { path: '/shop', alternates: true },
  { path: '/food', alternates: true },
  { path: '/legal/terms.html', alternates: false }, { path: '/legal/privacy.html', alternates: false },
];

/** Paths crawlers have no business in: personal, transactional, endless or the BFF. */
export const DISALLOW = [
  '/api/', '/bff/', '/oauth2/', '/login/', '/account', '/cart', '/orders/', '/food/checkout', '/food/orders/', '/quotes/',
  '/providers/*/book', '/services/*/quote', '/location', '/sign-in', '/register', '/search',
];

const xml = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const trim = origin => origin.replace(/\/$/, '');

/** The page in a language: English is the plain URL, French adds `lang=fr` (src/lib/seo.ts pageUrl). */
export const localized = (url, locale) => (locale === 'fr' ? `${url}${url.includes('?') ? '&' : '?'}lang=fr` : url);

export function robotsTxt(kind, origin) {
  const base = trim(origin);
  if (kind === 'site') {
    return ['User-agent: *', ...DISALLOW.map(p => `Disallow: ${p}`), 'Allow: /', '', `Sitemap: ${base}/sitemap.xml`, ''].join('\n');
  }
  if (kind === 'custom') return ['User-agent: *', 'Disallow: /api/', 'Disallow: /bff/', 'Allow: /', '', `Sitemap: ${base}/sitemap.xml`, ''].join('\n');
  return ['User-agent: *', 'Disallow: /api/', 'Disallow: /bff/', 'Allow: /', ''].join('\n');
}

/** A <urlset> with hreflang alternates (en-CA, fr-CA, x-default) for localised pages. */
export function urlset(entries) {
  const body = entries.map(({ loc, lastmod, alternates = true }) => {
    const links = alternates
      ? [['en-CA', loc], ['fr-CA', localized(loc, 'fr')], ['x-default', loc]]
        .map(([lang, href]) => `\n    <xhtml:link rel="alternate" hreflang="${lang}" href="${xml(href)}"/>`).join('')
      : '';
    return `  <url>\n    <loc>${xml(loc)}</loc>${lastmod ? `\n    <lastmod>${xml(lastmod.slice(0, 10))}</lastmod>` : ''}${links}\n  </url>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${body.join('\n')}\n</urlset>\n`;
}

export function sitemapIndex(locs) {
  const body = locs.map(loc => `  <sitemap><loc>${xml(loc)}</loc></sitemap>`).join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>\n<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${body}\n</sitemapindex>\n`;
}

const TEXT = 'text/plain; charset=utf-8';
const XML = 'application/xml; charset=utf-8';
const ok = (type, body) => ({ status: 200, type, body, cache: 'public, max-age=3600' });
const NOT_FOUND = { status: 404, type: TEXT, body: 'Not found', cache: 'no-store' };
const UNAVAILABLE = { status: 503, type: TEXT, body: 'Try again in a moment.', cache: 'no-store' };

/** Is this one of ours? (node-server asks before routing the request to the app.) */
export const isSeoPath = pathname => pathname === '/robots.txt' || pathname === '/sitemap.xml' || /^\/sitemaps\/[a-z]+(-\d{1,5})?\.xml$/.test(pathname);

/**
 * @param {{ siteOrigin: string, bffUrl: string, fetch?: typeof fetch, ttlMs?: number, now?: () => number }} options
 * @returns {(host: import('./page-hosts.mjs').HostKind, pathname: string) => Promise<{ status: number, type: string, body: string, cache: string }>}
 */
export function createSeo({ siteOrigin, bffUrl, fetch: fetcher = globalThis.fetch, ttlMs = 10 * 60_000, now = Date.now }) {
  const site = trim(siteOrigin);
  const api = trim(bffUrl);
  const cache = new Map();

  async function get(path) {
    const hit = cache.get(path);
    if (hit && hit.until > now()) return hit.body;
    const res = await fetcher(`${api}${path}`, { headers: { accept: 'application/json' } });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`${path} answered ${res.status}`);
    const body = await res.json();
    cache.set(path, { body, until: now() + ttlMs });
    if (cache.size > 1_000) cache.delete(cache.keys().next().value);
    return body;
  }

  return async function handle(host, pathname) {
    if (host.kind === 'unavailable') return UNAVAILABLE;
    if (host.kind === 'none') return NOT_FOUND;
    if (pathname === '/robots.txt') {
      return ok(TEXT, robotsTxt(host.kind, host.kind === 'site' ? site : `https://${host.host}`));
    }
    if (host.kind === 'custom') {
      return pathname === '/sitemap.xml' ? ok(XML, urlset([{ loc: `https://${host.host}/` }])) : NOT_FOUND;
    }
    if (host.kind !== 'site') return NOT_FOUND;
    try {
      if (pathname === '/sitemap.xml') {
        const index = await get('/api/v1/public/sitemap');
        const locs = [`${site}/sitemaps/pages.xml`];
        for (const s of index?.sections ?? []) {
          if (!(s.name in SECTIONS)) continue;
          for (let n = 1; n <= s.pages; n++) locs.push(`${site}/sitemaps/${s.name}-${n}.xml`);
        }
        return ok(XML, sitemapIndex(locs));
      }
      if (pathname === '/sitemaps/pages.xml') {
        return ok(XML, urlset(STATIC_PAGES.map(p => ({ loc: `${site}${p.path}`, alternates: p.alternates }))));
      }
      const m = /^\/sitemaps\/([a-z]+)-(\d{1,5})\.xml$/.exec(pathname);
      const route = m && SECTIONS[m[1]];
      if (!route || Number(m[2]) < 1) return NOT_FOUND;
      const page = await get(`/api/v1/public/sitemap/${m[1]}?page=${Number(m[2])}`);
      if (!page) return NOT_FOUND;
      const entries = page.items
        .filter(item => !item.customDomain) // listed on the business's own domain
        .map(item => ({ loc: `${site}${route(item.key)}`, lastmod: item.updatedAt ?? undefined }));
      return ok(XML, urlset(entries));
    } catch {
      return UNAVAILABLE;
    }
  };
}
