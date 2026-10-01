import { describe, expect, it } from 'vitest';
import type { Locale } from '@northline/ui';
import { Route as Home } from '../routes/index';
import { Route as Provider } from '../routes/providers/$slug/index';
import { Route as Product } from '../routes/products/$productId';
import { Route as Kitchen } from '../routes/food/$kitchen';
import { Route as Department } from '../routes/shop/$department';
import { Route as Account } from '../routes/account/index';

/**
 * S-63: what each public route puts in <head> — the tags TanStack's <HeadContent> renders on the server — for the data
 * its loader returns.
 */
type Head = { meta?: Record<string, string>[]; links?: Record<string, string>[]; scripts?: { type: string; children: string }[] };
const config = { siteOrigin: 'https://northline.test', legalEntity: 'Northline Marketplace Inc.', authOrigin: 'https://auth.test' };
function head(route: { options: { head?: unknown } }, args: { locale?: Locale; params?: Record<string, string>; search?: Record<string, unknown>; loaderData?: unknown }): Head {
  const fn = route.options.head as (ctx: unknown) => Head;
  return fn({ match: { context: { locale: args.locale ?? 'en', config }, search: args.search ?? {} }, params: args.params ?? {}, loaderData: args.loaderData });
}
const canonical = (h: Head) => h.links?.find(l => l.rel === 'canonical')?.href;
const jsonLd = (h: Head) => (h.scripts ?? []).filter(s => s.type === 'application/ld+json').map(s => JSON.parse(s.children) as Record<string, unknown>);

const STOREFRONT = {
  slug: 'prairie-wrench', url: '/providers/prairie-wrench', pageKind: 'business_page', brandColor: '#2f5d3a', logoUrl: '/api/v1/storefronts/prairie-wrench/logo',
  tagline: 'We come to you', ctaLabel: 'book_visit', announcement: null, customDomain: null, publishedAt: '2026-09-01T00:00:00Z', sections: [],
  business: { displayName: 'Prairie Wrench', type: 'provider', tier: 'master', city: 'Riverton', about: 'Mobile mechanic, licensed.', serviceArea: 'Riverton + 40 km', verifiedFacts: [] },
};
const FACTS = {
  merchantId: 'M1', slug: 'prairie-wrench', name: 'Prairie Wrench', tier: 'master', city: 'Riverton', since: '2024-01-01', verifiedFacts: [], rating: 4.9, reviewCount: 211,
  kind: 'visit', category: { id: 'service.automotive.mobile-mechanic', slug: 'mobile-mechanic', names: { en: 'Mobile mechanic', fr: 'Mécanicien mobile' } },
  vehicle: true, quoteable: true, zones: [], nextAvailable: null, taxBps: 500, reviews: { items: [], nextOffset: null },
  services: [{ id: 'S1', name: 'Brake pads', included: 'Front axle', pricingMode: 'fixed', priceCents: 18900, durationMin: 90, instantBook: true, categorySlug: 'mobile-mechanic', kind: 'visit' }],
};

describe('route heads', () => {
  it('provider page: canonical + hreflang on the site, LocalBusiness JSON-LD', () => {
    const h = head(Provider, { params: { slug: 'prairie-wrench' }, loaderData: { page: STOREFRONT, facts: FACTS } });
    expect(canonical(h)).toBe('https://northline.test/providers/prairie-wrench');
    expect(h.links).toContainEqual({ rel: 'alternate', hrefLang: 'fr-CA', href: 'https://northline.test/providers/prairie-wrench?lang=fr' });
    expect(h.meta).toContainEqual({ property: 'og:type', content: 'business.business' });
    expect(h.meta).toContainEqual({ property: 'og:image', content: 'https://northline.test/api/v1/storefronts/prairie-wrench/logo' });
    const [business] = jsonLd(h);
    expect(business).toMatchObject({
      '@context': 'https://schema.org', '@type': 'LocalBusiness', name: 'Prairie Wrench', url: 'https://northline.test/providers/prairie-wrench',
      aggregateRating: { ratingValue: 4.9, reviewCount: 211 },
      hasOfferCatalog: { name: 'Mobile mechanic', itemListElement: [{ itemOffered: { '@type': 'Service', name: 'Brake pads' }, price: '189.00', priceCurrency: 'CAD' }] },
    });
    expect(h.links).toContainEqual(expect.objectContaining({ rel: 'stylesheet' }));
  });

  it('provider page with its own domain: that domain is canonical, in French too', () => {
    const h = head(Provider, { locale: 'fr', params: { slug: 'prairie-wrench' }, loaderData: { page: { ...STOREFRONT, customDomain: 'book.example.ca' }, facts: FACTS } });
    expect(canonical(h)).toBe('https://book.example.ca/?lang=fr');
    expect(h.meta?.find(m => 'title' in m)?.title).toBe('Prairie Wrench · Mécanicien mobile · Riverton · Northline');
    expect(jsonLd(h)[0]).toMatchObject({ url: 'https://book.example.ca/', hasOfferCatalog: { name: 'Mécanicien mobile' } });
  });

  it('product page: Product + Offer, canonical keeps the market', () => {
    const offer = { offerId: 'O1', merchantId: 'M2', shopName: 'Glenmore Bakery', tier: 'master', rating: 4.8, ratingCount: 12, priceCents: 750, compareAtCents: null, condition: 'new',
      stock: 3, lowStock: false, returnsPolicy: null, variantTheme: 'none', variants: [], runs: [], images: ['/api/v1/public/catalogue/media/IMG1'], more: [] };
    const h = head(Product, { params: { productId: 'P1' }, search: { market: 'Riverton' }, loaderData: {
      productId: 'P1', name: 'Country sourdough', brand: null, description: 'Naturally leavened.', bullets: [], unit: '900 g', departmentSlug: 'bakery',
      departmentName: 'Bakery', market: 'Riverton', served: true, offers: [offer], direct: null,
    } });
    expect(canonical(h)).toBe('https://northline.test/products/P1?market=Riverton');
    expect(jsonLd(h)[0]).toMatchObject({
      '@type': 'Product', name: 'Country sourdough', category: 'Bakery', image: ['https://northline.test/api/v1/public/catalogue/media/IMG1'],
      offers: { '@type': 'Offer', price: '7.50', availability: 'https://schema.org/InStock', seller: { name: 'Glenmore Bakery' } },
    });
  });

  it('restaurant page: Restaurant with its menu and cuisines in the page’s language', () => {
    const h = head(Kitchen, { locale: 'fr', params: { kitchen: 'pho-dau-bo' }, loaderData: {
      kitchen: { merchantId: 'K1', slug: 'pho-dau-bo', name: 'Pho Dau Bo', cuisines: ['vietnamese'], dietary: [], priceLevel: '$$', open: true, opensAt: null, closesAt: null,
        paused: false, fulfilment: ['delivery', 'pickup'], prepMin: 15, etaFromMin: 25, etaToMin: 35, pickupFromMin: 15, pickupToMin: 20, distanceKm: null, delivers: null,
        deliveryFeeCents: 399, rating: 4.7, reviews: 88, brandColor: null },
      address: '1 Main St', province: 'AB', ahsVerified: true, minOrderCents: 1500, serviceFeeBps: 500, taxBps: 500, slots: [], combos: [],
      sections: [{ id: 'S1', name: 'Soupes', menu: 'Menu', items: [{ id: 'D1', name: 'Pho tai', description: null, priceCents: 1650, dietary: [], allergens: [], soldOut: false, availableNow: true, availability: 'now', groups: [] }] }],
    } });
    expect(canonical(h)).toBe('https://northline.test/food/pho-dau-bo?lang=fr');
    expect(jsonLd(h)[0]).toMatchObject({ '@type': 'Restaurant', servesCuisine: ['Vietnamien'], hasMenu: { hasMenuSection: [{ name: 'Soupes' }] } });
  });

  it('department page and home page', () => {
    expect(canonical(head(Department, { params: { department: 'bakery' }, loaderData: undefined }))).toBe('https://northline.test/shop/bakery');
    const home = head(Home, {});
    expect(canonical(home)).toBe('https://northline.test/');
    expect(jsonLd(home).map(d => d['@type'])).toEqual(['Organization', 'WebSite']);
  });

  it('screens not built yet are kept out of search engines', () => {
    expect(head(Account, {}).meta).toContainEqual({ name: 'robots', content: 'noindex' });
  });
});
