import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { HomeScreen } from './HomeScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { brand } from '@northline/ui';

const merchant = (name: string, type: string, slug: string | null) => ({ id: `m-${name}`, name, type, slug, tier: 'master' });
const item = (id: string, kind: string, name: string, extra: Record<string, unknown> = {}) => ({
  id, kind, name, description: null, merchant: merchant('Glenmore Bakery', 'seller', null), category: { id: 'shop.food-and-grocery.bakery', name: 'Bakery' },
  priceCents: 750, pricingMode: 'fixed', rating: null, reviewCount: 0, trustTier: 'registered', distanceKm: null, instantBook: false,
  fulfilment: ['pooled'], openNow: false, soldOut: false, onTonightsRun: false, prepMinutes: null, dietary: [], imageKey: null, ...extra,
});
const PRODUCTS = { items: [item('O1', 'product', 'Country sourdough', { onTonightsRun: true, rating: 4.6, reviewCount: 18 }), item('O2', 'product', 'Baguette', { priceCents: 640 })], total: 2, facets: null, next: null };
const SERVICES = {
  items: [item('S1', 'service', 'Brake pads', { merchant: merchant('Prairie Wrench', 'provider', 'prairie-wrench'), category: { id: 'service.automotive.mobile-mechanic', name: 'Mobile mechanic' }, priceCents: 18900, rating: 4.9, reviewCount: 312, trustTier: 'master' })],
  total: 1, facets: null, next: null,
};
const EMPTY = { items: [], total: 0, facets: null, next: null };

const RUN = { windowId: 'W1', label: 'R-1', day: 'today', startsAt: '2026-10-20T00:00:00Z', endsAt: '2026-10-20T03:00:00Z', orderBy: '2026-10-19T23:20:00Z', feeCents: 299, households: 5 };
const shopCard = (id: string, name: string, tier: string, extra: Record<string, unknown> = {}) => ({ merchantId: id, name, tier, departmentSlug: 'bakery', departmentName: 'Bakery', products: 14, run: RUN, imageUrl: null, ...extra });
const LANDING = {
  market: 'Calgary', served: true, run: RUN, shopCount: 2, departments: [{ slug: 'bakery', name: 'Bakery', shops: 2 }],
  shops: [shopCard('M2', 'Sidewalk Citizen', 'trusted'), shopCard('M1', 'Glenmore Bakery', 'master', { imageUrl: '/photo/loaf.jpg' })],
  popular: [{ productId: 'P1', offerId: 'O1', name: 'Country sourdough', merchantId: 'M1', shopName: 'Glenmore Bakery', unit: '900 g', priceCents: 750, imageUrl: null, sellers: 1, run: RUN }],
};
const PRODUCT = { productId: 'P1', name: 'Country sourdough', bullets: [], departmentSlug: 'bakery', departmentName: 'Bakery', market: 'Calgary', served: true,
  offers: [{ offerId: 'O1', merchantId: 'M1', shopName: 'Glenmore Bakery', tier: 'master', rating: 4.8, ratingCount: 2, priceCents: 750, compareAtCents: 900, stock: 4, lowStock: false, variantTheme: 'none', variants: [], runs: [], images: [], more: [] }] };
const KITCHENS = { city: 'Calgary', items: [{ merchantId: 'K1', slug: 'pho-lucky', name: 'Pho Lucky', cuisines: ['vietnamese'], dietary: [], priceLevel: '$', open: true, paused: false, fulfilment: ['courier'], prepMin: 15, etaFromMin: 25, etaToMin: 35, pickupFromMin: 15, pickupToMin: 20, deliveryFeeCents: 299, rating: 4.8, reviews: 120 }] };

function api(opts: { products?: unknown; services?: unknown; status?: number; user?: unknown; activity?: unknown } = {}) {
  return (c: Call) => {
    if (c.url === '/bff/session') return { body: { user: opts.user ?? null, guestId: 'g_1' } };
    if (c.url.startsWith('/api/v1/search?')) {
      const kind = new URL(c.url, 'http://x').searchParams.get('kind');
      if (opts.status) return { status: opts.status, body: {} };
      return { body: kind === 'service' ? opts.services ?? SERVICES : opts.products ?? PRODUCTS };
    }
    if (c.url.startsWith('/api/v1/public/shop?')) return { body: LANDING };
    if (c.url.startsWith('/api/v1/public/shop/products/')) return { body: PRODUCT };
    if (c.url.startsWith('/api/v1/public/kitchens')) return { body: KITCHENS };
    if (c.url === '/api/v1/me/activity') return { body: { items: opts.activity ?? [] } };
    if (c.url === '/api/v1/cart') return { body: { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } };
    return undefined;
  };
}
const home = { home: () => <HomeScreen /> };

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Beltline, Calgary', city: 'Calgary', province: 'AB' }));
});
afterEach(() => vi.unstubAllGlobals());

describe('home (E-15: maker of the week, home tiles, rails)', () => {
  it('features one local shop, then the home tiles and rails of real products, pros and kitchens', async () => {
    const calls = mockFetch(api());
    renderApp('/', { routes: home });
    // S-188: the hero band's headline names the visitor's place (a message parameter); one search on the page — the
    // header's, with its scope
    expect(await screen.findByRole('heading', { level: 1, name: 'Everything Beltline needs, tonight.' })).toBeInTheDocument();
    const band = screen.getByRole('region', { name: 'Everything Beltline needs, tonight.' });
    expect(within(band).getByText('Payment held until you confirm delivery')).toBeInTheDocument();
    const depts = within(band).getByRole('list', { name: 'Departments' });
    expect(within(depts).getAllByRole('link').map(a => a.getAttribute('href'))).toEqual(['/services', '/shop', '/food']);
    await waitFor(() => expect(within(depts).getByRole('link', { name: /Shop local/ })).toHaveTextContent('2 stores · one pooled delivery'));
    // the run card replaces the cut-off strip on the home: no strip here
    expect(await within(band).findByRole('complementary', { name: 'Next pooled run' })).toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: 'Delivery cut-off' })).toBeNull();
    expect(brand.name).toBeTruthy();
    expect(screen.getAllByRole('search')).toHaveLength(1);
    expect(screen.getByRole('banner')).toContainElement(screen.getByRole('search'));
    // the maker of the week: the Master shop with a photo, on tonight's run
    const hero = await screen.findByRole('region', { name: 'Glenmore Bakery, on tonight’s pooled run' });
    expect(within(hero).getByText('Maker of the week · Bakery')).toBeInTheDocument();
    expect(within(hero).getByText(/14 products from one of Calgary’s Master shops in Bakery\. Order by/)).toBeInTheDocument();
    expect(within(hero).getByRole('link', { name: 'Shop Glenmore Bakery' })).toHaveAttribute('href', '/search?scope=shop&q=Glenmore+Bakery');
    expect(within(hero).getByRole('link', { name: 'More bakery' })).toHaveAttribute('href', '/shop/bakery');
    // the side tiles: the top-rated service and the kitchens open now
    expect(await within(hero).findByRole('link', { name: /Brake pads.*From \$189\.00 · Prairie Wrench/ })).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(await within(hero).findByRole('link', { name: /1 kitchen open now.*Pho Lucky delivers in 25–35 min/ })).toHaveAttribute('href', '/food');

    // the home tiles: departments, services, kitchens, and the deal of the day (an offer with an earlier price)
    const shop = screen.getByRole('region', { name: 'Shop local departments' });
    expect(within(shop).getByRole('link', { name: 'Bakery' })).toHaveAttribute('href', '/shop/bakery');
    expect(within(shop).getByRole('link', { name: 'Shop all departments' })).toHaveAttribute('href', '/shop');
    expect(within(screen.getByRole('region', { name: 'Book trusted pros' })).getByRole('link', { name: 'Mobile mechanic' })).toHaveAttribute('href', '/services/mobile-mechanic');
    expect(within(screen.getByRole('region', { name: 'Kitchens open now' })).getByRole('link', { name: 'Vietnamese' })).toHaveAttribute('href', '/food?cuisine=vietnamese');
    const deal = await screen.findByRole('region', { name: 'Deal of the day' });
    expect(within(deal).getByText('17% off')).toBeInTheDocument();
    expect(deal).toHaveTextContent('$7.50');
    expect(deal).toHaveTextContent('Was $9.00');

    const products = await screen.findByRole('region', { name: 'Popular near Beltline' });
    const sourdough = await within(products).findByRole('article', { name: 'Country sourdough' });
    expect(within(sourdough).getByRole('link', { name: 'Country sourdough' })).toHaveAttribute('href', '/products/O1?offer=O1');
    expect(within(sourdough).getByRole('img', { name: '4.6 out of 5 stars' })).toBeInTheDocument();
    expect(sourdough).toHaveTextContent('Get it Tonight');
    expect(within(sourdough).getByRole('button', { name: 'Add to cart: Country sourdough' })).toBeInTheDocument();
    const pros = screen.getByRole('region', { name: 'Top-rated pros near Beltline' });
    const wrench = await within(pros).findByRole('link', { name: /Brake pads/ });
    expect(wrench).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(within(await screen.findByRole('region', { name: 'Popular kitchens tonight' })).getByRole('link', { name: /Pho Lucky/ })).toHaveAttribute('href', '/food/pho-lucky');
    // guests have no "Order again"
    expect(screen.queryByRole('region', { name: 'Order again' })).toBeNull();
    const searches = calls.filter(c => c.url.startsWith('/api/v1/search?')).map(c => new URL(c.url, 'http://x').searchParams);
    expect(searches.map(s => s.get('kind')).sort()).toEqual(['product', 'service']);
    expect(searches.find(s => s.get('kind') === 'service')!.get('sort')).toBe('rating');
    expect(searches[0]!.get('market')).toBe('AB');
    await expectNoAxeViolations(document.body); // S-109
  });

  it('offers the products of the shops the signed-in person ordered from again', async () => {
    mockFetch(api({
      products: { ...PRODUCTS, items: [PRODUCTS.items[0], { ...PRODUCTS.items[1], merchant: merchant('Bow Valley Dairy', 'seller', null) }] },
      user: { id: 'U1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO' },
      activity: [{ id: 'A1', kind: 'order', title: 'Order', with: ['Glenmore Bakery'], shops: 1, items: 2, when: '2026-10-10T18:00:00Z', amountCents: 1500, status: 'delivered', active: false }],
    }));
    renderApp('/', { routes: home });
    const again = await screen.findByRole('region', { name: 'Order again' });
    expect(await within(again).findByRole('article', { name: 'Country sourdough' })).toBeInTheDocument();
    expect(within(again).queryByRole('article', { name: 'Baguette' })).toBeNull();
    expect(within(again).getByRole('link', { name: 'Your orders: Order again' })).toHaveAttribute('href', '/account/orders');
  });

  it('offers another location where nothing is listed yet', async () => {
    mockFetch(api({ products: EMPTY, services: EMPTY }));
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Toronto', city: 'Toronto', province: 'ON' }));
    renderApp('/', { routes: home });
    expect(await screen.findByText('Nothing near Toronto yet.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Choose another location' })).toBeInTheDocument();
  });

  it('shows an error with Retry when the listings can’t load', async () => {
    let fail = true;
    mockFetch(c => (c.url.startsWith('/api/v1/search?') && fail ? { status: 500, body: {} } : api()(c)));
    const u = userEvent.setup({ delay: null });
    renderApp('/', { routes: home });
    const alerts = await screen.findAllByRole('alert', {}, { timeout: 8000 });
    expect(alerts[0]).toHaveTextContent('We couldn’t load what’s near you.');
    fail = false;
    await u.click(within(alerts[0]!).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('article', { name: 'Country sourdough' })).toBeInTheDocument();
  });

  it('speaks French', async () => {
    mockFetch(api());
    renderApp('/', { routes: home, locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Tout ce dont Beltline a besoin, ce soir.' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Réserver un pro/ })).toHaveAttribute('href', '/services');
    expect(await screen.findByRole('region', { name: 'Glenmore Bakery, sur la tournée partagée de ce soir' })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Les rayons du quartier' })).getByRole('link', { name: 'Boulangerie' })).toHaveAttribute('href', '/shop/bakery');
    expect(await screen.findByRole('region', { name: 'Aubaine du jour' })).toHaveTextContent('17 % de rabais');
    const products = await screen.findByRole('region', { name: 'Populaire près de Beltline' });
    await waitFor(() => expect(within(products).getByRole('article', { name: 'Country sourdough' })).toHaveTextContent(/Livré ce soir/));
    expect(within(products).getByRole('img', { name: '4,6 sur 5 étoiles' })).toBeInTheDocument();
  });

  it('waits for the place before asking for listings, in boxes drawn at their size', async () => {
    localStorage.clear();
    const calls = mockFetch(api());
    renderApp('/', { routes: home, geolocation: { getCurrentPosition: () => {} } as unknown as Geolocation });
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(calls.some(c => c.url.startsWith('/api/v1/search?'))).toBe(false);
    expect(document.querySelector('.nl-hero-media')).not.toBeNull();
  });
});
