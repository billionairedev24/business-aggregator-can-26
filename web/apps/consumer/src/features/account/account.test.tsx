import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AccountScreen } from './AccountScreen';
import { OrdersScreen } from './OrdersScreen';
import type { ActivityItem, Favourite, Wallet } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const soon = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString();

const ITEMS: ActivityItem[] = [
  { id: 'O1', kind: 'order', ref: 'NL-48213', title: '', with: ['Bridgeland Butcher', 'Sunnyside Greens', 'Glenmore Bakery'], delivery: 'pooled', shops: 3, items: 4,
    when: soon(30), whenEnd: soon(33), amountCents: 4776, status: 'packing', tone: 'accent', active: true, caseRef: null, action: 'track', href: '/orders/O1' },
  { id: 'B1', kind: 'booking', ref: 'BK-7712', title: 'Brake inspection', with: ['Prairie Wrench · Ravi'], delivery: null, shops: 1, items: 1,
    when: soon(72), whenEnd: null, amountCents: 9345, status: 'escrow', tone: 'neutral', active: true, caseRef: null, action: 'details', href: '/providers/prairie-wrench/book?step=done&booking=B1' },
  { id: 'Q1', kind: 'quote', ref: 'QT-3310', title: 'Mocktail bar · 40 guests', with: ['Sable & Soda'], delivery: null, shops: 1, items: 1,
    when: soon(120), whenEnd: null, amountCents: 64000, status: 'quote_ready', tone: 'accent-2', active: true, caseRef: null, action: 'view_quote', href: '/quotes/QQ1' },
  { id: 'B0', kind: 'booking', ref: 'BK-7401', title: 'Winter tire swap', with: ['Prairie Wrench · Ravi'], delivery: null, shops: 1, items: 1,
    when: '2026-09-12T16:00:00Z', whenEnd: null, amountCents: 12000, status: 'done', tone: 'neutral', active: false, caseRef: null, action: 'rebook', href: '/providers/prairie-wrench' },
  { id: 'O0', kind: 'order', ref: 'NL-48150', title: '', with: ['Sunnyside Greens', 'Glenmore Bakery'], delivery: 'pooled', shops: 2, items: 3,
    when: '2026-09-26T23:00:00Z', whenEnd: null, amountCents: 5995, status: 'case', tone: 'accent-2', active: false,
    caseRef: { id: 'RF1', number: 'RF-2201', kind: 'refund', open: true }, action: 'view_case', href: '/account?tab=help&case=RF1' },
];
const WALLET: Wallet = { points: { balance: 1240, valueCents: 1240, weekly: [120, 340, 90, 410, 260, 180, 520, 300] }, plus: null };
const FAVS: Favourite[] = [
  { merchantId: 'M1', name: 'Prairie Wrench', type: 'provider', tier: 'master', slug: 'prairie-wrench', categoryId: 'service.automotive.mobile-mechanic',
    visits: 3, lastAt: '2026-10-12T16:00:00Z', openQuoteId: null, addedAt: '2026-09-01T00:00:00Z' },
  { merchantId: 'M2', name: 'Glenmore Bakery', type: 'seller', tier: 'master', slug: null, categoryId: 'shop.groceries.bakery',
    visits: 0, lastAt: null, openQuoteId: null, addedAt: '2026-09-01T00:00:00Z' },
  { merchantId: 'M3', name: 'Sable & Soda', type: 'provider', tier: 'trusted', slug: 'sable-soda', categoryId: null,
    visits: 0, lastAt: null, openQuoteId: 'QQ1', addedAt: '2026-09-01T00:00:00Z' },
];

type Reply = { status?: number; body?: unknown } | undefined;
let user: typeof AMARA | null;
let activity: () => Reply;
let wallet: () => Reply;
let favourites: FavouriteRow[];
type FavouriteRow = Favourite;
let calls: Call[];
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g' } };
  if (c.url === '/api/v1/me/activity') return activity();
  if (c.url === '/api/v1/me/wallet') return wallet();
  if (c.url === '/api/v1/me/favourites') return { body: { items: favourites } };
  const fav = c.url.match(/^\/api\/v1\/me\/favourites\/(.+)$/);
  if (fav && c.method === 'DELETE') { favourites = favourites.filter(f => f.merchantId !== fav[1]); return { status: 200 }; }
  return undefined;
};
const orders = (path = '/account/orders', locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp(path, { locale, routes: { orders: () => <OrdersScreen /> } });
};
const account = (path = '/account', locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp(path, { locale, routes: { account: () => <AccountScreen /> } });
};

beforeEach(() => {
  user = AMARA;
  activity = () => ({ body: { items: ITEMS } });
  wallet = () => ({ body: WALLET });
  favourites = [...FAVS];
});

describe('Orders & bookings (redesign § 2.1, AT Shop:688)', () => {
  it('opens on Orders: tabs, the view chips, order cards and the help / order-again column', async () => {
    orders();
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders & bookings' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Orders\s*2$/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /^Bookings\s*3$/ })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByRole('button', { name: 'Active · 1' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Past' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Refunds & cases · 1' })).toBeInTheDocument();
    const card = (await screen.findByRole('heading', { level: 2, name: 'Grocery run · 3 shops' })).closest('li')!;
    await expectNoAxeViolations(document.body); // S-109
    expect(card).toHaveTextContent('Bridgeland Butcher, Sunnyside Greens, Glenmore Bakery');
    expect(card).toHaveTextContent('Packing');
    expect(card).toHaveTextContent('$47.76');
    expect(card).toHaveTextContent('Order #NL-48213');
    expect(within(card).getByRole('button', { name: 'Track' })).toBeEnabled();
    expect(screen.queryByText('Brake inspection')).not.toBeInTheDocument();
    const side = screen.getByRole('complementary', { name: 'Help and ordering again' });
    expect(within(side).getByRole('link', { name: 'Refunds & cases' })).toHaveAttribute('href', '/account?tab=help');
    expect(within(side).getByRole('link', { name: 'Grocery run · 2 shops' })).toHaveAttribute('href', '/account?tab=help&case=RF1');
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Your account › Orders & bookings');
  });

  it('switches to Bookings: jobs and quotes', async () => {
    const { router } = orders();
    await userEvent.click(await screen.findByRole('tab', { name: /^Bookings\s*3$/ }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'bookings' }));
    expect(await screen.findByText('Brake inspection')).toBeInTheDocument();
    expect(screen.getByText('Prairie Wrench · Ravi')).toBeInTheDocument();
    expect(screen.getByText('Escrow')).toBeInTheDocument();
    expect(screen.getByText('Quote ready')).toBeInTheDocument();
    expect(screen.getByText('$640.00')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View quote' })).toBeInTheDocument();
    expect(screen.queryByText('Grocery run · 3 shops')).not.toBeInTheDocument();
  });

  it('a card’s action opens it', async () => {
    const { router } = orders();
    await userEvent.click(await screen.findByRole('button', { name: 'Track' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/O1'));
  });

  it('Past and Refunds & cases filter the list', async () => {
    const { router } = orders('/account/orders?tab=bookings');
    await userEvent.click(await screen.findByRole('button', { name: 'Past' }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Winter tire swap' })).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Re-book' })).toBeInTheDocument();
    expect(screen.queryByText('Brake inspection')).not.toBeInTheDocument();
    expect(router.state.location.search).toEqual({ tab: 'bookings', view: 'past' });
    await userEvent.click(screen.getByRole('tab', { name: /^Orders\s*2$/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Refunds & cases · 1' }));
    expect(await screen.findByText('Case RF-2201')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Grocery run · 2 shops' })).toBeInTheDocument();
  });

  it('empty: one line and a way to start', async () => {
    activity = () => ({ body: { items: [] } });
    orders();
    expect(await screen.findByText('Nothing on the go right now.')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Start shopping' })[0]).toHaveAttribute('href', '/shop');
    expect(screen.getByRole('button', { name: 'Refunds & cases' })).toBeInTheDocument();
    expect(screen.getByText('What you order or book shows up here, ready to do again.')).toBeInTheDocument();
  });

  it('error: inline with Retry', async () => {
    let fail = true;
    activity = () => (fail ? { status: 500, body: {} } : { body: { items: ITEMS } });
    orders();
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t load this.');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Grocery run · 3 shops')).toBeInTheDocument();
  });

  it('asks a guest to sign in', async () => {
    user = null;
    orders();
    expect(await screen.findByText('Sign in to see your orders and bookings.')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Sign in' }).map(l => l.getAttribute('href'))).toContain('/sign-in?next=%2Faccount%2Forders');
    expect(calls.some(c => c.url === '/api/v1/me/activity')).toBe(false);
  });

  it('shows a skeleton while loading', async () => {
    activity = () => new Promise<never>(() => {}) as never;
    orders();
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
  });

  it('in French', async () => {
    orders('/account/orders?tab=bookings', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Commandes et réservations' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Réservations\s*3$/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: 'En cours · 2' })).toBeInTheDocument();
    expect(await screen.findByText('Fiducie')).toBeInTheDocument();
    expect(screen.getByText('Devis prêt')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: /^Commandes\s*2$/ }));
    expect(await screen.findByText('Tournée d’épicerie · 3 commerces')).toBeInTheDocument();
    expect(screen.getByText('Emballage')).toBeInTheDocument();
  });
});

describe('Your account (redesign § 2.1, AT Shop:596)', () => {
  it('opens on the card grid, not Wallet & points (§ 3.6; S-152 adds points, Plus and invites)', async () => {
    account();
    expect(await screen.findByRole('heading', { level: 1, name: 'Your account' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    const cards = within(screen.getByRole('list', { name: 'Account sections' })).getAllByRole('link');
    expect(cards.map(c => c.getAttribute('href'))).toEqual([
      '/account/orders', '/account/orders?tab=bookings', '/account?tab=addresses', '/account?tab=payments', '/account?tab=security',
      '/account?tab=notifications', '/account?tab=favourites', '/account?tab=wallet', '/account?tab=plus', '/account?tab=invite',
      '/account?tab=profile', '/account?tab=help',
    ]);
    // audit #9: "Sell or offer services" is a line under the grid, not a 13th card
    expect(screen.getByRole('link', { name: 'Sell or offer services' })).toHaveAttribute('href', '/sell');
    expect(cards[0]).toHaveTextContent('OrdersTrack, return or buy things again');
    expect(cards[6]).toHaveTextContent('Saved');
    expect(screen.queryByRole('navigation', { name: 'Your account' })).toBeNull(); // the eleven-tab rail is gone
    expect(calls.some(c => c.url === '/api/v1/me/wallet')).toBe(false);
  });

  it('opens the card grid for a section hidden for the demo', async () => {
    account('/account?tab=dietary');
    expect(await screen.findByRole('heading', { level: 1, name: 'Your account' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1, name: 'Dietary & accessibility' })).toBeNull();
  });

  it('opens a section under the Your account crumb', async () => {
    account('/account?tab=favourites');
    expect(await screen.findByRole('heading', { level: 1, name: 'Saved providers & shops' })).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Your account' })).toHaveAttribute('href', '/account');
    expect(crumbs).toHaveTextContent('Your account › Saved');
  });

  it('in French', async () => {
    account('/account', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Votre compte' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Connexion et sécurité/ })).toHaveAttribute('href', '/account?tab=security');
  });
});

describe('Account sections behind the full navigation (VITE_NL_FULL_NAV)', () => {
  beforeEach(() => { vi.stubEnv('VITE_NL_FULL_NAV', '1'); });
  afterEach(() => { vi.unstubAllEnvs(); });

  it('Wallet & points: balance, value, eight weeks, Plus', async () => {
    account('/account?tab=wallet');
    expect(await screen.findByRole('heading', { level: 1, name: 'Wallet & points' })).toBeInTheDocument();
    expect(screen.getByText('1,240 pts')).toBeInTheDocument();
    expect(screen.getByText('= $12.40 off anything · Standard')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /^Points earned, last 8 weeks: 120 pts, 7 weeks ago,.*300 pts, this week$/ })).toBeInTheDocument();
    expect(screen.getByText('$0 service fee on food, member prices, 2× points · one membership per household')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Try free' })).toHaveAttribute('href', '/account?tab=plus');
  });

  it('Plus members see their tag and Manage', async () => {
    wallet = () => ({ body: { ...WALLET, plus: { plan: 'monthly', since: '2026-05-03T00:00:00Z', renewsAt: '2026-11-01T00:00:00Z', members: 2 } } });
    account('/account?tab=wallet');
    expect(await screen.findByText('= $12.40 off anything · Plus · 2× points')).toBeInTheDocument();
    expect(screen.getByText('Northline Plus · active')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Manage' })).toBeInTheDocument();
  });

  it('wallet error + Retry', async () => {
    let fail = true;
    wallet = () => (fail ? { status: 503, body: {} } : { body: WALLET });
    account('/account?tab=wallet');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('1,240 pts')).toBeInTheDocument();
  });

  it('no points yet', async () => {
    wallet = () => ({ body: { points: { balance: 0, valueCents: 0, weekly: [0, 0, 0, 0, 0, 0, 0, 0] }, plus: null } });
    account('/account?tab=wallet');
    expect(await screen.findByText(/No points yet/)).toBeInTheDocument();
  });

  it('Favourites: what the person did with each, where to go, Remove', async () => {
    account('/account?tab=favourites');
    expect(await screen.findByRole('heading', { level: 1, name: 'Saved providers & shops' })).toBeInTheDocument();
    expect(await screen.findByText('Mobile mechanic · 3 jobs · last Oct 12')).toBeInTheDocument();
    const links = within(screen.getByRole('list', { name: 'Saved providers & shops' })).getAllByRole('link');
    expect(links.map(l => [l.textContent, l.getAttribute('href')])).toEqual([
      ['Book', '/providers/prairie-wrench'],
      ['Shop', '/search?q=Glenmore+Bakery&scope=shop'],
      ['View quote', '/quotes/QQ1'],
    ]);
    await userEvent.click(screen.getByRole('button', { name: 'Remove Prairie Wrench from saved' }));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench')).not.toBeInTheDocument());
    expect(calls.some(c => c.method === 'DELETE' && c.url === '/api/v1/me/favourites/M1')).toBe(true);
  });

  it('no favourites yet', async () => {
    favourites = [];
    account('/account?tab=favourites');
    expect(await screen.findByText('Nothing saved yet — tap ♡ on any provider or shop.')).toBeInTheDocument();
  });

  it('asks a guest to sign in', async () => {
    user = null;
    account('/account?tab=favourites');
    await screen.findByText('Sign in to see your account.');
    expect(screen.getAllByRole('link', { name: 'Sign in' }).map(l => l.getAttribute('href'))).toContain('/sign-in?next=%2Faccount%3Ftab%3Dfavourites');
  });

  it('Wallet & points in French', async () => {
    account('/account?tab=wallet', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Portefeuille et points' })).toBeInTheDocument();
    expect(await screen.findByText(/^= 12,40\s\$ de rabais sur tout · Standard$/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Essai gratuit' })).toBeInTheDocument();
  });
});
