import { describe, expect, it, vi } from 'vitest';
import { createPageRouter, normalizeHost } from '../../server/page-hosts.mjs';
import { pageRewrite, siteHref } from './pages';

const byHost = (pages: Record<string, { slug: string; pageKind: string } | 'down'>) => vi.fn(async (url: string) => {
  const host = new URL(url).searchParams.get('host')!;
  const hit = pages[host];
  if (hit === 'down') return new Response('', { status: 503 });
  return hit ? new Response(JSON.stringify(hit), { status: 200 }) : new Response('', { status: 404 });
});

describe('page hosts (server/page-hosts.mjs)', () => {
  const options = { siteOrigin: 'https://northline.ca', pagesHost: 'pages.northline.ca', bffUrl: 'http://bff:8081' };

  it('leaves the site alone, and everything when no pages host is configured', async () => {
    const route = createPageRouter({ ...options, fetch: byHost({}) });
    expect(await route('northline.ca', '/services')).toEqual({ type: 'app' });
    expect(await route('localhost:3000', '/')).toEqual({ type: 'app' });
    expect(await createPageRouter({ fetch: byHost({}) })('book.example.ca', '/')).toEqual({ type: 'app' });
  });

  it('serves pages.<zone>/<slug> and sends the rest to the site', async () => {
    const route = createPageRouter({ ...options, fetch: byHost({}) });
    expect(await route('Pages.Northline.ca.', '/prairie-wrench')).toEqual({ type: 'app', page: { mode: 'pages', host: 'pages.northline.ca', slug: 'prairie-wrench' } });
    expect(await route('pages.northline.ca', '/')).toEqual({ type: 'redirect', location: 'https://northline.ca/' });
    expect(await route('pages.northline.ca', '/services/plumber', '?x=1')).toEqual({ type: 'redirect', location: 'https://northline.ca/services/plumber?x=1' });
  });

  it('looks merchants’ own domains up by host (cached a minute) and serves their business page at /', async () => {
    let now = 0;
    const fetch = byHost({ 'book.example.ca': { slug: 'prairie-wrench', pageKind: 'business_page' }, 'shop.example.ca': { slug: 'parts', pageKind: 'store' } });
    const route = createPageRouter({ ...options, fetch, now: () => now });
    expect(await route('book.example.ca:443', '/')).toEqual({ type: 'app', page: { mode: 'custom', host: 'book.example.ca', slug: 'prairie-wrench' } });
    expect(await route('book.example.ca', '/providers/prairie-wrench/book')).toEqual({ type: 'redirect', location: 'https://northline.ca/providers/prairie-wrench/book' });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0]![0]).toBe('http://bff:8081/api/v1/public/storefronts/by-host?host=book.example.ca');
    now = 61_000;
    await route('book.example.ca', '/');
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(await route('shop.example.ca', '/')).toEqual({ type: 'redirect', location: 'https://northline.ca/' });
    expect(await route('nobody.example.ca', '/')).toEqual({ type: 'notFound' });
  });

  it('says so when the lookup is down (and doesn’t cache it)', async () => {
    const route = createPageRouter({ ...options, fetch: byHost({ 'book.example.ca': 'down' }) });
    expect(await route('book.example.ca', '/')).toEqual({ type: 'unavailable' });
  });

  it('normalises hosts', () => {
    expect(normalizeHost('Book.Example.CA.:8443')).toBe('book.example.ca');
    expect(normalizeHost('[::1]:3000')).toBe('[::1]');
  });
});

describe('page rewrite (router)', () => {
  const url = (href: string) => new URL(href);
  it('maps a merchant domain’s / onto the provider route and back', () => {
    const rewrite = pageRewrite({ mode: 'custom', host: 'book.example.ca', slug: 'prairie-wrench' })!;
    expect((rewrite.input!({ url: url('https://book.example.ca/') }) as URL).pathname).toBe('/providers/prairie-wrench');
    expect(rewrite.input!({ url: url('https://book.example.ca/other') })).toBeUndefined();
    expect((rewrite.output!({ url: url('https://book.example.ca/providers/prairie-wrench') }) as URL).pathname).toBe('/');
  });
  it('maps pages.<zone>/<slug>', () => {
    const rewrite = pageRewrite({ mode: 'pages', host: 'pages.northline.ca', slug: 'prairie-wrench' })!;
    expect((rewrite.input!({ url: url('https://pages.northline.ca/prairie-wrench') }) as URL).pathname).toBe('/providers/prairie-wrench');
    expect((rewrite.output!({ url: url('https://pages.northline.ca/providers/prairie-wrench') }) as URL).pathname).toBe('/prairie-wrench');
  });
  it('is off on the site; links from other hosts go to the site', () => {
    expect(pageRewrite(null)).toBeUndefined();
    expect(siteHref(null, 'https://northline.ca', '/providers/x/book')).toBe('/providers/x/book');
    expect(siteHref({ mode: 'custom', host: 'a.ca', slug: 'x' }, 'https://northline.ca', '/providers/x/book')).toBe('https://northline.ca/providers/x/book');
  });
});
