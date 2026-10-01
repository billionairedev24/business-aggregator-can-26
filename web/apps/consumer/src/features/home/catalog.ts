/**
 * The home page's entry tiles, in the design's order (design 06 `shopCatsT`, `cuisinesT`, `svcCatsT`), each tied to the
 * categories (db/seed/categories.json ids) or cuisine codes (onboarding's) whose counts it shows, and to where it leads.
 * French names are the design's.
 */
export interface Tile {
  key: string;
  en: string;
  fr: string;
  /** What `GET /api/v1/public/home` counts for this tile (summed). */
  ids: readonly string[];
  /** Where the tile goes: a landing page of Shop (S-49), Services (S-53) or Food (S-57). */
  href: string;
}
export interface ServiceTile extends Tile { kind: 'visit' | 'hourly' | 'quote' | 'shop' | 'consult' }

const shop = (key: string, en: string, fr: string, id: string): Tile => ({ key, en, fr, ids: [`shop.${id}`], href: `/shop/${id.split('.').pop()}` });

/** Shop by department: shops per department in the city. `/shop/<department slug>` is S-49's category page. */
export const DEPARTMENTS: readonly Tile[] = [
  shop('groceries', 'Groceries', 'Épicerie', 'food-and-grocery.groceries'),
  shop('butcher', 'Butcher', 'Boucherie', 'food-and-grocery.butcher'),
  shop('bakery', 'Bakery', 'Boulangerie', 'food-and-grocery.bakery'),
  shop('produce', 'Produce', 'Fruits et légumes', 'food-and-grocery.produce'),
  shop('clothing', 'Clothing', 'Vêtements', 'apparel.clothing'),
  shop('pharmacy', 'Pharmacy', 'Pharmacie', 'health-and-beauty.pharmacy-otc'),
  shop('hardware', 'Hardware', 'Quincaillerie', 'hardware-and-auto.hardware'),
  shop('kids', 'Kids', 'Enfants', 'kids-gifts-and-hobbies.kids'),
  shop('gifts', 'Gifts', 'Cadeaux', 'kids-gifts-and-hobbies.gifts-and-crafts'),
];

/** Order food: kitchens open now per cuisine (`meal_kits` = kitchens offering meal kits on the pooled run). */
export const CUISINES: readonly Tile[] = [
  ['vietnamese', 'Vietnamese', 'Vietnamien'], ['pizza', 'Pizza', 'Pizza'], ['indian', 'Indian', 'Indien'],
  ['ramen', 'Ramen', 'Ramen'], ['dessert', 'Dessert', 'Desserts'], ['ethiopian', 'Ethiopian', 'Éthiopien'],
  ['canadian', 'Canadian', 'Canadien'], ['meal_kits', 'Meal kits', 'Prêt-à-cuisiner'],
].map(([key, en, fr]) => ({ key: key!, en: en!, fr: fr!, ids: [key!], href: `/food?cuisine=${key}` }));

const svc = (key: string, en: string, fr: string, kind: ServiceTile['kind'], ...ids: string[]): ServiceTile =>
  ({ key, en, fr, kind, ids: ids.map(id => `service.${id}`), href: `/services/${ids[0]!.split('.').pop()}` });

/** Book a service: providers per category in the city, with how it's booked. `/services/<slug>` is S-53's page. */
export const SERVICE_CATEGORIES: readonly ServiceTile[] = [
  svc('mechanic', 'Mobile mechanic', 'Mécanicien mobile', 'visit', 'automotive.mobile-mechanic'),
  svc('cleaning', 'Home cleaning', 'Ménage', 'hourly', 'cleaning-and-property.house-cleaning'),
  svc('bar', 'Cocktail & mocktail bar', 'Bar à cocktails', 'quote', 'events-and-hospitality.cocktail-and-mocktail-bar'),
  svc('barber', 'Barber & hair', 'Coiffure', 'shop', 'personal-care-and-wellness.barber-and-hair'),
  svc('realestate', 'Real-estate agent', 'Courtier immobilier', 'consult', 'professional.real-estate-agent'),
  svc('plumber', 'Plumber', 'Plombier', 'visit', 'home-trades.plumber'),
  svc('tutor', 'Tutor', 'Tuteur', 'hourly', 'education-and-coaching.tutor-k-12', 'education-and-coaching.tutor-post-secondary'),
  svc('movers', 'Movers', 'Déménageurs', 'quote', 'cleaning-and-property.movers'),
  svc('snow', 'Snow removal', 'Déneigement', 'hourly', 'cleaning-and-property.snow-removal'),
];

/** The sum of a tile's counts; undefined while the numbers aren't known. */
export const countOf = (tile: Tile, counts: Record<string, number> | undefined) =>
  counts ? tile.ids.reduce((n, id) => n + (counts[id] ?? 0), 0) : undefined;
