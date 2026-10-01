import { describe, expect, it, vi } from 'vitest';
import { createPageRouter } from '../../server/page-hosts.mjs';
import { createSeo, isSeoPath, robotsTxt, urlset } from '../../server/seo.mjs';

const SITE = 'https://northline.test';
const INDEX = { pageSize: 5000, sections: [
  { name: 'providers', count: 2, pages: 1 }, { name: 'products', count: 7001, pages: 2 }, { name: 'kitchens', count: 0, pages: 0 },
  { name: 'departments', count: 1, pages: 1 }, { name: 'services', count: 1, pages: 1 }, { name: 'future', count: 1, pages: 1 },
] };
const PAGES: Record<string, unknown> = {
  '/api/v1/public/sitemap': INDEX,
  '/api/v1/public/sitemap/providers?page=1': { items: [
    { key: 'prairie-wrench', customDomain: null, updatedAt: '2026-09-30T18:00:00Z' },
    { key: 'own-domain', customDomain: 'book.example.ca', updatedAt: '2026-09-29T18:00:00Z' },
  ] },
  '/api/v1/public/sitemap/products?page=2': { items: [{ key: '01J9P&1', customDomain: null, updatedAt: null }] },
  '/api/v1/public/sitemap/departments?page=1': { items: [{ key: 'bakery', customDomain: null, updatedAt: null }] },
};

function api(fail = false) {
  return vi.fn(async (url: string) => {
    if (fail) return new Response('down', { status: 502 });
    const path = url.replace('http://bff.test', '');
    if (path.startsWith('/api/v1/public/storefronts/by-host')) {
      return path.includes('book.example.ca') ? Response.json({ slug: 'own-domain', pageKind: 'business_page' }) : new Response('', { status: 404 });
    }
    const body = PAGES[path];
    return body ? Response.json(body) : new Response('', { status: 404 });
  });
}
const seo = (fetch = api()) => createSeo({ siteOrigin: `${SITE}/`, bffUrl: 'http://bff.test', fetch: fetch as unknown as typeof globalThis.fetch });

describe('robots.txt (S-63)', () => {
  it('keeps crawlers out of personal and transactional pages on the site and names the sitemap', async () => {
    const res = await seo()({ kind: 'site' }, '/robots.txt');
    expect(res.status).toBe(200);
    expect(res.type).toBe('text/plain; charset=utf-8');
    for (const line of ['User-agent: *', 'Disallow: /cart', 'Disallow: /account', 'Disallow: /search', 'Disallow: /providers/*/book', 'Disallow: /api/', 'Allow: /', `Sitemap: ${SITE}/sitemap.xml`]) {
      expect(res.body.split('\n')).toContain(line);
    }
  });

  it('lets the pages host be crawled without a sitemap and gives a custom domain its own', () => {
    expect(robotsTxt('pages', 'https://pages.northline.test')).not.toContain('Sitemap');
    expect(robotsTxt('custom', 'https://book.example.ca')).toContain('Sitemap: https://book.example.ca/sitemap.xml');
  });

  it('knows its paths', () => {
    expect(['/robots.txt', '/sitemap.xml', '/sitemaps/pages.xml', '/sitemaps/products-12.xml'].every(isSeoPath)).toBe(true);
    expect(['/sitemaps/../x.xml', '/sitemap.xml/x', '/products/1'].some(isSeoPath)).toBe(false);
  });
});

describe('sitemaps', () => {
  it('indexes the landing pages and every page of each known section', async () => {
    const res = await seo()({ kind: 'site' }, '/sitemap.xml');
    expect(res.type).toBe('application/xml; charset=utf-8');
    expect(res.cache).toBe('public, max-age=3600');
    const locs = [...res.body.matchAll(/<loc>([^<]+)<\/loc>/g)].map(m => m[1]);
    expect(locs).toEqual([
      `${SITE}/sitemaps/pages.xml`, `${SITE}/sitemaps/providers-1.xml`, `${SITE}/sitemaps/products-1.xml`, `${SITE}/sitemaps/products-2.xml`,
      `${SITE}/sitemaps/departments-1.xml`, `${SITE}/sitemaps/services-1.xml`,
    ]);
    expect(res.body).toMatch(/^<\?xml version="1.0" encoding="UTF-8"\?>\n<sitemapindex xmlns="http:\/\/www.sitemaps.org\/schemas\/sitemap\/0.9">/);
  });

  it('lists the landing pages with French alternates, the legal documents without', async () => {
    const body = (await seo()({ kind: 'site' }, '/sitemaps/pages.xml')).body;
    expect(body).toContain(`<loc>${SITE}/services</loc>`);
    expect(body).toContain(`<xhtml:link rel="alternate" hreflang="fr-CA" href="${SITE}/services?lang=fr"/>`);
    expect(body).toContain(`<xhtml:link rel="alternate" hreflang="x-default" href="${SITE}/services"/>`);
    expect(body).toMatch(new RegExp(`<loc>${SITE}/legal/terms.html</loc>\\n  </url>`));
  });

  it('turns a section page into URLs, leaving a page with its own domain to that domain', async () => {
    const providers = (await seo()({ kind: 'site' }, '/sitemaps/providers-1.xml')).body;
    expect(providers).toContain(`<loc>${SITE}/providers/prairie-wrench</loc>\n    <lastmod>2026-09-30</lastmod>`);
    expect(providers).not.toContain('own-domain');
    const products = (await seo()({ kind: 'site' }, '/sitemaps/products-2.xml')).body;
    expect(products).toContain(`<loc>${SITE}/products/01J9P%261</loc>`);
    expect(products).toContain(`href="${SITE}/products/01J9P%261?lang=fr"`);
    expect((await seo()({ kind: 'site' }, '/sitemaps/departments-1.xml')).body).toContain(`<loc>${SITE}/shop/bakery</loc>`);
  });

  it('answers 404 for unknown sections and 503 while the api is down', async () => {
    expect((await seo()({ kind: 'site' }, '/sitemaps/future-1.xml')).status).toBe(404);
    expect((await seo()({ kind: 'site' }, '/sitemaps/kitchens-9.xml')).status).toBe(404);
    expect((await seo(api(true))({ kind: 'site' }, '/sitemap.xml')).status).toBe(503);
  });

  it('caches the api’s answers for a while', async () => {
    const fetch = api();
    const handle = seo(fetch);
    await handle({ kind: 'site' }, '/sitemap.xml');
    await handle({ kind: 'site' }, '/sitemap.xml');
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('serves a merchant’s own domain its one page, and nothing for unknown hosts', async () => {
    const fetch = api();
    const router = createPageRouter({ siteOrigin: SITE, pagesHost: 'pages.northline.test', bffUrl: 'http://bff.test', fetch: fetch as unknown as typeof globalThis.fetch });
    const handle = seo(fetch);
    const custom = await router.hostKind('Book.Example.ca:443');
    expect(custom).toEqual({ kind: 'custom', host: 'book.example.ca', slug: 'own-domain' });
    const map = await handle(custom, '/sitemap.xml');
    expect(map.body).toContain('<loc>https://book.example.ca/</loc>');
    expect(map.body).toContain('href="https://book.example.ca/?lang=fr"');
    expect((await handle(custom, '/robots.txt')).body).toContain('Sitemap: https://book.example.ca/sitemap.xml');
    expect(await router.hostKind('pages.northline.test')).toEqual({ kind: 'pages', host: 'pages.northline.test' });
    expect((await handle(await router.hostKind('pages.northline.test'), '/sitemap.xml')).status).toBe(404);
    expect((await handle(await router.hostKind('nobody.example.com'), '/robots.txt')).status).toBe(404);
    expect(await router.hostKind('northline.test')).toEqual({ kind: 'site' });
  });

  it('escapes XML', () => {
    expect(urlset([{ loc: 'https://n.test/a?x=1&y=<2>', alternates: false }])).toContain('<loc>https://n.test/a?x=1&amp;y=&lt;2&gt;</loc>');
  });
});
