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
  it('shows the logo, Deliver to, the search with its scope, Account & orders, the cart and the department strip — no language switch', async () => {
    mockFetch(guest);
    renderApp('/');
    const header = await screen.findByRole('banner');
    await expectNoAxeViolations(document.body); // S-109
    expect(within(header).getByRole('link', { name: `${brand.name} — home` })).toHaveAttribute('href', '/');
    const nav = within(header).getByRole('navigation', { name: 'Main' });
    // E-15 (design/revamp/a.html): All (the categories sheet) · Services Shop Food · the main departments | the busiest
    // service categories · Today's deals and Sell on … at the right end
    expect(within(nav).getAllByRole('button').map(b => b.textContent)).toEqual(['All']);
    expect(within(nav).getAllByRole('link').map(a => a.textContent)).toEqual([
      'Services', 'Shop', 'Food', 'Bakery', 'Butcher', 'Groceries', 'Pharmacy', 'Clothing',
      'Mobile mechanic', 'Home cleaning', 'Barber & hair', 'Tutor', 'Plumber', 'Today’s deals', `Sell on ${brand.name}`,
    ]);
    expect(within(nav).getAllByRole('link').slice(0, 4).map(a => a.getAttribute('href'))).toEqual(['/services', '/shop', '/food', '/shop/bakery']);
    expect(within(nav).getByRole('link', { name: 'Today’s deals' })).toHaveAttribute('href', '/search?scope=shop&delivery=tonight');
    // the search is always in the header, with its department scope; FR/EN is in the footer and the account menu
    expect(within(header).getByRole('search')).toBeInTheDocument();
    expect(within(header).getByRole('combobox', { name: 'Search in' })).toHaveValue('all');
    expect(within(within(header).getByRole('combobox', { name: 'Search in' })).getAllByRole('option').map(o => o.textContent)).toEqual(['All', 'Services', 'Shop', 'Food']);
    expect(within(header).queryByRole('button', { name: /language|Français/ })).toBeNull();
    expect(within(header).getByRole('button', { name: 'Hello, sign in Account & orders' })).toBeInTheDocument();
    expect(within(header).getByRole('button', { name: 'Menu' })).toHaveAttribute('aria-haspopup', 'dialog');
    expect(within(header).getByRole('link', { name: 'Cart, empty' })).toHaveAttribute('href', '/cart');
  });

  it('greets the signed-in person by name, over the place they deliver to', async () => {
    mockFetch(signedIn());
    renderApp('/');
    const header = await screen.findByRole('banner');
    expect(await within(header).findByRole('button', { name: 'Hello, Amara Account & orders' })).toHaveAttribute('aria-haspopup', 'menu');
    expect(within(header).getByRole('button', { name: /^Deliver to Amara/ })).toHaveAttribute('aria-haspopup', 'dialog');
  });

  it('opens every category by section from "All", and closes on a pick', async () => {
    mockFetch(guest);
    const { router } = renderApp('/');
    const all = within(await screen.findByRole('banner')).getByRole('button', { name: 'All categories' });
    expect(all).toHaveAttribute('aria-haspopup', 'dialog');
    await userEvent.click(all);
    const sheet = screen.getByRole('dialog', { name: 'All categories' });
    expect(within(sheet).getAllByRole('navigation').map(n => n.getAttribute('aria-label'))).toEqual(['Services', 'Shop', 'Food']);
    expect(within(sheet).getByRole('link', { name: 'See all — Shop' })).toHaveAttribute('href', '/shop');
    expect(within(sheet).getByRole('link', { name: 'Snow removal' })).toHaveAttribute('href', '/services/snow-removal');
    expect(within(sheet).getByRole('button', { name: 'Français' })).toBeInTheDocument();
    await userEvent.click(within(sheet).getByRole('link', { name: 'Hardware' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/shop/hardware'));
    expect(screen.queryByRole('dialog', { name: 'All categories' })).toBeNull();
  });

  it('never puts Orders in the top navigation', async () => {
    mockFetch(signedIn());
    renderApp('/shop');
    const header = await screen.findByRole('banner');
    await screen.findByRole('button', { name: /Account & orders/ });
    expect(within(within(header).getByRole('navigation', { name: 'Main' })).queryByText(/Orders/)).toBeNull();
    expect(within(header).queryByRole('link', { name: /Orders/ })).toBeNull();
  });

  it('searches on Enter in the current section\'s scope, or the one picked', async () => {
    mockFetch(guest);
    const { router } = renderApp('/shop');
    const box = await screen.findByRole('combobox', { name: 'Search' });
    expect(box).toHaveAttribute('placeholder', `Search ${brand.name}`);
    expect(screen.getByRole('combobox', { name: 'Search in' })).toHaveValue('shop');
    await userEvent.type(box, 'sourdough{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toEqual({ q: 'sourdough', scope: 'shop' });
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Search in' }), 'all');
    await userEvent.clear(box);
    await userEvent.type(box, 'mechanic{Enter}');
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'mechanic' }));
  });

  it('marks the current section, or the current category when it has a shortcut (one current item)', async () => {
    mockFetch(guest);
    const { unmount } = renderApp('/services');
    expect(await screen.findByRole('link', { name: 'Services' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Shop' })).not.toHaveAttribute('aria-current');
    unmount();
    renderApp('/services/mobile-mechanic/providers');
    expect(await screen.findByRole('link', { name: 'Mobile mechanic' })).toHaveAttribute('aria-current', 'page');
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(nav.querySelectorAll('[aria-current]')).toHaveLength(1);
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
    expect(within(nav).getAllByRole('link').slice(0, 4).map(a => a.textContent)).toEqual(['Services', 'Boutique', 'Restaurants', 'Boulangerie']);
    expect(within(nav).getByRole('button', { name: 'Toutes les catégories' })).toHaveTextContent('Tout');
    expect(within(screen.getByRole('contentinfo')).getByRole('button', { name: 'English' })).toHaveAttribute('lang', 'en-CA');
    await userEvent.click(within(screen.getByRole('banner')).getByRole('button', { name: 'Bonjour, connectez-vous Compte et commandes' }));
    expect(screen.getByRole('menuitem', { name: 'Se connecter' })).toBeInTheDocument();
    expect(screen.getByRole('menu')).toHaveTextContent('Bienvenue');
    expect(screen.queryByRole('note')).toBeNull();
    expect(within(screen.getByRole('banner')).getByRole('combobox', { name: 'Rechercher' })).toHaveAttribute('placeholder', `Rechercher sur ${brand.name}`);
    expect(document.cookie).toContain('nl.locale=fr');
    expect(document.documentElement.lang).toBe('fr-CA');
  });
});

describe('guests', () => {
  it('get the account menu — "Welcome, guest", Sign in and Create account coming back to the page, the language', async () => {
    mockFetch(guest);
    renderApp('/providers/prairie-wrench');
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
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
    expect(within(banner).queryByRole('button', { name: /Account & orders/ })).toBeNull();
    expect(within(banner).queryByRole('navigation')).toBeNull();
    expect(within(banner).queryByRole('search')).toBeNull();
    expect(within(banner).getByRole('link', { name: `${brand.name} — home` })).toBeInTheDocument();
  });

  it('are assumed while the session cannot be read (the header still works)', async () => {
    mockFetch(c => (c.url === '/bff/session' ? { status: 503, body: { detail: 'down' } } : undefined));
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
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
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
    const menu = screen.getByRole('menu', { name: 'Account menu' });
    await within(menu).findByText('amara@example.ca · reliability 4.9');
    const items = within(menu).getAllByRole('menuitem');
    expect(items.map(i => i.textContent)).toEqual([
      'Add photo', 'Orders3 active', 'Bookings', 'Quote requests', 'Messages', 'Saved4',
      'Your account', 'ProfileAmara Osei', 'Addresses2 · 2 members', 'Payment methodsVisa ··4471', 'Login & securityPasskey',
      'NotificationsQuiet 10 pm–7 am', 'FrançaisEnglish · AB',
      'Help & cases1 open', 'Sell or offer a service', 'Sign out',
    ]);
    expect(within(menu).getByRole('menuitem', { name: /^Orders/ })).toHaveAttribute('href', '/account/orders');
    expect(within(menu).getByRole('menuitem', { name: 'Bookings' })).toHaveAttribute('href', '/account/orders?tab=bookings');
    expect(within(menu).getByRole('menuitem', { name: 'Quote requests' })).toHaveAttribute('href', '/account/orders?tab=quotes');
    expect(within(menu).getByRole('menuitem', { name: 'Messages' })).toHaveAttribute('href', '/account/messages');
    expect(within(menu).getByRole('menuitem', { name: 'Your account' })).toHaveAttribute('href', '/account');
    expect(within(menu).queryByText('12,480 pts')).toBeNull(); // the points card waits for the wallet (later)
  });

  it('brings Wallet, Dietary and Plus back with the full navigation', async () => {
    vi.stubEnv('VITE_NL_FULL_NAV', '1');
    mockFetch(signedIn(c => c.url === '/api/v1/me/account-summary' ? { body: { points: { balance: 12480, valueCents: 12480 }, plus: false } } : undefined));
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
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
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
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
    const account = await within(banner).findByRole('button', { name: /Account & orders/ });
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
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
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
    await userEvent.click(await screen.findByRole('button', { name: /Account & orders/ }));
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
    expect(within(footer).getByRole('link', { name: 'Returns & refunds' })).toHaveAttribute('href', '/account/orders');
    expect(within(footer).getByRole('link', { name: 'Photo credits' })).toHaveAttribute('href', '/credits');
    expect(within(footer).getByText(`© ${brand.legalName}`)).toBeInTheDocument();
  });

  it('has the footer in French', async () => {
    mockFetch(guest);
    renderApp('/', { locale: 'fr' });
    const footer = await screen.findByRole('contentinfo');
    for (const name of ['Confidentialité', 'Conditions', 'Aide', `Vendre sur ${brand.name}`, 'Offrir un service']) {
      expect(within(footer).getByRole('link', { name })).toBeInTheDocument();
    }
    expect(within(footer).getByRole('button', { name: 'English' })).toHaveAttribute('lang', 'en-CA');
  });
});
