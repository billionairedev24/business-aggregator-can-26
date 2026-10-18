import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { HomeScreen } from './HomeScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

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

function api(opts: { products?: unknown; services?: unknown; status?: number } = {}) {
  return (c: Call) => {
    if (c.url === '/bff/session') return { body: { user: null, guestId: 'g_1' } };
    if (c.url.startsWith('/api/v1/search?')) {
      const kind = new URL(c.url, 'http://x').searchParams.get('kind');
      if (opts.status) return { status: opts.status, body: {} };
      return { body: kind === 'service' ? opts.services ?? SERVICES : opts.products ?? PRODUCTS };
    }
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

describe('home (redesign § 1.3)', () => {
  it('opens on the hero carousel, the tiles and rails of real products and services', async () => {
    const calls = mockFetch(api());
    renderApp('/', { routes: home });
    expect(await screen.findByRole('heading', { level: 1, name: 'Book trusted local pros' })).toBeInTheDocument();
    // § 3.2: no greeting, no "what do you need" question, no promises, no "Your week", no tier tags
    expect(screen.queryByText(/^Good (morning|afternoon|evening)/)).toBeNull();
    expect(screen.queryByText(/What do you need/)).toBeNull();
    expect(screen.queryByText('Paid into escrow')).toBeNull();
    expect(screen.queryByText('Your week')).toBeNull();

    const tiles = screen.getByRole('list', { name: 'Browse' });
    expect(within(tiles).getByRole('heading', { level: 2, name: 'Book a service' })).toBeInTheDocument();
    expect(within(tiles).getByRole('link', { name: 'Mobile mechanic' })).toHaveAttribute('href', '/services/mobile-mechanic');
    // § 3.7: food is a department of the Shop, not a vertical of its own
    expect(within(tiles).getByRole('heading', { name: 'Groceries & fresh food' })).toBeInTheDocument();
    expect(within(tiles).getByRole('link', { name: 'Bakery' })).toHaveAttribute('href', '/shop/bakery');
    expect(within(tiles).queryByRole('link', { name: /Vietnamese/ })).toBeNull();
    expect(within(tiles).getByRole('link', { name: 'See more: Book a service' })).toHaveAttribute('href', '/services');

    const products = await screen.findByRole('region', { name: 'Popular near Beltline' });
    const sourdough = await within(products).findByRole('article', { name: 'Country sourdough' });
    expect(within(sourdough).getByRole('link', { name: 'Country sourdough' })).toHaveAttribute('href', '/products/O1?offer=O1');
    expect(within(sourdough).getByRole('img', { name: '4.6 out of 5 stars' })).toBeInTheDocument();
    expect(sourdough).toHaveTextContent('Get it tonight');
    const pros = screen.getByRole('region', { name: 'Pros near Beltline' });
    const wrench = await within(pros).findByRole('link', { name: /Brake pads/ });
    expect(wrench).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(wrench).toHaveTextContent('Prairie Wrench');
    expect(wrench).not.toHaveTextContent('Master');
    const searches = calls.filter(c => c.url.startsWith('/api/v1/search?')).map(c => new URL(c.url, 'http://x').searchParams);
    expect(searches.map(s => s.get('kind')).sort()).toEqual(['product', 'service']);
    expect(searches[0]!.get('market')).toBe('AB');
    await expectNoAxeViolations(document.body); // S-109
  });

  it('moves through the slides with the arrows and the dots', async () => {
    mockFetch(api());
    const u = userEvent.setup({ delay: null });
    renderApp('/', { routes: home });
    const hero = await screen.findByRole('region', { name: 'Featured' });
    await u.click(within(hero).getByRole('button', { name: 'Next slide' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Your neighbourhood shops, delivered together' })).toBeInTheDocument();
    expect(within(hero).getByRole('link', { name: 'Start shopping' })).toHaveAttribute('href', '/shop');
    await u.click(within(hero).getByRole('button', { name: 'Previous slide' }));
    await u.click(within(hero).getByRole('button', { name: 'Previous slide' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Bigger job? Get itemized quotes' })).toBeInTheDocument();
    await u.click(within(hero).getByRole('button', { name: 'Slide 1 of 3' }));
    expect(within(hero).getByRole('button', { name: 'Slide 1 of 3' })).toHaveAttribute('aria-current', 'true');
  });

  it('offers another location where nothing is listed yet', async () => {
    mockFetch(api({ products: EMPTY, services: EMPTY }));
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Toronto', city: 'Toronto', province: 'ON' }));
    renderApp('/', { routes: home });
    expect(await screen.findByText('Nothing near Toronto yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Choose another location' })).toHaveAttribute('href', '/location');
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
    expect(await screen.findByRole('heading', { level: 1, name: 'Réservez des pros de confiance près de chez vous' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Épicerie et produits frais' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Boulangerie' })).toHaveAttribute('href', '/shop/bakery');
    const products = await screen.findByRole('region', { name: 'Populaire près de Beltline' });
    await waitFor(() => expect(within(products).getByRole('article', { name: 'Country sourdough' })).toHaveTextContent('Livré ce soir'));
    expect(within(products).getByRole('img', { name: '4,6 sur 5 étoiles' })).toBeInTheDocument();
  });

  it('waits for the place before asking for listings', async () => {
    localStorage.clear();
    const calls = mockFetch(api());
    renderApp('/', { routes: home, geolocation: { getCurrentPosition: () => {} } as unknown as Geolocation });
    expect(await screen.findByRole('heading', { level: 1, name: 'Book trusted local pros' })).toBeInTheDocument();
    expect(calls.some(c => c.url.startsWith('/api/v1/search?'))).toBe(false);
  });
});
