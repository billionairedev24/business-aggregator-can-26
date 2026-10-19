import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { apiQuery, categoryHref, itemHref, SearchParams, suggestionHref, type SearchItem } from './api';
import { RECENT_KEY } from './recent';
import { SearchResults } from './SearchResults';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const merchant = (name: string, type: string, slug: string | null, tier = 'master') => ({ id: `m-${name}`, name, type, slug, tier });
const item = (id: string, kind: string, name: string, extra: Partial<SearchItem> = {}) => ({
  id, kind, name, description: null, merchant: merchant('Glenmore Bakery', 'seller', null), category: { id: 'shop.groceries.bakery', name: 'Bakery' },
  priceCents: 750, pricingMode: 'fixed', rating: null, reviewCount: 0, trustTier: 'registered', distanceKm: null, instantBook: false,
  fulfilment: ['pooled'], openNow: false, soldOut: false, onTonightsRun: false, prepMinutes: null, dietary: [], allergens: [], imageKey: null,
  ...extra,
});
const ITEMS = [
  item('O1', 'product', 'Country sourdough', { onTonightsRun: true, imageKey: 'media:IMG1' }),
  item('S1', 'service', 'Brake pads', { merchant: merchant('Prairie Wrench', 'provider', 'prairie-wrench'), category: { id: 'service.automotive.mobile-mechanic', name: 'Mobile mechanic' }, priceCents: null, pricingMode: 'quote', rating: 4.9, reviewCount: 120, instantBook: true, trustTier: 'master' }),
  item('F1', 'food', 'Pho tai', { merchant: merchant('Pho Dau Bo', 'kitchen', 'pho-dau-bo', 'trusted'), category: { id: 'food.vietnamese', name: 'Vietnamese' }, priceCents: 1650, openNow: true, distanceKm: 1.24 }),
  item('M1', 'merchant', 'Sidewalk Citizen', { merchant: merchant('Sidewalk Citizen', 'seller', 'sidewalk-citizen', 'trusted'), category: null, priceCents: 450, trustTier: 'trusted' }),
];
const FACETS = {
  kinds: [{ value: 'product', label: null, count: 2 }, { value: 'service', label: null, count: 1 }, { value: 'food', label: null, count: 1 }],
  categories: [{ value: 'shop.groceries.bakery', label: 'Bakery', count: 2 }],
  merchants: [{ value: 'm-Glenmore Bakery', label: 'Glenmore Bakery', count: 3 }, { value: 'm-Sidewalk Citizen', label: 'Sidewalk Citizen', count: 1 }],
  tiers: [], prices: [], dietary: [],
};
const PAGE = { items: ITEMS, total: 4, facets: FACETS, next: null };

type Reply = { status?: number; body?: unknown } | undefined;
let api: (call: Call) => Reply;
const server = (call: Call): Reply => {
  if (call.url === '/bff/session') return { body: { user: null, guestId: 'g_x' } };
  if (call.url.startsWith('/api/v1/geo/markets')) return { body: { items: [], fallback: null } };
  return api(call);
};
const searches = (calls: Call[]) => calls.filter(c => c.url.startsWith('/api/v1/search?')).map(c => new URL(c.url, 'http://x').searchParams);

function Route() {
  return <SearchResults params={SearchParams.parse(useSearch({ strict: false }))} />;
}
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp(path, { locale, routes: { search: () => <Route /> } });
  return { calls, ...view };
};

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  api = c => (c.url.startsWith('/api/v1/search?') ? { body: PAGE } : undefined);
});
afterEach(() => vi.unstubAllGlobals());

describe('search results (redesign § 1.3 results page)', () => {
  it('shows products and services together, with the summary bar, the facets and the cards', async () => {
    const { calls } = open('/search?q=sourdough');
    expect(await screen.findByRole('heading', { level: 1, name: '4 results for “sourdough”' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const filters = screen.getByRole('complementary', { name: 'Filters' });
    const type = within(filters).getByRole('region', { name: 'Type' });
    expect(within(type).getByRole('radio', { name: 'Products and services' })).toBeChecked();
    expect(within(type).getByRole('radio', { name: /Products 2/ })).not.toBeChecked();
    expect(within(type).getByRole('radio', { name: /Services 1/ })).toBeInTheDocument();
    // § 3.7: Food isn't its own type for the demo
    expect(within(type).queryByRole('radio', { name: /Food/ })).toBeNull();
    expect(within(filters).getByRole('checkbox', { name: /Bakery 2/ })).not.toBeChecked();
    expect(within(filters).getByRole('region', { name: 'Distance' })).toHaveTextContent('Set your location to sort and filter by distance.');
    expect(within(filters).getByRole('checkbox', { name: 'Open now' })).toBeInTheDocument();
    expect(within(filters).getByRole('checkbox', { name: 'On tonight’s run' })).toBeInTheDocument();
    expect(within(filters).getByRole('radio', { name: 'Under $10' })).toBeInTheDocument();
    // "Sold by" / tiers are gone (§ 3.13)
    expect(within(filters).queryByText('Glenmore Bakery · 3')).toBeNull();
    expect(within(filters).queryByRole('checkbox', { name: /Master/ })).toBeNull();
    expect(screen.getByRole('combobox', { name: 'Sorted by' })).toHaveValue('relevance');

    const results = screen.getByRole('list', { name: 'Results' });
    const sourdough = within(results).getByRole('article', { name: 'Country sourdough' });
    expect(within(sourdough).getByRole('link', { name: 'Country sourdough' })).toHaveAttribute('href', '/products/O1?offer=O1');
    expect(within(sourdough).getByRole('heading', { level: 2 })).toHaveTextContent('Country sourdough');
    expect(sourdough).toHaveTextContent('Glenmore Bakery · Bakery');
    expect(sourdough).toHaveTextContent('$7.50');
    expect(sourdough).toHaveTextContent('Get it tonight');
    expect(sourdough.querySelector('img')).toHaveAttribute('src', '/api/v1/public/catalogue/media/IMG1');
    expect(within(sourdough).getByRole('button', { name: 'Add to cart: Country sourdough' })).toBeEnabled();
    const brakes = within(results).getByRole('link', { name: /Brake pads/ });
    expect(brakes).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(brakes).toHaveTextContent('Mobile mechanic');
    expect(brakes).toHaveTextContent('Prairie Wrench');
    expect(brakes).toHaveTextContent('Quote');
    expect(brakes).toHaveTextContent('Instant book');
    expect(brakes).toHaveTextContent('Verified');
    expect(within(brakes).getByRole('img', { name: '4.9 out of 5 stars' })).toBeInTheDocument();
    expect(within(results).getByRole('article', { name: 'Pho tai' })).toHaveTextContent('Pho Dau Bo · Vietnamese · 1.2 km');
    const shop = within(results).getByRole('link', { name: /Sidewalk Citizen/ });
    expect(shop).toHaveTextContent('Shop');
    expect(shop).toHaveTextContent('from');
    expect(shop).toHaveAttribute('href', '/search?scope=shop&q=Sidewalk+Citizen');

    const q = searches(calls)[0]!;
    expect(q.get('q')).toBe('sourdough');
    expect(q.get('lang')).toBe('en');
    expect(q.has('kind')).toBe(false);
    expect(q.get('size')).toBe('24');
  });

  it('adds to the cart from a card, then shows the stepper', async () => {
    let cart = { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] as unknown[] };
    const user = userEvent.setup({ delay: null });
    api = c => {
      if (c.url.startsWith('/api/v1/search?')) return { body: PAGE };
      if (c.method === 'POST' && c.url === '/api/v1/cart/items') {
        cart = { itemCount: 1, shopCount: 1, subtotalCents: 750, groups: [{ merchantId: 'm', shopName: 'Glenmore Bakery', items: [{ itemId: 'I1', merchantId: 'm', offerId: 'O1', variantId: null, productId: 'P1', name: 'Country sourdough', option: null, unit: null, imageUrl: null, unitCents: 750, qty: 1, lineCents: 750, stock: 5, available: true, handlingDays: 0 }] }] };
        return { status: 201, body: {} };
      }
      if (c.url === '/api/v1/cart') return { body: cart };
      return undefined;
    };
    const { calls } = open('/search?q=sourdough');
    const card = await screen.findByRole('article', { name: 'Country sourdough' });
    await user.click(within(card).getByRole('button', { name: 'Add to cart: Country sourdough' }));
    expect(calls.find(c => c.method === 'POST' && c.url === '/api/v1/cart/items')?.body).toEqual({ offerId: 'O1', qty: 1 });
    expect(await within(card).findByRole('group', { name: 'Quantity of Country sourdough' })).toHaveTextContent('1 in cart');
    expect(await screen.findByRole('link', { name: 'Cart, 1 item' })).toBeInTheDocument();
  });

  it('speaks French', async () => {
    open('/search?q=levain', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: '4 résultats pour « levain »' })).toBeInTheDocument();
    const filters = screen.getByRole('complementary', { name: 'Filtres' });
    expect(within(filters).getByRole('radio', { name: 'Produits et services' })).toBeChecked();
    expect(within(filters).getByRole('radio', { name: /Moins de 10\s\$/ })).toBeInTheDocument();
    expect(within(filters).getByRole('checkbox', { name: 'Sur la tournée de ce soir' })).toBeInTheDocument();
    expect(screen.getByText('Trié par')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Brake pads/ })).toHaveTextContent('Devis');
    expect(screen.getByRole('button', { name: 'Ajouter au panier : Country sourdough' })).toBeInTheDocument();
  });

  it('filters, sorts and clears through the URL', async () => {
    const user = userEvent.setup({ delay: null });
    const { calls, router } = open('/search?q=bread');
    await screen.findByRole('heading', { level: 1, name: /4 results/ });
    await user.click(screen.getByRole('checkbox', { name: 'On tonight’s run' }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ q: 'bread', delivery: 'tonight' }));
    await user.click(screen.getByRole('radio', { name: 'Under $10' }));
    await user.click(screen.getByRole('checkbox', { name: 'Open now' }));
    await user.click(screen.getByRole('checkbox', { name: /Bakery 2/ }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sorted by' }), 'price_asc');
    await waitFor(() => {
      const q = searches(calls).at(-1)!;
      expect(q.get('delivery')).toBe('tonight');
      expect(q.get('maxPrice')).toBe('999');
      expect(q.get('openNow')).toBe('true');
      expect(q.get('category')).toBe('shop.groceries.bakery');
      expect(q.get('sort')).toBe('price_asc');
    });
    expect(screen.getByRole('checkbox', { name: 'On tonight’s run' })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Clear all' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'bread', sort: 'price_asc' }));
  });

  it('narrows to one type with its own filters', async () => {
    const user = userEvent.setup({ delay: null });
    const { calls, router } = open('/search?q=fix');
    await screen.findByRole('heading', { level: 1, name: /4 results/ });
    await user.click(screen.getByRole('radio', { name: /Services 1/ }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'fix', scope: 'services' }));
    await user.click(await screen.findByRole('checkbox', { name: 'Instant book' }));
    await user.click(screen.getByRole('radio', { name: 'Under $100' }));
    await waitFor(() => {
      const q = searches(calls).at(-1)!;
      expect(q.get('kind')).toBe('service');
      expect(q.get('instantBook')).toBe('true');
      expect(q.get('maxPrice')).toBe('9999');
    });
    expect(screen.queryByRole('checkbox', { name: 'On tonight’s run' })).toBeNull();
  });

  it('adds the visitor’s province and coordinates, and offers distance', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '12 Main St, Riverton', city: 'Riverton', lat: 50.1, lng: -110.2, province: 'AB' }));
    const user = userEvent.setup({ delay: null });
    const { calls } = open('/search?q=pho');
    await waitFor(() => expect(searches(calls).some(q => q.get('market') === 'AB' && q.get('lat') === '50.10000' && q.get('lng') === '-110.20000')).toBe(true));
    await user.click(await screen.findByRole('radio', { name: 'Under 10 km' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sorted by' }), 'distance');
    await waitFor(() => {
      const q = searches(calls).at(-1)!;
      expect(q.get('radiusKm')).toBe('10');
      expect(q.get('sort')).toBe('distance');
    });
  });

  it('leaves distance out without a location and says why', async () => {
    const { calls } = open('/search?q=pho&sort=distance&radiusKm=3');
    expect(await screen.findByText('Set your location to sort and filter by distance.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set location' })).toBeInTheDocument(); // the location dialog, in place
    const q = searches(calls).at(-1)!;
    expect(q.has('sort')).toBe(false);
    expect(q.has('radiusKm')).toBe(false);
    expect(screen.queryByRole('radio', { name: 'Under 10 km' })).toBeNull();
  });

  it('pages with the API’s next token until everything is seen', async () => {
    const user = userEvent.setup({ delay: null });
    api = c => {
      if (!c.url.startsWith('/api/v1/search?')) return undefined;
      return new URL(c.url, 'http://x').searchParams.get('after') === 'relevance.abc'
        ? { body: { items: [item('O9', 'product', 'Seeded rye')], total: 5, facets: null, next: null } }
        : { body: { ...PAGE, total: 5, next: 'relevance.abc' } };
    };
    open('/search?q=bread');
    expect(await screen.findByText('Showing 4 of 5')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Show more results' }));
    expect(await screen.findByRole('article', { name: 'Seeded rye' })).toBeInTheDocument();
    expect(screen.getByText('You’ve seen all 5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show more results' })).toBeNull();
  });

  it('has an empty state with one action', async () => {
    const user = userEvent.setup({ delay: null });
    api = c => (c.url.startsWith('/api/v1/search?') ? { body: { items: [], total: 0, facets: { ...FACETS, kinds: [], categories: [], merchants: [] }, next: null } } : undefined);
    const { router } = open('/search?q=zzz');
    expect(await screen.findByText('No results.')).toBeInTheDocument();
    expect(screen.getByText('Nothing matches “zzz” here yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse services' })).toHaveAttribute('href', '/services');
    await router.navigate({ to: '/search', search: { q: 'zzz', delivery: 'tonight' } as never });
    expect(await screen.findByText('Try clearing a filter, or search for something broader.')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'zzz' }));
  });

  it('shows errors in the page with Retry', async () => {
    const user = userEvent.setup({ delay: null });
    let fail: Reply = { status: 503, body: { detail: 'down' } };
    api = c => (c.url.startsWith('/api/v1/search?') ? fail ?? { body: PAGE } : undefined);
    open('/search?q=bread');
    expect(await screen.findByText('Search isn’t answering right now.')).toBeInTheDocument();
    fail = undefined;
    await user.click(screen.getByRole('button', { name: /Retry/ }));
    expect(await screen.findByRole('heading', { level: 1, name: '4 results for “bread”' })).toBeInTheDocument();
  });

  it('says so when rate limited', async () => {
    api = c => (c.url.startsWith('/api/v1/search?') ? { status: 429, body: { code: 'rate_limited' } } : undefined);
    open('/search?q=bread', 'fr');
    expect(await screen.findByText('Trop de recherches à la fois — réessayez dans une minute.')).toBeInTheDocument();
  });

  it('remembers what was searched', async () => {
    open('/search?q=cinnamon buns');
    await screen.findByRole('heading', { level: 1, name: /cinnamon buns/ });
    expect(JSON.parse(localStorage.getItem(RECENT_KEY)!)[0].q).toBe('cinnamon buns');
  });
});

describe('predictions as you type (header and hero)', () => {
  const SUGGEST = { items: [
    { text: 'Country sourdough', type: 'product', id: 'O1', merchantId: 'M1', merchantName: 'Glenmore Bakery', merchantType: 'seller', merchantSlug: 'glenmore-bakery', priceCents: 750, trustTier: 'master', rating: 4.8, highlight: [{ start: 8, length: 4 }] },
    { text: 'Glenmore Bakery', type: 'merchant', id: 'M1', merchantId: 'M1', merchantName: 'Glenmore Bakery', merchantType: 'seller', merchantSlug: 'glenmore-bakery', priceCents: null, trustTier: 'master', rating: 4.8, highlight: [] },
    { text: 'Mobile mechanic', type: 'category', id: 'service.automotive.mobile-mechanic', merchantId: null, merchantName: null, merchantType: null, merchantSlug: null, priceCents: null, trustTier: null, rating: null, highlight: [{ start: 0, length: 4 }] },
  ] };
  beforeEach(() => {
    api = c => (c.url.startsWith('/api/v1/search/suggest?') ? { body: SUGGEST } : c.url.startsWith('/api/v1/search?') ? { body: PAGE } : undefined);
  });

  it('suggests listings, businesses and categories, then your recent searches', async () => {
    localStorage.setItem(RECENT_KEY, JSON.stringify([{ q: 'mobile mechanic', at: Date.now() - 86_400_000 }, { q: 'cinnamon buns', at: Date.now() }]));
    const user = userEvent.setup({ delay: null });
    const { calls } = open('/shop');
    const box = await screen.findByRole('combobox', { name: 'Search' });
    await user.type(box, 'sour');
    const list = await screen.findByRole('listbox', { name: 'Search suggestions' });
    await waitFor(() => expect(within(list).getAllByRole('option')).toHaveLength(5));
    const [first, shop, category, recent] = within(list).getAllByRole('option');
    expect(first).toHaveTextContent('Country sourdoughGlenmore Bakery · $7.50');
    expect(first!.querySelector('strong')).toHaveTextContent('sour');
    expect(shop).toHaveTextContent('Glenmore BakeryShop · Master tier · ★ 4.8');
    expect(category).toHaveTextContent('Mobile mechanicService');
    expect(recent).toHaveTextContent('Recent: mobile mechanicsearched yesterday');
    expect(within(list).getByText('Suggestions')).toBeInTheDocument();
    expect(within(list).getByText('Your recent')).toBeInTheDocument();
    const s = new URL(calls.find(c => c.url.startsWith('/api/v1/search/suggest?'))!.url, 'http://x').searchParams;
    expect(s.get('q')).toBe('sour');
    expect(s.get('lang')).toBe('en');
  });

  it('opens a suggestion with the keyboard and searches on Enter', async () => {
    const user = userEvent.setup({ delay: null });
    const { router } = open('/shop');
    const box = await screen.findByRole('combobox', { name: 'Search' });
    await user.type(box, 'mob');
    // (the bold letters split the option's text, so find it by its content)
    const mechanic = await screen.findByText((_, el) => el?.getAttribute('role') === 'option' && /^Mobile mechanic/.test(el.textContent ?? ''));
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}');
    expect(box).toHaveAttribute('aria-activedescendant', mechanic.id);
    await user.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/services/mobile-mechanic'));

    await user.type(screen.getByRole('combobox', { name: 'Search' }), 'rye{Escape}');
    expect(screen.getByRole('listbox', { name: 'Search suggestions', hidden: true })).not.toBeVisible();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toEqual({ q: 'rye' });
    expect(JSON.parse(localStorage.getItem(RECENT_KEY)!)[0].q).toBe('rye');
  });

  it('suggests in French with the visitor’s province', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '12 Main St, Riverton', city: 'Riverton', province: 'BC' }));
    localStorage.setItem(RECENT_KEY, JSON.stringify([{ q: 'brioche', at: Date.now() - 3 * 86_400_000 }]));
    const user = userEvent.setup({ delay: null });
    const { calls } = open('/shop', 'fr');
    await user.type(await screen.findByRole('combobox', { name: 'Rechercher' }), 'sour');
    const list = await screen.findByRole('listbox', { name: 'Suggestions de recherche' });
    expect(await within(list).findByText('Vos recherches récentes')).toBeInTheDocument();
    expect(within(list).getByRole('option', { name: /Récent : brioche/ })).toHaveTextContent('recherché il y a 3 jours');
    await waitFor(() => expect(calls.some(c => c.url.includes('/search/suggest?') && c.url.includes('market=BC') && c.url.includes('lang=fr'))).toBe(true));
  });
});

describe('links and the API query', () => {
  it('maps every kind to its page', () => {
    expect(itemHref(item('O1', 'product', 'x') as SearchItem)).toBe('/products/O1?offer=O1');
    expect(itemHref(item('M2', 'merchant', 'x', { merchant: merchant('A', 'both', 'a-b') }) as SearchItem)).toBe('/providers/a-b');
    expect(itemHref(item('M3', 'merchant', 'x', { merchant: merchant('K', 'kitchen', 'k-k') }) as SearchItem)).toBe('/food/k-k');
    expect(categoryHref('shop.groceries.bakery')).toBe('/shop/bakery');
    expect(categoryHref('food.vietnamese')).toBe('/search?category=food.vietnamese');
    expect(suggestionHref({ text: 'Pho tai', type: 'food', id: 'F1', merchantSlug: 'pho', highlight: [] })).toBe('/food/pho');
  });

  it('builds the query from the page and the place', () => {
    const qs = new URLSearchParams(apiQuery({ q: ' sourdough ', scope: 'shop', sort: 'distance', radiusKm: 3 }, { market: 'AB', lat: 51, lng: -114 }, 'fr'));
    expect(Object.fromEntries(qs)).toEqual({ q: 'sourdough', market: 'AB', lang: 'fr', kind: 'product', lat: '51.00000', lng: '-114.00000', radiusKm: '3', sort: 'distance', size: '24' });
    expect(apiQuery({ sort: 'relevance' }, null, 'en')).toBe('lang=en&size=24');
  });

  it('drops malformed parameters', () => {
    expect(SearchParams.parse({ q: 'x', scope: 'nope', maxPrice: -5, openNow: 'yes', dietary: 'Halal!', sort: 'cheap' })).toEqual({ q: 'x' });
  });
});
