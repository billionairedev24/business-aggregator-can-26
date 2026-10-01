/**
 * schema.org JSON-LD for the public pages (S-63): a provider page is a `LocalBusiness` offering `Service`s with its
 * `AggregateRating`; a product is a `Product` with an `Offer` (one shop) or an `AggregateOffer` (several); a kitchen
 * is a `Restaurant` with its menu. Only what the page itself shows; money in CAD. No `@context` (added by seo()).
 */

const CURRENCY = 'CAD';
const price = (cents: number) => (cents / 100).toFixed(2);
const compact = <T extends object>(o: T): T =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== '' && !(Array.isArray(v) && v.length === 0))) as T;

/** Only when there are reviews: an `AggregateRating` with no count is invalid. */
export function aggregateRating(rating: number | null | undefined, count: number | null | undefined) {
  return rating && count && count > 0
    ? { '@type': 'AggregateRating', ratingValue: Number(rating.toFixed(2)), reviewCount: count, bestRating: 5, worstRating: 1 }
    : undefined;
}

export interface ProviderData {
  url: string;
  name: string;
  description?: string | null;
  image?: string | null;
  city?: string | null;
  province?: string | null;
  areaServed?: string | null;
  category?: string | null;
  rating?: number | null;
  reviewCount?: number | null;
  services: readonly { name: string; description?: string | null; priceCents?: number | null; pricingMode: string }[];
}

export function providerJsonLd(p: ProviderData): object {
  const services = p.services.map(s => compact({
    '@type': 'Offer',
    itemOffered: compact({ '@type': 'Service', name: s.name, description: s.description ?? undefined, serviceType: p.category ?? undefined }),
    ...(s.priceCents != null && s.pricingMode !== 'quote'
      ? s.pricingMode === 'hourly'
        ? { priceSpecification: { '@type': 'UnitPriceSpecification', price: price(s.priceCents), priceCurrency: CURRENCY, unitCode: 'HUR' } }
        : { price: price(s.priceCents), priceCurrency: CURRENCY }
      : {}),
  }));
  return compact({
    '@type': 'LocalBusiness',
    '@id': `${p.url}#business`,
    url: p.url,
    name: p.name,
    description: p.description ?? undefined,
    image: p.image ?? undefined,
    address: p.city ? compact({ '@type': 'PostalAddress', addressLocality: p.city, addressRegion: p.province ?? undefined, addressCountry: 'CA' }) : undefined,
    areaServed: p.areaServed ?? p.city ?? undefined,
    aggregateRating: aggregateRating(p.rating, p.reviewCount),
    hasOfferCatalog: services.length ? { '@type': 'OfferCatalog', name: p.category ?? p.name, itemListElement: services } : undefined,
  });
}

export interface ProductData {
  url: string;
  name: string;
  description?: string | null;
  brand?: string | null;
  category?: string | null;
  images: readonly string[];
  offers: readonly { shopName: string; priceCents: number; stock: number; rating: number; ratingCount: number; condition?: string | null }[];
}

const availability = (stock: number) => `https://schema.org/${stock > 0 ? 'InStock' : 'OutOfStock'}`;

export function productJsonLd(p: ProductData): object {
  const offers = p.offers;
  const reviews = offers.reduce((n, o) => n + o.ratingCount, 0);
  const rating = reviews ? offers.reduce((sum, o) => sum + o.rating * o.ratingCount, 0) / reviews : null;
  const offer = offers.length === 1
    ? compact({
      '@type': 'Offer', url: p.url, price: price(offers[0]!.priceCents), priceCurrency: CURRENCY,
      availability: availability(offers[0]!.stock),
      itemCondition: offers[0]!.condition === 'used' ? 'https://schema.org/UsedCondition' : 'https://schema.org/NewCondition',
      seller: { '@type': 'Organization', name: offers[0]!.shopName },
    })
    : offers.length > 1
      ? {
        '@type': 'AggregateOffer', url: p.url, priceCurrency: CURRENCY, offerCount: offers.length,
        lowPrice: price(Math.min(...offers.map(o => o.priceCents))), highPrice: price(Math.max(...offers.map(o => o.priceCents))),
        availability: availability(Math.max(...offers.map(o => o.stock))),
      }
      : undefined;
  return compact({
    '@type': 'Product',
    '@id': `${p.url}#product`,
    url: p.url,
    name: p.name,
    description: p.description ?? undefined,
    brand: p.brand ? { '@type': 'Brand', name: p.brand } : undefined,
    category: p.category ?? undefined,
    image: p.images.length ? [...p.images] : undefined,
    offers: offer,
    aggregateRating: aggregateRating(rating, reviews),
  });
}

export interface RestaurantData {
  url: string;
  name: string;
  address?: string | null;
  province?: string | null;
  cuisines: readonly string[];
  rating?: number | null;
  reviewCount?: number | null;
  priceLevel?: string | null;
  delivers: boolean;
  sections: readonly { name: string; items: readonly { name: string; description?: string | null; priceCents: number; dietary: readonly string[] }[] }[];
}

/** schema.org's suitableForDiet values for the dietary codes kitchens declare. */
const DIETS: Record<string, string> = {
  halal: 'HalalDiet', kosher: 'KosherDiet', vegan: 'VeganDiet', vegetarian: 'VegetarianDiet', gluten_free: 'GlutenFreeDiet',
  low_sodium: 'LowSaltDiet', diabetic: 'DiabeticDiet', low_fat: 'LowFatDiet', low_calorie: 'LowCalorieDiet',
};

export function restaurantJsonLd(r: RestaurantData): object {
  return compact({
    '@type': 'Restaurant',
    '@id': `${r.url}#restaurant`,
    url: r.url,
    name: r.name,
    address: r.address ? compact({ '@type': 'PostalAddress', streetAddress: r.address, addressRegion: r.province ?? undefined, addressCountry: 'CA' }) : undefined,
    servesCuisine: [...r.cuisines],
    priceRange: r.priceLevel ?? undefined,
    aggregateRating: aggregateRating(r.rating, r.reviewCount),
    potentialAction: r.delivers ? { '@type': 'OrderAction', target: r.url, deliveryMethod: 'http://purl.org/goodrelations/v1#DeliveryModeOwnFleet' } : undefined,
    hasMenu: r.sections.length ? {
      '@type': 'Menu',
      hasMenuSection: r.sections.map(s => ({
        '@type': 'MenuSection',
        name: s.name,
        hasMenuItem: s.items.map(i => compact({
          '@type': 'MenuItem',
          name: i.name,
          description: i.description ?? undefined,
          offers: { '@type': 'Offer', price: price(i.priceCents), priceCurrency: CURRENCY },
          suitableForDiet: i.dietary.map(d => DIETS[d]).filter(Boolean).map(d => `https://schema.org/${d}`),
        })),
      })),
    } : undefined,
  });
}

/** The site itself on the home page: an `Organization` (the legal entity) and a `WebSite` with its search box. */
export function siteJsonLd(origin: string, legalEntity: string): object[] {
  const base = origin.replace(/\/$/, '');
  return [
    { '@type': 'Organization', '@id': `${base}/#organization`, name: 'Northline', legalName: legalEntity, url: `${base}/` },
    {
      '@type': 'WebSite', '@id': `${base}/#website`, name: 'Northline', url: `${base}/`, inLanguage: ['en-CA', 'fr-CA'],
      potentialAction: { '@type': 'SearchAction', target: { '@type': 'EntryPoint', urlTemplate: `${base}/search?q={search_term_string}` }, 'query-input': 'required name=search_term_string' },
    },
  ];
}
