import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';

const AMARA = { id: '01J9ZD3V00000000000000AMA1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO', locale: 'en-CA' };
const guest = (call: Call) => (call.url === '/bff/session' ? { body: { user: null, guestId: 'g_x' } } : undefined);
const signedIn = (extra?: (c: Call) => { status?: number; body?: unknown } | undefined) => (call: Call) =>
  call.url === '/bff/session' ? { body: { user: AMARA, guestId: 'g_x', sid: 's1' } } : extra?.(call);

beforeEach(() => { document.cookie = 'nl.locale=; max-age=0; path=/'; document.cookie = 'XSRF-TOKEN=tok-1; path=/'; localStorage.clear(); sessionStorage.clear(); });
afterEach(() => { vi.unstubAllGlobals(); });

describe('consumer header', () => {
  it('shows brand, location, Services / Shop / Food, language and cart — and no search on the home page', async () => {
    mockFetch(guest);
    renderApp('/');
    const header = await screen.findByRole('banner');
    expect(within(header).getByRole('link', { name: 'Northline — home' })).toHaveAttribute('href', '/');
    const nav = within(header).getByRole('navigation', { name: 'Main' });
    expect(within(nav).getAllByRole('link').map(a => a.textContent)).toEqual(['Services', 'Shop', 'Food']);
    expect(within(nav).getAllByRole('link').map(a => a.getAttribute('href'))).toEqual(['/services', '/shop', '/food']);
    expect(within(header).queryByRole('search')).toBeNull();
    expect(within(header).getByRole('button', { name: /Switch language/ })).toHaveTextContent('EN');
    expect(within(header).getByRole('link', { name: 'Cart, empty' })).toHaveAttribute('href', '/cart');
  });

  it('never puts Orders in the top navigation', async () => {
    mockFetch(signedIn());
    renderApp('/shop');
    const header = await screen.findByRole('banner');
    await screen.findByRole('button', { name: 'Account menu' });
    expect(within(within(header).getByRole('navigation', { name: 'Main' })).queryByText(/Orders/)).toBeNull();
    expect(within(header).queryByRole('link', { name: /Orders/ })).toBeNull();
  });

  it('has the search field off the home page and searches on Enter', async () => {
    mockFetch(guest);
    const { router } = renderApp('/shop');
    const box = await screen.findByRole('searchbox', { name: 'Search' });
    expect(box).toHaveAttribute('placeholder', 'Search “sourdough”, “mobile mechanic”, “DJ”…');
    await userEvent.type(box, 'sourdough{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toEqual({ q: 'sourdough' });
  });

  it('marks the current section', async () => {
    mockFetch(guest);
    renderApp('/services/mobile-mechanic/providers');
    expect(await screen.findByRole('link', { name: 'Services' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Shop' })).not.toHaveAttribute('aria-current');
  });

  it('counts the cart', async () => {
    mockFetch(c => guest(c) ?? (c.url === '/api/v1/cart' ? { body: { itemCount: 4, shops: [] } } : undefined));
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Cart, 4 items' })).toHaveTextContent('4');
  });

  it('switches to French and remembers it', async () => {
    mockFetch(guest);
    renderApp('/cart');
    await userEvent.click(await screen.findByRole('button', { name: /Switch language/ }));
    const nav = screen.getByRole('navigation', { name: 'Principale' });
    expect(within(nav).getAllByRole('link').map(a => a.textContent)).toEqual(['Services', 'Boutique', 'Restaurants']);
    expect(screen.getByRole('button', { name: /Changer de langue/ })).toHaveTextContent('FR');
    expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Se connecter' })).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent("Vous naviguez en tant qu'invité.");
    expect(screen.getByRole('searchbox')).toHaveAttribute('placeholder', 'Rechercher « pain au levain », « mécanicien mobile », « DJ »…');
    expect(document.cookie).toContain('nl.locale=fr');
    expect(document.documentElement.lang).toBe('fr-CA');
  });
});

describe('guests', () => {
  it('offer Sign in and Create account, coming back to the page', async () => {
    mockFetch(guest);
    renderApp('/providers/prairie-wrench');
    expect(await screen.findByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in?next=%2Fproviders%2Fprairie-wrench');
    expect(screen.getByRole('link', { name: 'Create account' })).toHaveAttribute('href', '/register?next=%2Fproviders%2Fprairie-wrench');
    expect(screen.queryByRole('note')).toBeNull();
  });

  it('see the guest banner on the cart, booking, checkout, orders, account and quote screens', async () => {
    mockFetch(guest);
    renderApp('/cart');
    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent("You're browsing as a guest. Sign in to pay, book, track orders and earn points — your cart is kept.");
    expect(within(note).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in?next=%2Fcart');
  });

  it('get no account buttons on the sign-in page itself', async () => {
    mockFetch(guest);
    renderApp('/sign-in');
    await screen.findByRole('heading', { name: 'Sign in' });
    expect(within(screen.getByRole('banner')).queryByRole('link', { name: 'Sign in' })).toBeNull();
  });

  it('are assumed while the session cannot be read (the header still works)', async () => {
    mockFetch(c => (c.url === '/bff/session' ? { status: 503, body: { detail: 'down' } } : undefined));
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('the account menu', () => {
  it('lists Orders & bookings first, with the design’s values when the api has them', async () => {
    mockFetch(signedIn(c => c.url === '/api/v1/me/account-summary' ? { body: {
      reliability: 4.9, points: { balance: 12480, valueCents: 12480 }, plus: false, activeOrders: 3, favourites: 4, openCases: 1,
      paymentMethod: { brand: 'Visa', last4: '4471' }, addresses: { count: 2, members: 2 }, signIn: 'passkey', quietHours: { from: '10 pm', to: '7 am' },
      dietary: ['Halal', 'step-free'], province: 'AB',
    } } : undefined));
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    const menu = screen.getByRole('menu', { name: 'Account menu' });
    await within(menu).findByText('amara@example.ca · reliability 4.9');
    const items = within(menu).getAllByRole('menuitem');
    expect(items.map(i => i.textContent)).toEqual([
      'Add photo', 'Orders & bookings3 active', 'Favourites4', 'Wallet & points12,480 pts',
      'ProfileAmara Osei', 'Addresses & household2 · 2 members', 'Payment methodsVisa ··4471', 'Security & sign-inPasskey',
      'NotificationsQuiet 10 pm–7 am', 'Language / Langue & regionEnglish · AB', 'Dietary & accessibilityHalal, step-free', 'Northline PlusTry free',
      'Help & cases1 open', 'Sell or offer a service', 'Sign out',
    ]);
    expect(within(menu).getByRole('menuitem', { name: /Orders & bookings/ })).toHaveAttribute('href', '/account/orders');
    expect(within(menu).getByText('12,480 pts', { selector: 'strong' })).toBeInTheDocument();
    expect(within(menu).getByText('Standard')).toBeInTheDocument();
  });

  it('works without the summary (values hidden)', async () => {
    mockFetch(signedIn());
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    const orders = await screen.findByRole('menuitem', { name: /Orders & bookings/ });
    expect(orders).toHaveTextContent(/^Orders & bookings$/);
  });

  it('opens Orders & bookings', async () => {
    mockFetch(signedIn());
    const { router } = renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    await userEvent.click(screen.getByRole('menuitem', { name: /Orders & bookings/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/account/orders'));
    expect(screen.queryByRole('menu')).toBeNull();
    expect(await screen.findByRole('heading', { name: 'Orders & bookings' })).toBeInTheDocument();
  });

  it('signs out of the BFF (with the CSRF header) and of northline-auth', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign, protocol: 'http:' });
    const calls = mockFetch(signedIn(c => (c.method === 'POST' ? { status: 204 } : undefined)));
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('/'));
    const logout = calls.find(c => c.url === '/bff/logout')!;
    expect(logout.method).toBe('POST');
    expect(logout.headers['x-xsrf-token']).toBe('tok-1');
    expect(calls.some(c => c.url === 'http://auth.test/api/auth/sign-out' && c.method === 'POST')).toBe(true);
  });
});

describe('screens and states', () => {
  it('render a pending screen for every route not built yet', async () => {
    mockFetch(guest);
    renderApp('/food/orders/01J9ZD3V00000000000000ORD1');
    expect(await screen.findByRole('heading', { name: 'Track order' })).toBeInTheDocument();
    expect(screen.getByText('This screen is being built (S-57).')).toBeInTheDocument();
  });

  it('has a skip link and the footer with the legal documents', async () => {
    mockFetch(guest);
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main');
    const footer = screen.getByRole('contentinfo');
    expect(within(footer).getByRole('link', { name: 'Privacy' })).toHaveAttribute('href', '/legal/privacy.html');
    expect(within(footer).getByRole('link', { name: 'Terms' })).toHaveAttribute('href', '/legal/terms.html');
    expect(within(footer).getByRole('link', { name: 'Terms' })).toHaveAttribute('hreflang', 'en-CA');
    expect(within(footer).getByRole('link', { name: 'Offer a service' })).toHaveAttribute('href', '/sell?type=provider');
    expect(within(footer).getByRole('link', { name: 'Sell on Northline' })).toHaveAttribute('href', '/sell?type=seller');
    expect(within(footer).getByRole('link', { name: 'Run a kitchen' })).toHaveAttribute('href', '/sell?type=kitchen');
    expect(within(footer).getByText('Northline Marketplace Inc.')).toBeInTheDocument();
  });

  it('has the footer in French', async () => {
    mockFetch(guest);
    renderApp('/', { locale: 'fr' });
    const footer = await screen.findByRole('contentinfo');
    for (const name of ['Confidentialité', 'Conditions', 'Vendre sur Northline', 'Offrir un service', 'Gérer une cuisine']) {
      expect(within(footer).getByRole('link', { name })).toBeInTheDocument();
    }
    expect(within(footer).getByRole('button', { name: 'English' })).toHaveAttribute('lang', 'en-CA');
  });
});
