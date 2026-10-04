import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { CartPage } from './CartPage';
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
    { merchantId: 'M2', shopName: 'Glenmore Bakery', items: [line('I2', 'M2', 'Country sourdough', 750, 2, { option: 'Sliced', unit: '900 g' }), line('I3', 'M2', 'Baguette', 640, 1)] },
  ],
};
const TONIGHT = { id: 'W1', kind: 'pooled', windowId: 'W1', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', orderBy: '2026-09-30T23:20:00Z', packBy: '2026-09-30T23:45:00Z', feeCents: 299, households: 5, etaMinutes: null };
const MORNING = { ...TONIGHT, id: 'W2', windowId: 'W2', day: 'tomorrow', startsAt: '2026-10-01T14:00:00Z', endsAt: '2026-10-01T17:00:00Z', orderBy: '2026-10-01T13:05:00Z', packBy: '2026-10-01T13:30:00Z', feeCents: 199, households: 0 };
const DIRECT = { id: 'direct', kind: 'direct', windowId: null, day: null, startsAt: null, endsAt: null, orderBy: null, packBy: null, feeCents: 999, households: 0, etaMinutes: 45 };
const HOME = { id: 'A1', street: '1204 17 Ave SW', unit: 'Apt 804', city: 'Calgary', province: 'AB', postal: 'T2T 0B8', note: 'Buzz 0804 · leave at door', isDefault: true };
const setup = (extra = {}) => ({ cart: CART, addresses: [HOME], options: [TONIGHT, MORNING, DIRECT], payment: { provider: 'fake', publishableKey: null }, stepUp: 'none', market: 'Calgary', served: true, ...extra });
const quote = (fee: number) => ({ subtotalCents: 3990, deliveryFeeCents: fee, taxCents: Math.round((3990 + fee) * 0.05), taxes: [{ type: 'gst', percent: 5, cents: Math.round((3990 + fee) * 0.05) }], totalCents: 3990 + fee + Math.round((3990 + fee) * 0.05), market: 'Calgary' });
const STARTED = { checkoutId: 'K1', orderId: 'ORD1', ref: 'NL-50001', totalCents: 4504, expiresAt: '2026-09-30T23:00:00Z', payment: { provider: 'fake', publishableKey: null }, intents: [{ paymentIntent: 'pi_1', clientSecret: 's1', status: 'authorized', amountCents: 1943 }] };

type Reply = { status?: number; body?: unknown } | undefined;
let user: typeof AMARA | null;
let routes: Record<string, (c: Call) => Reply>;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g_x' } };
  const path = c.url.split('?')[0]!;
  const key = `${c.method} ${path}`;
  return routes[key]?.(c) ?? routes[path]?.(c);
};
const open = (locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp('/cart', { locale, routes: { cart: () => <CartPage />, confirmed: () => <h1>Order page</h1> } });
  return { calls, ...view };
};

beforeEach(() => {
  user = AMARA;
  localStorage.clear();
  sessionStorage.clear();
  routes = {
    'GET /api/v1/cart': () => ({ body: CART }),
    'GET /api/v1/me/checkout': () => ({ body: setup() }),
    'POST /api/v1/me/checkout/quote': c => ({ body: quote((c.body as { kind: string; windowId: string | null }).kind === 'direct' ? 999 : (c.body as { windowId: string }).windowId === 'W2' ? 199 : 299) }),
    'POST /api/v1/me/checkouts': () => ({ status: 201, body: STARTED }),
    'POST /api/v1/me/checkouts/K1/place': () => ({ status: 201, body: { orderId: 'ORD1', ref: 'NL-50001' } }),
  };
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('Cart and checkout (design 06 cart)', () => {
  it('keeps the page h1 while the cart loads (S-142)', async () => {
    mockFetch(server);
    const answer = globalThis.fetch;
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => (String(input).startsWith('/api/v1/cart') ? new Promise(() => {}) : answer(input, init)));
    renderApp('/cart', { routes: { cart: () => <CartPage /> } });
    expect(await screen.findByText('Loading your cart…')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('shows the multi-shop cart, windows, address, substitutions and the summary with tax', async () => {
    open();
    await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull());
    await waitFor(() => expect(document.querySelector('.cart-grid')).not.toBeNull());
    expect(screen.getByRole('heading', { level: 1, name: 'Checkout' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('4 items · 2 shops · one delivery')).toBeInTheDocument();
    const butcher = screen.getByRole('region', { name: 'Bridgeland Butcher' });
    expect(within(butcher).getByRole('link', { name: 'Ribeye, AAA' })).toHaveAttribute('href', '/products/p-I1');
    expect(await within(butcher).findByText('closes to run 5:45 p.m.')).toBeInTheDocument();
    expect(screen.getByText('Sliced · 900 g')).toBeInTheDocument();
    const windows = screen.getByRole('radiogroup', { name: 'Delivery window' });
    expect(within(windows).getByRole('radio', { name: /Tonight 6.9.p\.m\.\s*Pooled with 5 neighbours\s*\$2\.99/ })).toHaveAttribute('aria-checked', 'true');
    expect(within(windows).getByRole('radio', { name: /Tomorrow 8.11.a\.m\..*order by 7:05.a\.m\.\s*\$1\.99/ })).toBeInTheDocument();
    expect(within(windows).getByRole('radio', { name: /Now · 45 min\s*Direct courier\s*\$9\.99/ })).toBeInTheDocument();
    expect(screen.getByText('1204 17 Ave SW, Apt 804')).toBeInTheDocument();
    expect(screen.getByText('Calgary AB T2T 0B8 · Buzz 0804 · leave at door')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Similar item' })).toHaveAttribute('aria-checked', 'true');
    const summary = screen.getByRole('complementary', { name: 'Total' });
    expect(await within(summary).findByText('GST 5%')).toBeInTheDocument();
    expect(within(summary).getByText('$2.14')).toBeInTheDocument();
    expect(within(summary).getByRole('button', { name: 'Pay $45.03' })).toBeEnabled();
  });

  it('changes quantities and removes lines', async () => {
    const { calls } = open();
    routes['PATCH /api/v1/cart/items/I2'] = () => ({ body: { ...CART, itemCount: 5 } });
    routes['DELETE /api/v1/cart/items/I3'] = () => ({ body: { ...CART, itemCount: 3 } });
    const user = userEvent.setup({ delay: null });
    await user.click(await screen.findByRole('button', { name: 'One more Country sourdough' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PATCH')?.body).toEqual({ qty: 3 }));
    await user.click(screen.getByRole('button', { name: 'Remove Baguette' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url === '/api/v1/cart/items/I3')).toBe(true));
  });

  it('quotes the window picked and pays with the local stand-in, then opens the order', async () => {
    const { calls } = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('radio', { name: /Now · 45 min/ }));
    const summary = screen.getByRole('complementary', { name: 'Total' });
    await within(summary).findByText('$9.99');
    const pay = await within(summary).findByRole('button', { name: /Pay \$52\.38/ });
    await u.click(pay);
    const start = calls.find(c => c.method === 'POST' && c.url.startsWith('/api/v1/me/checkouts'))!;
    expect(start.body).toEqual({ kind: 'direct', windowId: null, address: { addressId: 'A1' }, substitution: 'similar' });
    expect(start.headers['idempotency-key']).toBeTruthy();
    expect(await screen.findByText('Your bank · Verified by Visa')).toBeInTheDocument();
    expect(screen.getByText('Confirm $45.04 to Northline')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('heading', { name: 'Order page' })).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/me/checkouts/K1/place')?.headers['idempotency-key']).toBeTruthy();
  });

  it('asks for a new address with its messages when none is saved', async () => {
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ addresses: [] }) });
    open();
    const u = userEvent.setup({ delay: null });
    const pay = await screen.findByRole('button', { name: /Pay/ });
    await u.click(pay);
    expect(await screen.findByText('Enter the street address.')).toBeInTheDocument();
    expect(screen.getByText('Enter a Canadian postal code, like T2P 1B5.')).toBeInTheDocument();
    expect(screen.getByText('2 things need attention.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Street address'), '1204 17 Ave SW');
    await u.type(screen.getByLabelText('Postal code'), 't2t 0b8');
    await waitFor(() => expect(screen.queryByText('Enter the street address.')).not.toBeInTheDocument());
  });

  it('shows the server’s answers: sold out, not delivered here', async () => {
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 409, body: { code: 'out_of_stock', detail: 'x' } });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: /Pay \$45\.03/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something in your cart just sold out. Check your cart and try again.');
  });

  it('steps up with the authenticator when the sign-in was a phone code only', async () => {
    vi.stubEnv('VITE_NL_DEV_STEP_UP', '1');
    let asked = 0;
    routes['POST /api/v1/me/checkouts'] = c => (c.headers['x-step-up'] === 'dev' ? { status: 201, body: STARTED } : (asked++, { status: 403, body: { code: 'step_up_required', detail: 'x' } }));
    const { calls } = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: /Pay \$45\.03/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Confirm it’s you' });
    expect(within(dialog).getByText('Payments sit behind a second factor. Use your passkey or authenticator app.')).toBeInTheDocument();
    await u.click(within(dialog).getByRole('button', { name: 'Use my passkey' }));
    expect(await screen.findByText('Your bank · Verified by Visa')).toBeInTheDocument();
    expect(asked).toBe(1);
    const keys = calls.filter(c => c.url.startsWith('/api/v1/me/checkouts')).map(c => c.headers['idempotency-key']);
    expect(new Set(keys).size).toBe(1); // the retry after the step-up reuses the Pay attempt's key
    vi.unstubAllEnvs();
  });

  it('offers to add a passkey when the account has no second factor', async () => {
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 403, body: { code: 'second_factor_required', detail: 'x' } });
    open();
    await userEvent.setup({ delay: null }).click(await screen.findByRole('button', { name: /Pay/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a passkey to pay' });
    expect(within(dialog).getByRole('button', { name: 'Add a passkey' })).toBeInTheDocument();
  });

  it('lets a guest see the cart and sign in to pay', async () => {
    user = null;
    open();
    await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull());
    await waitFor(() => expect(document.querySelector('.cart-grid')).not.toBeNull());
    expect(screen.getByRole('heading', { level: 1, name: 'Checkout' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in to pay' })).toHaveAttribute('href', '/sign-in?next=%2Fcart');
    expect(screen.queryByRole('radiogroup', { name: 'Delivery window' })).not.toBeInTheDocument();
    expect(screen.getByText(/You're browsing as a guest/)).toBeInTheDocument();
  });

  it('has an empty state', async () => {
    routes['GET /api/v1/cart'] = () => ({ body: { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } });
    open();
    expect(await screen.findByText('Your cart is empty.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse the Shop' })).toHaveAttribute('href', '/shop');
  });

  it('is in French', async () => {
    open('fr');
    expect(await screen.findByText('4 articles · 2 commerces · une seule livraison')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Paiement' })).toBeInTheDocument();
    expect(await screen.findByRole('radio', { name: /Ce soir 18 h – 21 h\s*Groupé avec 5 voisins/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Article similaire' })).toBeInTheDocument();
    expect(await screen.findByText('TPS 5 %')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Payer 45,03\s\$/ })).toBeInTheDocument();
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
