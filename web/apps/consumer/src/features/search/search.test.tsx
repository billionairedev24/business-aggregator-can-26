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

describe('search results (design 06 search)', () => {
  it('shows the results with the design’s heading, filters, facets and cards', async () => {
    const { calls } = open('/search?q=sourdough');
    expect(await screen.findByRole('heading', { level: 1, name: '“sourdough” · 4 results' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const filters = screen.getByRole('complementary', { name: 'Filters' });
    for (const name of ['On tonight’s run', 'Under $10', 'Master sellers', 'Halal', 'Gluten-free']) {
      expect(within(filters).getByRole('button', { name })).toHaveAttribute('aria-pressed', 'false');
    }
    expect(within(filters).getByRole('heading', { name: 'Filter' })).toBeInTheDocument();
    expect(within(filters).getByRole('button', { name: 'Shop · 2' })).toBeInTheDocument();
    expect(within(filters).getByRole('button', { name: 'Everything' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(filters).getByText('Glenmore Bakery · 3')).toBeInTheDocument();
    expect(within(filters).getByRole('heading', { name: 'Businesses' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Home › Search');
    expect(screen.getByRole('combobox', { name: 'Sorted by' })).toHaveValue('relevance');

    const sourdough = screen.getByRole('link', { name: /Country sourdough/ });
    expect(sourdough).toHaveAttribute('href', '/products/O1?offer=O1');
    expect(sourdough).toHaveTextContent('Country sourdough$7.50Glenmore Bakery · BakeryOn tonight’s run');
    expect(sourdough.querySelector('img')).toHaveAttribute('src', '/api/v1/public/catalogue/media/IMG1');
    expect(screen.getByRole('link', { name: /Brake pads/ })).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(screen.getByRole('link', { name: /Brake pads/ })).toHaveTextContent('Brake padsQuotePrairie Wrench · Mobile mechanic · ★ 4.9 (120)Instant book');
    expect(screen.getByRole('link', { name: /Pho tai/ })).toHaveAttribute('href', '/food/pho-dau-bo');
    expect(screen.getByRole('link', { name: /Pho tai/ })).toHaveTextContent('Pho Dau Bo · Vietnamese · 1.2 km');
    expect(screen.getByRole('link', { name: /Sidewalk Citizen/ })).toHaveTextContent('Sidewalk Citizenfrom $4.50Shop');
    expect(screen.getByRole('link', { name: /Sidewalk Citizen/ })).toHaveAttribute('href', '/search?scope=shop&q=Sidewalk+Citizen');

    const q = searches(calls)[0]!;
    expect(q.get('q')).toBe('sourdough');
    expect(q.get('lang')).toBe('en');
    expect(q.has('kind')).toBe(false);
    expect(q.get('size')).toBe('24');
  });

  it('speaks French', async () => {
    open('/search?q=levain', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: '« levain » · 4 résultats' })).toBeInTheDocument();
    const filters = screen.getByRole('complementary', { name: 'Filtres' });
    for (const name of ['Sur la tournée de ce soir', 'Moins de 10 $', 'Vendeurs Maîtres', 'Halal', 'Sans gluten']) {
      expect(within(filters).getByRole('button', { name })).toBeInTheDocument();
    }
    expect(within(filters).getByRole('heading', { name: 'Filtrer' })).toBeInTheDocument();
    expect(screen.getByText('Trié par')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Brake pads/ })).toHaveTextContent('Devis');
    expect(screen.getByRole('navigation', { name: 'Fil d’Ariane' })).toHaveTextContent('Accueil › Recherche');
  });

  it('filters, sorts and clears through the URL', async () => {
    const user = userEvent.setup({ delay: null });
    const { calls, router } = open('/search?q=bread');
    await screen.findByRole('heading', { level: 1, name: /4 results/ });
    await user.click(screen.getByRole('button', { name: 'On tonight’s run' }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ q: 'bread', delivery: 'tonight' }));
    await user.click(screen.getByRole('button', { name: 'Under $10' }));
    await user.click(screen.getByRole('button', { name: 'Halal' }));
    await user.click(screen.getByRole('button', { name: 'Gluten-free' }));
    await user.click(screen.getByRole('button', { name: 'Bakery · 2' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sorted by' }), 'price_asc');
    await waitFor(() => {
      const q = searches(calls).at(-1)!;
      expect(q.get('delivery')).toBe('tonight');
      expect(q.get('maxPrice')).toBe('999');
      expect(q.get('dietary')).toBe('halal,gluten_free');
      expect(q.get('category')).toBe('shop.groceries.bakery');
      expect(q.get('sort')).toBe('price_asc');
    });
    expect(screen.getByRole('button', { name: 'On tonight’s run' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Clear all' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'bread', sort: 'price_asc' }));
  });

  it('narrows to a scope with its own filters', async () => {
    const user = userEvent.setup({ delay: null });
    const { calls, router } = open('/search?q=fix&scope=services');
    expect(await screen.findByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Services › Search');
    await user.click(screen.getByRole('button', { name: 'Instant book' }));
    await user.click(screen.getByRole('button', { name: 'Available today' }));
    await user.click(screen.getByRole('button', { name: 'Under $80' }));
    await waitFor(() => {
      const q = searches(calls).at(-1)!;
      expect(q.get('kind')).toBe('service');
      expect(q.get('instantBook')).toBe('true');
      expect(q.get('openNow')).toBe('true');
      expect(q.get('maxPrice')).toBe('7999');
    });
    expect(screen.getByRole('heading', { name: 'Providers' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Food' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'fix', scope: 'food' }));
    await user.click(await screen.findByRole('button', { name: 'Nut-free' }));
    await waitFor(() => expect(searches(calls).at(-1)!.get('allergenFree')).toBe('peanuts,tree_nuts'));
  });

  it('adds the visitor’s province and coordinates, and offers distance', async () => {
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: '12 Main St, Riverton', city: 'Riverton', lat: 50.1, lng: -110.2, province: 'AB' }));
    const user = userEvent.setup({ delay: null });
    const { calls } = open('/search?q=pho');
    await waitFor(() => expect(searches(calls).some(q => q.get('market') === 'AB' && q.get('lat') === '50.10000' && q.get('lng') === '-110.20000')).toBe(true));
    await user.click(await screen.findByRole('button', { name: 'Under 10 km' }));
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
    expect(screen.getByRole('link', { name: 'Set location' })).toHaveAttribute('href', '/location?next=/search');
    const q = searches(calls).at(-1)!;
    expect(q.has('sort')).toBe(false);
    expect(q.has('radiusKm')).toBe(false);
    expect(screen.queryByRole('heading', { name: 'Distance' })).toBeNull();
  });

  it('pages with the API’s next token', async () => {
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
    expect(await screen.findByRole('link', { name: /Seeded rye/ })).toBeInTheDocument();
    expect(screen.getByText('Showing 5 of 5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show more results' })).toBeNull();
  });

  it('has an empty state with one action', async () => {
    const user = userEvent.setup({ delay: null });
    api = c => (c.url.startsWith('/api/v1/search?') ? { body: { items: [], total: 0, facets: { ...FACETS, kinds: [], categories: [], merchants: [] }, next: null } } : undefined);
    const { router } = open('/search?q=zzz');
    expect(await screen.findByText('Nothing matches “zzz” here yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse services' })).toHaveAttribute('href', '/services');
    await router.navigate({ to: '/search', search: { q: 'zzz', delivery: 'tonight' } as never });
    await user.click(await screen.findByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'zzz' }));
  });

  it('shows errors in the page with Retry, rate limits and dead page links in their own words', async () => {
    const user = userEvent.setup({ delay: null });
    let fail: Reply = { status: 503, body: { detail: 'down' } };
    api = c => (c.url.startsWith('/api/v1/search?') ? fail ?? { body: PAGE } : undefined);
    open('/search?q=bread');
    expect(await screen.findByText('Search isn’t answering right now.')).toBeInTheDocument();
    fail = undefined;
    await user.click(screen.getByRole('button', { name: /Retry/ }));
    expect(await screen.findByRole('heading', { level: 1, name: '“bread” · 4 results' })).toBeInTheDocument();
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
