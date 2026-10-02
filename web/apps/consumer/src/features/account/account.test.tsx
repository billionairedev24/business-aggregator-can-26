import { beforeEach, describe, expect, it } from 'vitest';
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

describe('Orders & bookings (design 06 orders)', () => {
  it('lists what is under way with the design’s columns, tags and actions', async () => {
    orders();
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders & bookings' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Active · 3' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Past' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Refunds & cases · 1' })).toBeInTheDocument();
    expect(await screen.findByText('Grocery run · 3 shops')).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Bridgeland Butcher, Sunnyside Greens, Glenmore Bakery')).toBeInTheDocument();
    expect(screen.getByText('Packing')).toBeInTheDocument();
    expect(screen.getByText('$47.76')).toBeInTheDocument();
    expect(screen.getByText('Brake inspection')).toBeInTheDocument();
    expect(screen.getByText('Prairie Wrench · Ravi')).toBeInTheDocument();
    expect(screen.getByText('Escrow')).toBeInTheDocument();
    expect(screen.getByText('Quote ready')).toBeInTheDocument();
    expect(screen.getByText('$640.00')).toBeInTheDocument();
    expect(screen.queryByText('Winter tire swap')).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Track' }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: 'View quote' }).length).toBeGreaterThan(0);
  });

  it('a row’s action opens it', async () => {
    const { router } = orders();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Track' }))[0]!);
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/O1'));
  });

  it('Past and Refunds & cases filter the list', async () => {
    const { router } = orders();
    await userEvent.click(await screen.findByRole('button', { name: 'Past' }));
    expect(await screen.findByText('Winter tire swap')).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Re-book' }).length).toBeGreaterThan(0);
    expect(screen.queryByText('Brake inspection')).not.toBeInTheDocument();
    expect(router.state.location.search).toEqual({ view: 'past' });
    await userEvent.click(screen.getByRole('button', { name: 'Refunds & cases · 1' }));
    expect(await screen.findByText('Case RF-2201')).toBeInTheDocument();
    expect(screen.getByText('Grocery run · 2 shops')).toBeInTheDocument();
    expect(screen.queryByText('Winter tire swap')).not.toBeInTheDocument();
  });

  it('empty: one line and a way to start', async () => {
    activity = () => ({ body: { items: [] } });
    orders();
    expect(await screen.findByText('Nothing on the go right now.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start shopping' })).toHaveAttribute('href', '/shop');
    expect(screen.getByRole('button', { name: 'Refunds & cases' })).toBeInTheDocument();
  });

  it('error: rosehip inline with Retry', async () => {
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
    orders('/account/orders', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Commandes et réservations' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'En cours · 3' })).toBeInTheDocument();
    expect(await screen.findByText('Tournée d’épicerie · 3 commerces')).toBeInTheDocument();
    expect(screen.getByText('Emballage')).toBeInTheDocument();
    expect(screen.getByText('Fiducie')).toBeInTheDocument();
    expect(screen.getByText('Devis prêt')).toBeInTheDocument();
  });
});

describe('Account area (design 06 account)', () => {
  it('Wallet & points by default: balance, value, eight weeks, Plus', async () => {
    account();
    expect(await screen.findByRole('heading', { level: 1, name: 'Wallet & points' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Your account' });
    expect(within(nav).getByRole('link', { name: 'Wallet & points' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Sell or offer a service' })).toHaveAttribute('href', '/sell');
    expect(screen.getByText('1,240 pts')).toBeInTheDocument();
    expect(screen.getByText('= $12.40 off anything · Standard')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /^Points earned, last 8 weeks: 120 pts, 7 weeks ago,.*300 pts, this week$/ })).toBeInTheDocument();
    expect(screen.getByText('Free pooled delivery, 2× points, priority windows · $7.99/mo per household')).toBeInTheDocument();
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
    account();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('1,240 pts')).toBeInTheDocument();
  });

  it('no points yet', async () => {
    wallet = () => ({ body: { points: { balance: 0, valueCents: 0, weekly: [0, 0, 0, 0, 0, 0, 0, 0] }, plus: null } });
    account();
    expect(await screen.findByText(/No points yet/)).toBeInTheDocument();
  });

  it('Favourites: what the person did with each, where to go, Remove', async () => {
    account('/account?tab=favourites');
    expect(await screen.findByRole('heading', { level: 1, name: 'Favourite providers & shops' })).toBeInTheDocument();
    expect(await screen.findByText('Mobile mechanic · 3 jobs · last Oct 12')).toBeInTheDocument();
    const links = within(screen.getByRole('list', { name: 'Favourite providers & shops' })).getAllByRole('link');
    expect(links.map(l => [l.textContent, l.getAttribute('href')])).toEqual([
      ['Book', '/providers/prairie-wrench'],
      ['Shop', '/search?q=Glenmore+Bakery&scope=shop'],
      ['View quote', '/quotes/QQ1'],
    ]);
    await userEvent.click(screen.getByRole('button', { name: 'Remove Prairie Wrench from favourites' }));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench')).not.toBeInTheDocument());
    expect(calls.some(c => c.method === 'DELETE' && c.url === '/api/v1/me/favourites/M1')).toBe(true);
  });

  it('no favourites yet', async () => {
    favourites = [];
    account('/account?tab=favourites');
    expect(await screen.findByText('No favourites yet — tap ♡ on any provider or shop.')).toBeInTheDocument();
  });

  it('asks a guest to sign in', async () => {
    user = null;
    account('/account?tab=favourites');
    await screen.findByText('Sign in to see your account.');
    expect(screen.getAllByRole('link', { name: 'Sign in' }).map(l => l.getAttribute('href'))).toContain('/sign-in?next=%2Faccount%3Ftab%3Dfavourites');
  });

  it('in French', async () => {
    account('/account', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Portefeuille et points' })).toBeInTheDocument();
    expect(await screen.findByText(/^= 12,40\s\$ de rabais sur tout · Standard$/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Essai gratuit' })).toBeInTheDocument();
  });
});
