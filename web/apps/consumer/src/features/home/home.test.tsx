import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { HomeScreen } from './HomeScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: 'u1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO' };

const SUMMARY = {
  city: 'Calgary', providers: 412, shops: 58, kitchensOpen: 42,
  categories: {
    'shop.food-and-grocery.bakery': 7, 'shop.food-and-grocery.groceries': 18,
    'service.automotive.mobile-mechanic': 14, 'service.education-and-coaching.tutor-k-12': 8, 'service.education-and-coaching.tutor-post-secondary': 4,
  },
  cuisines: { vietnamese: 4, meal_kits: 1 },
  trusted: [
    { merchantId: 'm1', name: 'Prairie Wrench', slug: 'prairie-wrench', tier: 'master', brandColor: '#2f5d3a', category: { id: 'service.automotive.mobile-mechanic', name: 'Mobile mechanic' }, rating: 4.9, reviews: 312 },
    { merchantId: 'm2', name: 'Sable & Soda', slug: null, tier: 'trusted', brandColor: null, category: null, rating: 0, reviews: 0 },
  ],
};

function api(opts: { user?: typeof AMARA | null; summary?: unknown; summaryStatus?: number; upcoming?: unknown; points?: boolean } = {}) {
  return (c: Call) => {
    if (c.url === '/bff/session') return { body: { user: opts.user ?? null, guestId: 'g_1' } };
    if (c.url.startsWith('/api/v1/public/home')) return { status: opts.summaryStatus ?? 200, body: opts.summary ?? SUMMARY };
    if (c.url === '/api/v1/me/upcoming' && opts.upcoming) return { body: opts.upcoming };
    if (c.url === '/api/v1/me/account-summary' && opts.points) return { body: { points: { balance: 12480, valueCents: 12480 } } };
    return undefined;
  };
}

const home = { home: () => <HomeScreen /> };

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Beltline, Calgary', city: 'Calgary' }));
});
afterEach(() => vi.unstubAllGlobals());

describe('home', () => {
  it('asks what the visitor needs where they are, with the city’s numbers', async () => {
    const calls = mockFetch(api());
    renderApp('/', { routes: home });
    expect(await screen.findByRole('heading', { level: 1, name: 'What do you need in Beltline today?' })).toBeInTheDocument();
    expect(await screen.findByText(/^Good (morning|afternoon|evening) · Beltline, Calgary$/)).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const scopes = screen.getByRole('navigation', { name: 'Services, Shop and Food' });
    await waitFor(() => expect(within(scopes).getByRole('link', { name: /Services/ })).toHaveTextContent('Services412 pros'));
    expect(within(scopes).getByRole('link', { name: /Shop/ })).toHaveAttribute('href', '/shop');
    expect(within(scopes).getByRole('link', { name: /Shop/ })).toHaveTextContent('58 shops');
    expect(within(scopes).getByRole('link', { name: /Food/ })).toHaveTextContent('42 open');
    expect(calls.find(c => c.url.startsWith('/api/v1/public/home'))!.url).toBe('/api/v1/public/home?city=Calgary');

    expect(screen.getByRole('heading', { name: 'Shop by department' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Bakery/ })).toHaveAttribute('href', '/shop/bakery');
    expect(screen.getByRole('link', { name: /Bakery/ })).toHaveTextContent('7 shops');
    expect(screen.getByRole('link', { name: /Butcher/ })).toHaveTextContent('0 shops');
    expect(screen.getByRole('link', { name: /Vietnamese/ })).toHaveAttribute('href', '/food?cuisine=vietnamese');
    expect(screen.getByRole('link', { name: /Vietnamese/ })).toHaveTextContent('4 open');
    expect(screen.getByRole('link', { name: /Meal kits/ })).toHaveTextContent('1 open');
    expect(screen.getByRole('link', { name: /^Mobile mechanic/ })).toHaveTextContent('14 nearby · visit');
    expect(screen.getByRole('link', { name: /Tutor/ })).toHaveTextContent('12 nearby · hourly');
    expect(screen.getByRole('link', { name: /Tutor/ })).toHaveAttribute('href', '/services/tutor-k-12');
    expect(screen.getByRole('link', { name: 'All shops on tonight’s run' })).toHaveAttribute('href', '/shop');
    expect(screen.getByRole('link', { name: 'All kitchens' })).toHaveAttribute('href', '/food');
    expect(screen.getByRole('link', { name: 'All categories' })).toHaveAttribute('href', '/services');
    expect(screen.getByRole('heading', { name: 'Paid into escrow' })).toBeInTheDocument();
  });

  it('shows trusted providers, linking the ones with a public page', async () => {
    mockFetch(api());
    renderApp('/', { routes: home });
    const wrench = await screen.findByRole('link', { name: /Prairie Wrench/ });
    expect(wrench).toHaveAttribute('href', '/providers/prairie-wrench');
    expect(wrench).toHaveTextContent('Master');
    expect(wrench).toHaveTextContent('Mobile mechanic');
    expect(wrench).toHaveTextContent('★ 4.9');
    expect(wrench).toHaveTextContent('312 verified reviews');
    expect(screen.getByText('Sable & Soda').closest('a')).toBeNull();
    expect(screen.getByText('New')).toBeInTheDocument();
  });

  it('searches from the hero pill', async () => {
    mockFetch(api());
    const user = userEvent.setup({ delay: null });
    const { router } = renderApp('/', { routes: home });
    await user.type(await screen.findByRole('combobox', { name: 'Search' }), 'sourdough');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toEqual({ q: 'sourdough' });
  });

  it('greets a signed-in person by name and shows their week and points', async () => {
    mockFetch(api({
      user: AMARA, points: true,
      upcoming: { items: [{ id: 'o1', title: 'Grocery run · 3 shops', subtitle: 'Tonight 6–9 pm · pooled', state: 'Packing', tone: 'accent', href: '/orders/o1' }] },
    }));
    renderApp('/', { routes: home });
    expect(await screen.findByText(/^Good (morning|afternoon|evening), Amara · Beltline, Calgary$/)).toBeInTheDocument();
    const row = await screen.findByRole('link', { name: /Grocery run/ });
    expect(row).toHaveAttribute('href', '/orders/o1');
    expect(row).toHaveTextContent('Packing');
    expect(await screen.findByText('12,480 pts')).toBeInTheDocument();
    expect(screen.getByText(/worth \$124\.80/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Wallet' })).toHaveAttribute('href', '/account?tab=wallet');
  });

  it('says the week is empty until orders exist, and asks guests to sign in', async () => {
    mockFetch(api({ user: AMARA }));
    renderApp('/', { routes: home });
    expect(await screen.findByText('Nothing booked or on its way this week.')).toBeInTheDocument();
  });

  it('asks guests to sign in to see their week', async () => {
    mockFetch(api());
    renderApp('/', { routes: home });
    expect(await screen.findByText('Sign in to see your orders, bookings and quotes here.')).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(within(main).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in');
  });

  it('offers another location where Northline isn’t yet', async () => {
    mockFetch(api({ summary: { city: 'Toronto', providers: 0, shops: 0, kitchensOpen: 0, categories: {}, cuisines: {}, trusted: [] } }));
    localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Toronto', city: 'Toronto' }));
    renderApp('/', { routes: home });
    expect(await screen.findByText('Northline isn’t in Toronto yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Choose another location' })).toHaveAttribute('href', '/location');
    expect(screen.queryByRole('heading', { name: 'Shop by department' })).not.toBeInTheDocument();
  });

  it('shows an error with Retry when the numbers can’t load', async () => {
    let fail = true;
    mockFetch(c => (c.url.startsWith('/api/v1/public/home') && fail ? { status: 500, body: {} } : api()(c)));
    const user = userEvent.setup({ delay: null });
    renderApp('/', { routes: home });
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 });
    expect(alert).toHaveTextContent('We couldn’t load what’s near you.');
    fail = false;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: /Bakery/ })).toHaveTextContent('7 shops');
  });

  it('speaks French', async () => {
    mockFetch(api());
    renderApp('/', { routes: home, locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'De quoi avez-vous besoin à Beltline ?' })).toBeInTheDocument();
    expect(await screen.findByText(/^(Bonjour|Bon après-midi|Bonsoir) · Beltline, Calgary$/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Magasiner par rayon' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('link', { name: /Boulangerie/ })).toHaveTextContent('7 commerces'));
    expect(screen.getByRole('link', { name: /Vietnamien/ })).toHaveTextContent('4 ouvertes');
    expect(screen.getByRole('link', { name: /^Mécanicien mobile/ })).toHaveTextContent('14 à proximité · visite');
    expect(screen.getByRole('link', { name: /Repas/ })).toHaveTextContent('42 ouverts');
    expect(screen.getByRole('heading', { name: 'De confiance, près de chez vous' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Prairie Wrench/ })).toHaveTextContent('Maître');
    expect(screen.getByRole('heading', { name: 'Payé en fiducie' })).toBeInTheDocument();
  });

  it('asks without a place until the location is known', async () => {
    localStorage.clear();
    mockFetch(api());
    renderApp('/', { routes: home, geolocation: { getCurrentPosition: () => {} } as unknown as Geolocation });
    expect(await screen.findByRole('heading', { level: 1, name: 'What do you need today?' })).toBeInTheDocument();
  });
});
