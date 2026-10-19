import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { brand } from '@northline/ui';

const AMARA = { id: '01J9ZD3V00000000000000AMA1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO', locale: 'en-CA' };
const guest = (call: Call) => (call.url === '/bff/session' ? { body: { user: null, guestId: 'g_x' } } : undefined);
const signedIn = (extra?: (c: Call) => { status?: number; body?: unknown } | undefined) => (call: Call) =>
  call.url === '/bff/session' ? { body: { user: AMARA, guestId: 'g_x', sid: 's1' } } : extra?.(call);

beforeEach(() => { document.cookie = 'nl.locale=; max-age=0; path=/'; document.cookie = 'XSRF-TOKEN=tok-1; path=/'; localStorage.clear(); sessionStorage.clear(); });
afterEach(() => { vi.unstubAllGlobals(); });

describe('consumer header', () => {
  it('shows the logo, Deliver to, the search (home included), the avatar menu, the cart and the department bar — no language switch', async () => {
    mockFetch(guest);
    renderApp('/');
    const header = await screen.findByRole('banner');
    await expectNoAxeViolations(document.body); // S-109
    expect(within(header).getByRole('link', { name: `${brand.name} — home` })).toHaveAttribute('href', '/');
    const nav = within(header).getByRole('navigation', { name: 'Main' });
    // redesign § 3.7: Food as its own vertical is hidden for the demo (VITE_NL_FULL_NAV brings it back)
    expect(within(nav).getAllByRole('link').map(a => a.textContent)).toEqual(['Services', 'Shop']);
    expect(within(nav).getAllByRole('link').map(a => a.getAttribute('href'))).toEqual(['/services', '/shop']);
    // redesign § 3.3: search is always in the header; FR/EN moved to the footer and the account menu
    expect(within(header).getByRole('search')).toBeInTheDocument();
    expect(within(header).queryByRole('button', { name: /language|Français/ })).toBeNull();
    expect(within(header).getByRole('button', { name: 'Account menu' })).toBeInTheDocument();
    expect(within(header).getByRole('link', { name: 'Cart, empty' })).toHaveAttribute('href', '/cart');
  });

  it('brings Food back into the department bar with the full navigation', async () => {
    vi.stubEnv('VITE_NL_FULL_NAV', '1');
    mockFetch(guest);
    renderApp('/');
    const nav = within(await screen.findByRole('banner')).getByRole('navigation', { name: 'Main' });
    expect(within(nav).getAllByRole('link').map(a => a.getAttribute('href'))).toEqual(['/services', '/shop', '/food']);
    vi.unstubAllEnvs();
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
    const box = await screen.findByRole('combobox', { name: 'Search' });
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
    await userEvent.click(within(await screen.findByRole('contentinfo')).getByRole('button', { name: 'Français' }));
    const nav = screen.getByRole('navigation', { name: 'Principale' });
    expect(within(nav).getAllByRole('link').map(a => a.textContent)).toEqual(['Services', 'Boutique']);
    expect(within(screen.getByRole('contentinfo')).getByRole('button', { name: 'English' })).toHaveAttribute('lang', 'en-CA');
    await userEvent.click(within(screen.getByRole('banner')).getByRole('button', { name: 'Menu du compte' }));
    expect(screen.getByRole('menuitem', { name: 'Se connecter' })).toBeInTheDocument();
    expect(screen.getByRole('menu')).toHaveTextContent('Bienvenue');
    expect(screen.queryByRole('note')).toBeNull();
    expect(within(screen.getByRole('banner')).getByRole('combobox')).toHaveAttribute('placeholder', 'Rechercher « pain au levain », « mécanicien mobile », « DJ »…');
    expect(document.cookie).toContain('nl.locale=fr');
    expect(document.documentElement.lang).toBe('fr-CA');
  });
});

describe('guests', () => {
  it('get the avatar menu — "Welcome, guest", Sign in and Create account coming back to the page, the language', async () => {
    mockFetch(guest);
    renderApp('/providers/prairie-wrench');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    const menu = screen.getByRole('menu', { name: 'Account menu' });
    expect(menu).toHaveTextContent('Welcome, guest');
    expect(within(menu).getByRole('menuitem', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in?next=%2Fproviders%2Fprairie-wrench');
    expect(within(menu).getByRole('menuitem', { name: 'Create account' })).toHaveAttribute('href', '/register?next=%2Fproviders%2Fprairie-wrench');
    expect(within(menu).getByRole('menuitem', { name: 'Français' })).toBeInTheDocument();
  });

  it('see no guest banner (redesign § 3.4: they sign in at the checkout or booking step, or from the avatar menu)', async () => {
    mockFetch(guest);
    renderApp('/cart');
    await screen.findByRole('banner');
    expect(screen.queryByRole('note')).toBeNull();
    expect(screen.queryByText(/browsing as a guest/)).toBeNull();
  });

  it('get the logo alone on the sign-in page itself (no site navigation on auth pages)', async () => {
    mockFetch(guest);
    renderApp('/sign-in');
    await screen.findByRole('heading', { name: 'Sign in' });
    const banner = screen.getByRole('banner');
    expect(within(banner).queryByRole('button', { name: 'Account menu' })).toBeNull();
    expect(within(banner).queryByRole('navigation')).toBeNull();
    expect(within(banner).queryByRole('search')).toBeNull();
    expect(within(banner).getByRole('link', { name: `${brand.name} — home` })).toBeInTheDocument();
  });

  it('are assumed while the session cannot be read (the header still works)', async () => {
    mockFetch(c => (c.url === '/bff/session' ? { status: 503, body: { detail: 'down' } } : undefined));
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    expect(screen.getByRole('menuitem', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('the account menu', () => {
  it('lists Orders and Bookings first, with the values when the api has them — no Wallet, Dietary or Plus (§ 3.6)', async () => {
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
      'Add photo', 'Orders3 active', 'Bookings', 'Saved4',
      'Your account', 'ProfileAmara Osei', 'Addresses2 · 2 members', 'Payment methodsVisa ··4471', 'Login & securityPasskey',
      'NotificationsQuiet 10 pm–7 am', 'FrançaisEnglish · AB',
      'Help & cases1 open', 'Sell or offer a service', 'Sign out',
    ]);
    expect(within(menu).getByRole('menuitem', { name: /^Orders/ })).toHaveAttribute('href', '/account/orders');
    expect(within(menu).getByRole('menuitem', { name: 'Bookings' })).toHaveAttribute('href', '/account/orders?tab=bookings');
    expect(within(menu).getByRole('menuitem', { name: 'Your account' })).toHaveAttribute('href', '/account');
    expect(within(menu).queryByText('12,480 pts')).toBeNull(); // the points card waits for the wallet (later)
  });

  it('brings Wallet, Dietary and Plus back with the full navigation', async () => {
    vi.stubEnv('VITE_NL_FULL_NAV', '1');
    mockFetch(signedIn(c => c.url === '/api/v1/me/account-summary' ? { body: { points: { balance: 12480, valueCents: 12480 }, plus: false } } : undefined));
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    const menu = screen.getByRole('menu', { name: 'Account menu' });
    expect(await within(menu).findByText('12,480 pts', { selector: 'strong' })).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: /Wallet & points/ })).toHaveAttribute('href', '/account?tab=wallet');
    expect(within(menu).getByRole('menuitem', { name: /Dietary & accessibility/ })).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: new RegExp(`${brand.name} Plus`) })).toBeInTheDocument();
    vi.unstubAllEnvs();
  });

  it('works without the summary (values hidden)', async () => {
    mockFetch(signedIn());
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    const orders = await screen.findByRole('menuitem', { name: /^Orders/ });
    expect(orders).toHaveTextContent(/^Orders$/);
  });

  it('gives a screen reader the landmarks, names and states of the header (S-148)', async () => {
    mockFetch(signedIn());
    renderApp('/search?q=x');
    const banner = await screen.findByRole('banner');
    expect(within(banner).getByRole('link', { name: `${brand.name} — home` })).toBeInTheDocument();
    expect(within(banner).getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    expect(within(banner).getByRole('search')).toBeInTheDocument();
    expect(within(banner).getByRole('combobox', { name: 'Search' })).toBeInTheDocument();
    const account = await within(banner).findByRole('button', { name: 'Account menu' });
    expect(account).toHaveAttribute('aria-expanded', 'false');
    expect(account).toHaveAttribute('aria-haspopup', 'menu');
    await userEvent.click(account);
    expect(account).toHaveAttribute('aria-expanded', 'true');
    const menu = screen.getByRole('menu');
    expect(within(menu).getAllByRole('menuitem').length).toBeGreaterThan(3);
    expect(within(menu).getByRole('menuitem', { name: /Sign out/ })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).toBeNull();
    expect(account).toHaveFocus();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('opens Orders & bookings', async () => {
    mockFetch(signedIn());
    const { router } = renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Account menu' }));
    await userEvent.click(screen.getByRole('menuitem', { name: /^Orders/ }));
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
    expect(within(footer).getByRole('link', { name: 'Help' })).toHaveAttribute('href', '/help'); // S-145: for guests too
    expect(within(footer).getByRole('link', { name: 'Offer a service' })).toHaveAttribute('href', '/sell?type=provider');
    expect(within(footer).getByRole('link', { name: `Sell on ${brand.name}` })).toHaveAttribute('href', '/sell?type=seller');
    // Food is hidden for the demo, so is "Run a kitchen" (demo rehearsal 2026-10-19)
    expect(within(footer).queryByRole('link', { name: 'Run a kitchen' })).toBeNull();
    expect(within(footer).getByText(brand.legalName)).toBeInTheDocument();
  });

  it('offers "Run a kitchen" in the footer when the full navigation (Food) is on', async () => {
    vi.stubEnv('VITE_NL_FULL_NAV', '1');
    mockFetch(guest);
    renderApp('/');
    const footer = await screen.findByRole('contentinfo');
    expect(within(footer).getByRole('link', { name: 'Run a kitchen' })).toHaveAttribute('href', '/sell?type=kitchen');
    vi.unstubAllEnvs();
  });

  it('has the footer in French', async () => {
    mockFetch(guest);
    renderApp('/', { locale: 'fr' });
    const footer = await screen.findByRole('contentinfo');
    for (const name of ['Confidentialité', 'Conditions', 'Aide', `Vendre sur ${brand.name}`, 'Offrir un service']) {
      expect(within(footer).getByRole('link', { name })).toBeInTheDocument();
    }
    expect(within(footer).queryByRole('link', { name: 'Gérer une cuisine' })).toBeNull(); // Food is hidden for the demo
    expect(within(footer).getByRole('button', { name: 'English' })).toHaveAttribute('lang', 'en-CA');
  });
});
