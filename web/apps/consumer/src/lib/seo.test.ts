import { describe, expect, it } from 'vitest';
import { pickLocale, urlLocale } from './locale';
import { pageUrl, scriptJson, seo } from './seo';
import { aggregateRating, productJsonLd, providerJsonLd, restaurantJsonLd, siteJsonLd } from './structuredData';

const site = { siteOrigin: 'https://northline.test' };
const ld = (scripts: { type: string; children: string }[]) => scripts.map(s => JSON.parse(s.children) as Record<string, unknown>);

describe('seo() tags (S-63)', () => {
  it('gives title, description, canonical, hreflang en/fr/x-default and Open Graph', () => {
    const { meta, links, scripts } = seo({ locale: 'en', config: site }, {
      title: 'Bakery · Northline', description: '  Fresh   bread\n daily. ', path: '/shop/bakery', query: { market: 'Riverton', empty: undefined },
    });
    expect(meta).toContainEqual({ title: 'Bakery · Northline' });
    expect(meta).toContainEqual({ name: 'description', content: 'Fresh bread daily.' });
    expect(meta).toContainEqual({ property: 'og:url', content: 'https://northline.test/shop/bakery?market=Riverton' });
    expect(meta).toContainEqual({ property: 'og:locale', content: 'en_CA' });
    expect(meta).toContainEqual({ property: 'og:locale:alternate', content: 'fr_CA' });
    expect(meta.find(m => m.name === 'robots')).toBeUndefined();
    expect(links).toEqual([
      { rel: 'canonical', href: 'https://northline.test/shop/bakery?market=Riverton' },
      { rel: 'alternate', hrefLang: 'en-CA', href: 'https://northline.test/shop/bakery?market=Riverton' },
      { rel: 'alternate', hrefLang: 'fr-CA', href: 'https://northline.test/shop/bakery?lang=fr&market=Riverton' },
      { rel: 'alternate', hrefLang: 'x-default', href: 'https://northline.test/shop/bakery?market=Riverton' },
    ]);
    expect(scripts).toEqual([]);
  });

  it('makes the French page its own canonical URL', () => {
    const { links, meta } = seo({ locale: 'fr', config: site }, { title: 'Services · Northline', path: '/services' });
    expect(links[0]).toEqual({ rel: 'canonical', href: 'https://northline.test/services?lang=fr' });
    expect(meta).toContainEqual({ property: 'og:locale', content: 'fr_CA' });
  });

  it('uses a business’s own domain and leaves noindex pages without canonical', () => {
    expect(seo({ locale: 'en', config: site }, { title: 'x', path: '/', origin: 'https://book.example.ca' }).links[0])
      .toEqual({ rel: 'canonical', href: 'https://book.example.ca/' });
    const hidden = seo({ locale: 'en', config: site }, { title: 'Cart', path: '/cart', noindex: true });
    expect(hidden.links).toEqual([]);
    expect(hidden.meta).toContainEqual({ name: 'robots', content: 'noindex' });
  });

  it('writes JSON-LD that can’t close its script element', () => {
    const { scripts } = seo({ locale: 'en', config: site }, { title: 'x', path: '/', jsonLd: [{ '@type': 'Thing', name: '</script><b>' }] });
    expect(scripts[0]!.type).toBe('application/ld+json');
    expect(scripts[0]!.children).not.toContain('</script>');
    expect(ld(scripts)[0]).toEqual({ '@context': 'https://schema.org', '@type': 'Thing', name: '</script><b>' });
    expect(scriptJson('<')).toBe('"\\u003c"');
  });

  it('sorts query parameters so one page has one URL', () => {
    expect(pageUrl('https://n.test/', '/food', { cuisine: 'pho', area: 'x' }, 'fr')).toBe('https://n.test/food?area=x&cuisine=pho&lang=fr');
  });

  it('lets ?lang= choose the language over the cookie and the browser', () => {
    expect(pickLocale('en', 'en-CA', urlLocale('/shop?lang=fr'))).toBe('fr');
    expect(pickLocale('fr', 'fr-CA', urlLocale('https://x/?lang=en'))).toBe('en');
    expect(pickLocale('fr', null, urlLocale('/shop?lang=de'))).toBe('fr');
  });
});

describe('structured data (schema.org)', () => {
  it('describes a provider as a LocalBusiness with its services and rating', () => {
    const data = providerJsonLd({
      url: 'https://northline.test/providers/prairie-wrench', name: 'Prairie Wrench', description: 'Mobile mechanic.', city: 'Riverton', province: 'AB',
      category: 'Mobile mechanic', rating: 4.9, reviewCount: 211, areaServed: 'Riverton + 40 km',
      services: [
        { name: 'Brake pads', description: 'Front axle.', priceCents: 18900, pricingMode: 'fixed' },
        { name: 'Diagnosis', priceCents: 9500, pricingMode: 'hourly' },
        { name: 'Engine swap', priceCents: null, pricingMode: 'quote' },
      ],
    });
    expect(data).toMatchObject({
      '@type': 'LocalBusiness', '@id': 'https://northline.test/providers/prairie-wrench#business', name: 'Prairie Wrench',
      address: { '@type': 'PostalAddress', addressLocality: 'Riverton', addressRegion: 'AB', addressCountry: 'CA' },
      areaServed: 'Riverton + 40 km',
      aggregateRating: { '@type': 'AggregateRating', ratingValue: 4.9, reviewCount: 211, bestRating: 5, worstRating: 1 },
      hasOfferCatalog: { '@type': 'OfferCatalog', name: 'Mobile mechanic' },
    });
    const offers = (data as { hasOfferCatalog: { itemListElement: Record<string, unknown>[] } }).hasOfferCatalog.itemListElement;
    expect(offers[0]).toEqual({ '@type': 'Offer', itemOffered: { '@type': 'Service', name: 'Brake pads', description: 'Front axle.', serviceType: 'Mobile mechanic' }, price: '189.00', priceCurrency: 'CAD' });
    expect(offers[1]!.priceSpecification).toEqual({ '@type': 'UnitPriceSpecification', price: '95.00', priceCurrency: 'CAD', unitCode: 'HUR' });
    expect(offers[2]).not.toHaveProperty('price');
  });

  it('leaves the rating out without reviews', () => {
    expect(aggregateRating(4.5, 0)).toBeUndefined();
    expect(providerJsonLd({ url: 'u', name: 'New', rating: 0, reviewCount: 0, services: [] })).toEqual({ '@type': 'LocalBusiness', '@id': 'u#business', url: 'u', name: 'New' });
  });

  it('describes a product with one Offer, or an AggregateOffer across shops', () => {
    const one = productJsonLd({
      url: 'https://n.test/products/P1', name: 'Country sourdough', brand: 'Glenmore', category: 'Bakery', images: ['https://n.test/i.jpg'],
      offers: [{ shopName: 'Glenmore Bakery', priceCents: 750, stock: 4, rating: 4.8, ratingCount: 10 }],
    });
    expect(one).toMatchObject({
      '@type': 'Product', name: 'Country sourdough', brand: { '@type': 'Brand', name: 'Glenmore' }, image: ['https://n.test/i.jpg'],
      offers: { '@type': 'Offer', price: '7.50', priceCurrency: 'CAD', availability: 'https://schema.org/InStock', itemCondition: 'https://schema.org/NewCondition', seller: { '@type': 'Organization', name: 'Glenmore Bakery' } },
      aggregateRating: { ratingValue: 4.8, reviewCount: 10 },
    });
    const many = productJsonLd({
      url: 'u', name: 'Eggs', images: [],
      offers: [{ shopName: 'A', priceCents: 650, stock: 0, rating: 5, ratingCount: 1 }, { shopName: 'B', priceCents: 700, stock: 0, rating: 4, ratingCount: 3 }],
    });
    expect(many).toMatchObject({
      offers: { '@type': 'AggregateOffer', lowPrice: '6.50', highPrice: '7.00', offerCount: 2, priceCurrency: 'CAD', availability: 'https://schema.org/OutOfStock' },
      aggregateRating: { ratingValue: 4.25, reviewCount: 4 },
    });
    expect(many).not.toHaveProperty('image');
  });

  it('describes a kitchen as a Restaurant with its menu', () => {
    const data = restaurantJsonLd({
      url: 'https://n.test/food/pho', name: 'Pho Dau Bo', address: '1 Main St', province: 'AB', cuisines: ['Vietnamese'], rating: 4.7, reviewCount: 88,
      priceLevel: '$$', delivers: true,
      sections: [{ name: 'Soups', items: [{ name: 'Pho tai', description: 'Rare beef', priceCents: 1650, dietary: ['halal', 'unknown'] }] }],
    });
    expect(data).toMatchObject({
      '@type': 'Restaurant', servesCuisine: ['Vietnamese'], priceRange: '$$',
      address: { streetAddress: '1 Main St', addressRegion: 'AB', addressCountry: 'CA' },
      aggregateRating: { ratingValue: 4.7, reviewCount: 88 },
      hasMenu: { '@type': 'Menu', hasMenuSection: [{ '@type': 'MenuSection', name: 'Soups', hasMenuItem: [{
        '@type': 'MenuItem', name: 'Pho tai', description: 'Rare beef', offers: { '@type': 'Offer', price: '16.50', priceCurrency: 'CAD' },
        suitableForDiet: ['https://schema.org/HalalDiet'],
      }] }] },
    });
  });

  it('names the site and its search on the home page', () => {
    const [org, web] = siteJsonLd('https://n.test/', 'Northline Marketplace Inc.') as Record<string, unknown>[];
    expect(org).toMatchObject({ '@type': 'Organization', legalName: 'Northline Marketplace Inc.', url: 'https://n.test/' });
    expect(web).toMatchObject({ '@type': 'WebSite', potentialAction: { target: { urlTemplate: 'https://n.test/search?q={search_term_string}' } } });
  });
});
