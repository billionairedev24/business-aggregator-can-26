import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { CartPage } from './CartPage';
import { addressErrors, localizeServer } from './address';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550201', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const line = (itemId: string, merchantId: string, name: string, unitCents: number, qty: number, extra = {}) => ({
  itemId, merchantId, offerId: `o-${itemId}`, variantId: null, productId: `p-${itemId}`, name, option: null, unit: null, imageUrl: null,
  unitCents, qty, lineCents: unitCents * qty, stock: 10, available: true, handlingDays: 0, ...extra,
});
const CART = {
  itemCount: 4, shopCount: 2, subtotalCents: 3990,
  groups: [
    { merchantId: 'M1', shopName: 'Bridgeland Butcher', items: [line('I1', 'M1', 'Ribeye, AAA', 1850, 1, { unit: '340 g' })] },
    { merchantId: 'M2', shopName: 'Glenmore Bakery', items: [line('I2', 'M2', 'Country sourdough', 750, 2, { option: 'Sliced', unit: '900 g' }), line('I3', 'M2', 'Baguette', 640, 1, { stock: 2 })] },
  ],
};
// tonight's run: 6–9 pm (UTC−6), order by 5:20 pm
const RUN = { windowId: 'W1', label: 'R-611', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', orderBy: '2026-09-30T23:20:00Z', feeCents: 299, households: 5 };
const LANDING = { market: 'Calgary', served: true, run: RUN, shopCount: 3, departments: [], shops: [], popular: [] };

type Reply = { status?: number; body?: unknown } | undefined;
let user: typeof AMARA | null;
let routes: Record<string, (c: Call) => Reply>;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g_x' } };
  const path = c.url.split('?')[0]!;
  return routes[`${c.method} ${path}`]?.(c) ?? routes[path]?.(c);
};
const open = (locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp('/cart', { locale, routes: { cart: () => <CartPage />, checkout: () => <h1>Checkout page</h1> } });
  return { calls, ...view };
};

beforeEach(() => {
  user = AMARA;
  localStorage.clear();
  sessionStorage.clear();
  routes = { 'GET /api/v1/cart': () => ({ body: CART }) };
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('Cart (redesign § 1.3, AT Shop:425)', () => {
  it('keeps the page h1 while the cart loads (S-142)', async () => {
    mockFetch(server);
    const answer = globalThis.fetch;
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => (String(input).startsWith('/api/v1/cart') ? new Promise(() => {}) : answer(input, init)));
    renderApp('/cart', { routes: { cart: () => <CartPage /> } });
    expect(await screen.findByText('Loading your cart…')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('lists the lines by shop with the subtotal and Proceed to checkout — no payment in the cart', async () => {
    open();
    expect(await screen.findByText('4 items · 2 shops · one delivery')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Shopping cart' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    const butcher = screen.getByRole('region', { name: 'Bridgeland Butcher' });
    expect(within(butcher).getByRole('link', { name: 'Ribeye, AAA' })).toHaveAttribute('href', '/products/p-I1');
    expect(within(butcher).getByText('In stock')).toBeInTheDocument();
    expect(screen.getByText('Sliced · 900 g')).toBeInTheDocument();
    expect(screen.getByText('Only 2 left')).toBeInTheDocument();
    expect(screen.getAllByText('Subtotal (4 items):')).toHaveLength(2);
    const summary = screen.getByRole('complementary', { name: 'Order summary' });
    expect(within(summary).getByText('Delivery and tax are added at checkout.')).toBeInTheDocument();
    expect(within(summary).getByRole('link', { name: 'Proceed to checkout' })).toHaveAttribute('href', '/checkout');
    // § 3.8: the payment moved to the checkout
    expect(screen.queryByRole('radiogroup', { name: 'Delivery window' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Pay/ })).toBeNull();
  });

  it('changes quantities with the stepper and deletes lines', async () => {
    const { calls } = open();
    routes['PATCH /api/v1/cart/items/I2'] = () => ({ body: { ...CART, itemCount: 5 } });
    routes['DELETE /api/v1/cart/items/I3'] = () => ({ body: { ...CART, itemCount: 3 } });
    routes['DELETE /api/v1/cart/items/I1'] = () => ({ body: { ...CART, itemCount: 3 } });
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'One more Country sourdough' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PATCH')?.body).toEqual({ qty: 3 }));
    await u.click(screen.getByRole('button', { name: 'Delete Baguette' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url === '/api/v1/cart/items/I3')).toBe(true));
    await u.click(screen.getByRole('button', { name: 'Remove Ribeye, AAA' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url === '/api/v1/cart/items/I1')).toBe(true));
  });

  it('S-173: saves a line for later (kept in this browser) and moves it back to the cart', async () => {
    const { calls } = open();
    routes['DELETE /api/v1/cart/items/I3'] = () => ({ body: { ...CART, itemCount: 3 } });
    routes['POST /api/v1/cart/items'] = () => ({ status: 201, body: CART });
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Save Baguette for later' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url === '/api/v1/cart/items/I3')).toBe(true));
    const saved = await screen.findByRole('region', { name: 'Saved for later (1)' });
    expect(within(saved).getByRole('link', { name: 'Baguette' })).toHaveAttribute('href', '/products/p-I3');
    expect(within(saved).getByText('Glenmore Bakery')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('nl.savedForLater')!)).toEqual([expect.objectContaining({ offerId: 'o-I3', qty: 1, unitCents: 640 })]);
    await u.click(within(saved).getByRole('button', { name: 'Move Baguette to cart' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url === '/api/v1/cart/items')?.body).toEqual({ offerId: 'o-I3', qty: 1 }));
    await waitFor(() => expect(screen.queryByRole('region', { name: /Saved for later/ })).toBeNull());
    expect(localStorage.getItem('nl.savedForLater')).toBeNull();
  });

  it('S-173: names the pooled run every shop rides, with its fee and order-by time', async () => {
    routes['GET /api/v1/public/shop'] = () => ({ body: LANDING });
    open();
    expect(await screen.findByText('Pooled run: Tonight, 6–9 p.m.')).toBeInTheDocument();
    expect(screen.getByText('All 2 shops deliver together for one $2.99 fee. Order by 5:20 p.m.')).toBeInTheDocument();
  });

  it('goes to the checkout', async () => {
    open();
    await userEvent.setup({ delay: null }).click(await screen.findByRole('link', { name: 'Proceed to checkout' }));
    expect(await screen.findByRole('heading', { name: 'Checkout page' })).toBeInTheDocument();
  });

  it('says what to fix before checking out', async () => {
    routes['GET /api/v1/cart'] = () => ({ body: { ...CART, groups: [{ ...CART.groups[0]!, items: [line('I1', 'M1', 'Ribeye, AAA', 1850, 3, { stock: 1, available: false })] }] } });
    open();
    expect(await screen.findByText('Only 1 left — lower the quantity to pay.')).toBeInTheDocument();
    expect(screen.getByText('Remove or lower the items marked above to check out.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Proceed to checkout' })).toHaveAttribute('aria-disabled', 'true');
  });

  it('lets a guest see the cart and sign in on the way to the checkout', async () => {
    user = null;
    open();
    expect(await screen.findByRole('link', { name: 'Sign in to check out' })).toHaveAttribute('href', '/sign-in?next=%2Fcheckout');
    expect(screen.queryByText(/You're browsing as a guest/)).toBeNull(); // redesign § 3.4: no guest banner
  });

  it('has an empty state: a bold line, one sentence, Continue shopping', async () => {
    routes['GET /api/v1/cart'] = () => ({ body: { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } });
    open();
    expect(await screen.findByText('Your cart is empty.')).toBeInTheDocument();
    expect(screen.getByText('Shops near you deliver together on one run — add something to get started.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue shopping' })).toHaveAttribute('href', '/shop');
  });

  it('is in French', async () => {
    open('fr');
    expect(await screen.findByText('4 articles · 2 commerces · une seule livraison')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Panier' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Passer à la caisse' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Supprimer Baguette' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Garder Baguette pour plus tard' })).toBeInTheDocument();
    expect(screen.getAllByText('Sous-total (4 articles) :')).toHaveLength(2);
  });

  it('shows an error with Retry when the cart can’t load', async () => {
    let fail = true;
    routes['GET /api/v1/cart'] = () => (fail ? { status: 500, body: { detail: 'no' } } : { body: CART });
    open();
    await waitFor(() => expect(screen.getByText('We couldn’t load your cart.')).toBeInTheDocument());
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1); // S-142: the error keeps the h1
    fail = false;
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('4 items · 2 shops · one delivery')).toBeInTheDocument();
  });
});

describe('address checks and server messages', () => {
  it('checks a new address with the server’s messages, a saved one not at all', () => {
    expect(addressErrors({ addressId: 'A1' })).toEqual({});
    expect(Object.keys(addressErrors({ street: '', city: 'x', province: 'ZZ', postal: '123' }, ['AB', 'BC']))).toEqual(['address.street', 'address.province', 'address.postal']);
  });

  it('puts the server’s English in French', () => {
    expect(localizeServer('Enter the city.', 'fr')).toBe('Entrez la ville.');
    expect(localizeServer('Only 3 left.', 'fr')).toBe('Plus que 3.');
    expect(localizeServer('Only 3 left.', 'en')).toBe('Only 3 left.');
  });
});
