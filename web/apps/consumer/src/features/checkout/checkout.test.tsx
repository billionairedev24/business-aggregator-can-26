import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { CheckoutPage } from './CheckoutPage';
import { CompletePage } from './CompletePage';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { readFileSync } from 'node:fs';

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
const quote = (fee: number, extra = {}) => ({ subtotalCents: 3990, deliveryFeeCents: fee, taxCents: Math.round((3990 + fee) * 0.05), taxes: [{ type: 'gst', percent: 5, cents: Math.round((3990 + fee) * 0.05) }], totalCents: 3990 + fee + Math.round((3990 + fee) * 0.05), market: 'Calgary', ...extra });
const STARTED = { checkoutId: 'K1', orderId: 'ORD1', ref: 'NL-50001', totalCents: 4503, expiresAt: '2026-09-30T23:00:00Z', payment: { provider: 'fake', publishableKey: null }, intents: [{ paymentIntent: 'pi_1', clientSecret: 's1', status: 'authorized', amountCents: 1943 }] };
const ORDER = {
  orderId: 'ORD1', ref: 'NL-50001', type: 'goods', state: 'placed', placedAt: '2026-09-30T22:00:00Z', subtotalCents: 3990, deliveryFeeCents: 299, taxCents: 214, totalCents: 4503,
  delivery: { kind: 'pooled', runLabel: 'R-611', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', households: 5, etaAt: null },
  shops: [{ merchantId: 'M1', name: 'Bridgeland Butcher', items: 1, packed: false }, { merchantId: 'M2', name: 'Glenmore Bakery', items: 3, packed: false }],
  steps: [{ key: 'paid', state: 'done' }, { key: 'packing', state: 'current' }, { key: 'pickup', state: 'todo' }, { key: 'delivered', state: 'todo' }],
};

type Reply = { status?: number; body?: unknown } | undefined;
let user: typeof AMARA | null;
let routes: Record<string, (c: Call) => Reply>;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g_x' } };
  const path = c.url.split('?')[0]!;
  return routes[`${c.method} ${path}`]?.(c) ?? routes[path]?.(c);
};
function Complete() {
  const { order, ref } = useSearch({ strict: false }) as { order?: string; ref?: string };
  return <CompletePage orderId={order} refCode={ref} />;
}
const open = (locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp('/checkout', { locale, routes: { checkout: () => <CheckoutPage />, checkoutComplete: () => <Complete />, cart: () => <h1>Cart page</h1> } });
  return { calls, ...view };
};
const starts = (calls: Call[]) => calls.filter(c => c.method === 'POST' && c.url.startsWith('/api/v1/me/checkouts') && !c.url.includes('/place'));
const quoteFor = (c: Call) => quote((c.body as { kind: string; windowId: string | null }).kind === 'direct' ? 999 : (c.body as { windowId: string }).windowId === 'W2' ? 199 : 299);

beforeEach(() => {
  user = AMARA;
  localStorage.clear();
  sessionStorage.clear();
  routes = {
    'GET /api/v1/cart': () => ({ body: CART }),
    'GET /api/v1/me/checkout': () => ({ body: setup() }),
    'POST /api/v1/me/checkout/quote': c => ({ body: quoteFor(c) }),
    'POST /api/v1/me/checkouts': () => ({ status: 201, body: STARTED }),
    'POST /api/v1/me/checkouts/K1/place': () => ({ status: 201, body: { orderId: 'ORD1', ref: 'NL-50001' } }),
    'GET /api/v1/me/orders/ORD1': () => ({ body: ORDER }),
  };
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

const step = (name: string) => screen.getByRole('region', { name: new RegExp(`^${name}$`) });

describe('3-step checkout (redesign § 1.3, AT Shop:502)', () => {
  it('opens on the delivery step beside the summary; Place stays off until the review', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Secure checkout' })).toBeInTheDocument();
    const delivery = await screen.findByRole('region', { name: 'Delivery address and time' });
    await waitFor(() => expect(within(delivery).getByRole('radio', { name: /1204 17 Ave SW, Apt 804/ })).toHaveAttribute('aria-checked', 'true'));
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('4 items')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Checkout steps' })).toBeInTheDocument();
    const windows = within(delivery).getByRole('radiogroup', { name: 'Delivery window' });
    expect(within(windows).getByRole('radio', { name: /Tonight 6.9.p\.m\.\s*Pooled with 5 neighbours\s*\$2\.99/ })).toHaveAttribute('aria-checked', 'true');
    expect(within(windows).getByRole('radio', { name: /Tomorrow 8.11.a\.m\..*order by 7:05.a\.m\.\s*\$1\.99/ })).toBeInTheDocument();
    expect(within(windows).getByRole('radio', { name: /Now · 45 min\s*Direct courier\s*\$9\.99/ })).toBeInTheDocument();
    expect(within(delivery).getByRole('radio', { name: 'Similar item' })).toHaveAttribute('aria-checked', 'true');
    // the later steps are numbered, closed
    expect(step('Payment')).not.toHaveTextContent('Continue to payment');
    const summary = screen.getByRole('complementary', { name: 'Total' });
    expect(await within(summary).findByText('GST 5%')).toBeInTheDocument();
    expect(within(summary).getByText('$2.14')).toBeInTheDocument();
    expect(within(summary).getByText('$45.03')).toBeInTheDocument();
    expect(within(summary).getByRole('button', { name: 'Place your order' })).toBeDisabled();
    expect(within(summary).getByText('Choose the address, the time and the payment to place your order.')).toBeInTheDocument();
  });

  it('walks address → payment → review and places the order, then shows it placed', async () => {
    const { calls, router } = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('radio', { name: /Now · 45 min/ }));
    await within(screen.getByRole('complementary', { name: 'Total' })).findByText('$9.99');
    await u.click(within(step('Delivery address and time')).getByRole('button', { name: 'Continue' }));
    // the finished step folds to a line with Change; focus moves to the next step
    expect(step('Delivery address and time')).toHaveTextContent('1204 17 Ave SW, Apt 804 · Calgary AB T2T 0B8');
    expect(screen.getByRole('button', { name: 'Change: Delivery address and time' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Payment' })).toHaveFocus();
    expect(within(step('Payment')).getByText('Test payments · nothing is charged')).toBeInTheDocument();
    await u.click(within(step('Payment')).getByRole('button', { name: 'Continue to payment' }));
    const start = starts(calls)[0]!;
    expect(start.body).toEqual({ kind: 'direct', windowId: null, address: { addressId: 'A1' }, substitution: 'similar' });
    expect(start.headers['idempotency-key']).toBeTruthy();
    const review = await screen.findByRole('region', { name: 'Review items and delivery' });
    expect(within(review).getByText('Delivery for Glenmore Bakery')).toBeInTheDocument();
    expect(within(review).getAllByText('Now · 45 min')).toHaveLength(2);
    expect(within(review).getByText('Sliced · Qty 2')).toBeInTheDocument();
    expect(step('Payment')).toHaveTextContent('Test payment · nothing is charged');
    const summary = screen.getByRole('complementary', { name: 'Total' });
    expect(within(summary).getByText('$45.03')).toBeInTheDocument(); // the checkout's total, fixed when the payment opened
    await expectNoAxeViolations(document.body);
    await u.click(within(summary).getByRole('button', { name: 'Place your order' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Thank you — your order is placed.' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/checkout/complete');
    expect(router.state.location.search).toEqual({ order: 'ORD1', ref: 'NL-50001' });
    expect(calls.find(c => c.url === '/api/v1/me/checkouts/K1/place')?.headers['idempotency-key']).toBeTruthy();
    expect(await screen.findByText('Order NL-50001 · $45.03')).toBeInTheDocument();
    expect(screen.getByText(/Arriving tonight 6.9.p\.m/)).toBeInTheDocument();
    expect(screen.getByText('We’ve told the 2 shops. You’ll get a message when it’s on its way.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Track your order' })).toHaveAttribute('href', '/orders/ORD1');
    // the cart is read again after placing (the api removed the ordered lines)
    expect(calls.filter(c => c.url === '/api/v1/cart').length).toBeGreaterThan(1);
  });

  it('keeps the sticky summary under the header as drawn (its measured height, else the header token)', () => {
    const checkoutCss = readFileSync('src/features/checkout/checkout.css', 'utf8'); // vitest runs in apps/consumer
    expect(checkoutCss).toMatch(/\.co-summary \{[^}]*position: sticky; top: calc\(var\(--nl-header-h, var\(--size-header\)\) \+ var\(--space-4\)\)/);
    expect(checkoutCss).not.toContain('104px');
  });

  it('asks for no quote once the order is placed (the emptied cart answered 409: demo rehearsal)', async () => {
    let placedAt = -1;
    const { calls } = open();
    routes['POST /api/v1/me/checkouts/K1/place'] = () => { placedAt = calls.length; return { status: 201, body: { orderId: 'ORD1', ref: 'NL-50001' } }; };
    routes['POST /api/v1/me/checkout/quote'] = c => (placedAt >= 0 ? { status: 409, body: { code: 'cart_empty', detail: 'Your cart is empty.' } } : { body: quoteFor(c) });
    routes['GET /api/v1/cart'] = () => ({ body: placedAt >= 0 ? { ...CART, itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } : CART });
    const errors = vi.spyOn(console, 'error');
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(within(step('Payment')).getByRole('button', { name: 'Continue to payment' }));
    await screen.findByRole('region', { name: 'Review items and delivery' });
    await u.click(within(screen.getByRole('complementary', { name: 'Total' })).getByRole('button', { name: 'Place your order' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Thank you — your order is placed.' })).toBeInTheDocument();
    await waitFor(() => expect(calls.filter(c => c.url === '/api/v1/cart').length).toBeGreaterThan(1));
    expect(placedAt).toBeGreaterThan(0);
    expect(calls.slice(placedAt).filter(c => c.url.startsWith('/api/v1/me/checkout/quote'))).toEqual([]);
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('opens a new checkout when the delivery changes after the payment opened', async () => {
    const { calls } = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(within(step('Payment')).getByRole('button', { name: 'Continue to payment' }));
    await screen.findByRole('button', { name: 'Change: Payment' });
    await u.click(screen.getByRole('button', { name: 'Change: Delivery address and time' }));
    await u.click(await screen.findByRole('radio', { name: /Tomorrow 8.11/ }));
    await u.click(within(step('Delivery address and time')).getByRole('button', { name: 'Continue' }));
    await u.click(within(step('Payment')).getByRole('button', { name: 'Continue to payment' }));
    await screen.findByRole('button', { name: 'Change: Payment' });
    const [first, second] = starts(calls);
    expect((second!.body as { windowId: string }).windowId).toBe('W2');
    expect(second!.headers['idempotency-key']).not.toBe(first!.headers['idempotency-key']);
  });

  it('asks for a new address with its messages when none is saved', async () => {
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ addresses: [] }) });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Deliver to this address' }));
    expect(await screen.findByText('Enter the street address.')).toBeInTheDocument();
    expect(screen.getByText('Enter a Canadian postal code, like T2P 1B5.')).toBeInTheDocument();
    expect(screen.getByText('2 things need attention.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Street address'), '1204 17 Ave SW');
    await u.type(screen.getByLabelText('Postal code'), 't2t 0b8');
    await waitFor(() => expect(screen.queryByText('Enter the street address.')).not.toBeInTheDocument());
    await u.click(screen.getByRole('button', { name: 'Deliver to this address' }));
    expect(await screen.findByRole('button', { name: 'Continue to payment' })).toBeInTheDocument();
  });

  it('shows the server’s answers: sold out', async () => {
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 409, body: { code: 'out_of_stock', detail: 'x' } });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something in your cart just sold out. Check your cart and try again.');
  });

  it('steps up with the authenticator when the sign-in was a phone code only', async () => {
    vi.stubEnv('VITE_NL_DEV_STEP_UP', '1');
    let asked = 0;
    routes['POST /api/v1/me/checkouts'] = c => (c.headers['x-step-up'] === 'dev' ? { status: 201, body: STARTED } : (asked++, { status: 403, body: { code: 'step_up_required', detail: 'x' } }));
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ stepUp: 'required' }) });
    const { calls } = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    expect(within(step('Payment')).getByText('Paying asks for a second factor.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    const dialog = await screen.findByRole('dialog', { name: 'Confirm it’s you' });
    await u.click(within(dialog).getByRole('button', { name: 'Use my passkey' }));
    expect(await screen.findByRole('region', { name: 'Review items and delivery' })).toBeInTheDocument();
    expect(asked).toBe(1);
    const keys = starts(calls).map(c => c.headers['idempotency-key']);
    expect(new Set(keys).size).toBe(1); // the retry after the step-up reuses the attempt's key
  });

  it('offers to add a passkey when the account has no second factor', async () => {
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 403, body: { code: 'second_factor_required', detail: 'x' } });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a passkey to pay' });
    expect(within(dialog).getByRole('button', { name: 'Add a passkey' })).toBeInTheDocument();
  });

  it('pays with Stripe’s Payment Element: checks the card, then authorizes every intent on Place', async () => {
    const submit = vi.fn(async () => ({}));
    const confirmPayment = vi.fn(async () => ({ paymentIntent: { id: 'pi_1', status: 'requires_capture', payment_method: 'pm_1' } }));
    const confirmCardPayment = vi.fn(async () => ({ paymentIntent: { id: 'pi_2', status: 'requires_capture' } }));
    const mount = vi.fn();
    vi.stubGlobal('Stripe', () => ({ elements: () => ({ create: () => ({ mount, destroy: vi.fn() }), submit }), confirmPayment, confirmCardPayment }));
    const stripe = { ...STARTED, payment: { provider: 'stripe', publishableKey: 'pk_test_fake' }, intents: [
      { paymentIntent: 'pi_1', clientSecret: 'pi_1_secret_fake', status: 'requires_payment_method', amountCents: 2500 },
      { paymentIntent: 'pi_2', clientSecret: 'pi_2_secret_fake', status: 'requires_payment_method', amountCents: 2003 },
    ] };
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ payment: { provider: 'stripe', publishableKey: 'pk_test_fake' } }) });
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 201, body: stripe });
    const { calls } = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    await waitFor(() => expect(mount).toHaveBeenCalled());
    await u.click(await screen.findByRole('button', { name: 'Use this payment method' }));
    expect(submit).toHaveBeenCalledTimes(1);
    await screen.findByRole('region', { name: 'Review items and delivery' });
    expect(step('Payment')).toHaveTextContent('Card, Apple Pay or Google Pay');
    // the element stays mounted (hidden) while the review is open
    expect(screen.getByTestId('stripe-payment-element')).toBeInTheDocument();
    expect(calls.some(c => c.url.includes('/place'))).toBe(false);
    await u.click(within(screen.getByRole('region', { name: 'Review items and delivery' })).getByRole('button', { name: 'Place your order' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Thank you — your order is placed.' })).toBeInTheDocument();
    expect(confirmPayment).toHaveBeenCalledTimes(1);
    expect(confirmCardPayment).toHaveBeenCalledWith('pi_2_secret_fake', { payment_method: 'pm_1' });
    expect(mount).toHaveBeenCalledTimes(1);
  });

  it('S-157: offers Apple Pay and Google Pay in the Payment Element when the api allows them (same intents, same step-up)', async () => {
    const create = vi.fn((_type: string, _options: Record<string, unknown>) => ({ mount: vi.fn(), destroy: vi.fn() }));
    vi.stubGlobal('Stripe', () => ({ elements: () => ({ create, submit: vi.fn(async () => ({})) }), confirmPayment: vi.fn(), confirmCardPayment: vi.fn() }));
    routes['GET /api/v1/public/payments/options'] = () => ({ body: { provider: 'stripe', applePay: true, googlePay: false } });
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ payment: { provider: 'stripe', publishableKey: 'pk_test_fake' } }) });
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 201, body: { ...STARTED, payment: { provider: 'stripe', publishableKey: 'pk_test_fake' }, intents: [
      { paymentIntent: 'pi_1', clientSecret: 'pi_1_secret_fake', status: 'requires_payment_method', amountCents: 4503 }] } });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledWith('payment', { layout: 'tabs', wallets: { applePay: 'auto', googlePay: 'never' } });
  });

  it('S-157: cards only when the options can’t be read', async () => {
    const create = vi.fn((_type: string, _options: Record<string, unknown>) => ({ mount: vi.fn(), destroy: vi.fn() }));
    vi.stubGlobal('Stripe', () => ({ elements: () => ({ create, submit: vi.fn(async () => ({})) }), confirmPayment: vi.fn(), confirmCardPayment: vi.fn() }));
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ payment: { provider: 'stripe', publishableKey: 'pk_test_fake' } }) });
    routes['POST /api/v1/me/checkouts'] = () => ({ status: 201, body: { ...STARTED, payment: { provider: 'stripe', publishableKey: 'pk_test_fake' }, intents: [
      { paymentIntent: 'pi_1', clientSecret: 'pi_1_secret_fake', status: 'requires_payment_method', amountCents: 4503 }] } });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await within(await screen.findByRole('region', { name: 'Delivery address and time' })).findByRole('button', { name: 'Continue' }));
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0]![1]).toMatchObject({ wallets: { applePay: 'never', googlePay: 'never' } });
  });

  it('asks guests to sign in on the way', async () => {
    user = null;
    open();
    expect(await screen.findByText('Sign in to check out.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in?next=%2Fcheckout');
  });

  it('has an empty state', async () => {
    routes['GET /api/v1/cart'] = () => ({ body: { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] } });
    open();
    expect(await screen.findByText('Your cart is empty.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continue shopping' })).toHaveAttribute('href', '/shop');
  });

  it('is in French', async () => {
    open('fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Paiement sécurisé' })).toBeInTheDocument();
    expect(await screen.findByRole('radio', { name: /Ce soir 18 h – 21 h\s*Groupé avec 5 voisins/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Article similaire' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Adresse et heure de livraison' })).toBeInTheDocument();
    expect(await screen.findByText('TPS 5 %')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Passer la commande' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Continuer' })).toBeInTheDocument();
  });
});

describe('promo code, points and tip at checkout (mobile gaps part 2)', () => {
  type Body = { promoCode?: string; usePoints?: boolean; tip?: { kind: string; value: number } };
  it('applies a code, spends points and adds a tip — the quote and the payment carry them', async () => {
    const bodies: Body[] = [];
    routes['POST /api/v1/me/checkout/quote'] = c => {
      const b = c.body as Body;
      bodies.push(b);
      const discount = b.promoCode === 'FALL10' ? 200 : 0;
      const tip = b.tip?.kind === 'amount' ? b.tip.value : 0;
      const points = b.usePoints ? 900 : 0;
      return { body: quote(299, { discountCents: discount, promoCode: b.promoCode ?? null, tipCents: tip, pointsCents: points, points, pointsAvailable: 1240 }) };
    };
    const { calls } = open();
    const u = userEvent.setup({ delay: null });
    const delivery = await screen.findByRole('region', { name: 'Delivery address and time' });
    await u.click(await within(delivery).findByRole('radio', { name: '$4.00' }));
    await u.click(within(delivery).getByRole('button', { name: 'Continue' }));
    await u.type(await screen.findByLabelText('Promo code'), 'fall10');
    await u.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('FALL10 applied · $2.00 off')).toBeInTheDocument();
    expect(screen.getByText('Promo FALL10')).toBeInTheDocument();
    await u.click(screen.getByRole('checkbox', { name: 'Use my points · 1,240 points available' }));
    await waitFor(() => expect(screen.getByText('−$9.00')).toBeInTheDocument());
    await waitFor(() => expect(bodies.at(-1)).toMatchObject({ promoCode: 'FALL10', usePoints: true, tip: { kind: 'amount', value: 400 } }));
    await u.click(screen.getByRole('button', { name: 'Continue to payment' }));
    expect(starts(calls)[0]!.body).toMatchObject({ promoCode: 'FALL10', usePoints: true, tip: { kind: 'amount', value: 400 } });
  });

  it('shows why a code is refused, in French', async () => {
    routes['POST /api/v1/me/checkout/quote'] = c => ((c.body as Body).promoCode === 'NOPE'
      ? { status: 422, body: { errors: [{ field: 'promoCode', rule: 'promo', message: 'Ce code n’est pas valide.' }] } }
      : { body: quoteFor(c) });
    open('fr');
    const u = userEvent.setup({ delay: null });
    expect(await screen.findByRole('heading', { name: 'Pourboire au livreur · 100 % lui revient' })).toBeInTheDocument();
    await u.click(await screen.findByRole('button', { name: 'Continuer' }));
    await u.type(await screen.findByLabelText('Code promo'), 'nope');
    await u.click(screen.getByRole('button', { name: 'Appliquer' }));
    expect(await screen.findByText('Ce code n’est pas valide.')).toBeInTheDocument();
  });
});

describe('Age-restricted items at checkout (2026-10-04)', () => {
  const ALCOHOL_AGE = { required: true, minimumAge: 18, classes: ['alcohol'], state: 'none' };

  it('asks for a one-time photo ID check before the payment, and opens the provider', async () => {
    const { leave } = await import('../cart/age');
    const go = vi.spyOn(leave, 'to').mockImplementation(() => {});
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ age: ALCOHOL_AGE }) });
    routes['POST /api/v1/me/checkout/quote'] = () => ({ body: { ...quote(299), age: ALCOHOL_AGE } });
    routes['POST /api/v1/me/age-verification'] = () => ({ body: { url: 'https://verify.example/vs_1', status: { state: 'pending', ageFloor: 0 } } });
    const { calls } = open();
    expect(await screen.findByRole('heading', { level: 2, name: 'Photo ID needed' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    expect(screen.getByText('Your cart has alcohol. You must be 18 or older to buy them where they’re delivered.')).toBeInTheDocument();
    await waitFor(() => expect(within(step('Delivery address and time')).getByRole('button', { name: 'Continue' })).toBeDisabled());
    expect(within(step('Delivery address and time')).getByText('Verify your age to pay.')).toBeInTheDocument();
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: 'Verify my age' }));
    await waitFor(() => expect(go).toHaveBeenCalledWith('https://verify.example/vs_1'));
    expect(calls.find(c => c.method === 'POST' && c.url.startsWith('/api/v1/me/age-verification'))?.body).toEqual({ returnTo: 'web' });
    go.mockRestore();
  });

  it('lets a verified customer continue and says the courier checks ID at the door', async () => {
    const verified = { ...ALCOHOL_AGE, state: 'verified' };
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ age: verified }) });
    routes['POST /api/v1/me/checkout/quote'] = () => ({ body: { ...quote(299), age: verified } });
    open();
    expect(await screen.findByRole('heading', { level: 2, name: 'Age verified' })).toBeInTheDocument();
    expect(screen.getByText(/Verified 18\+ · The courier checks photo ID at the door/)).toBeInTheDocument();
    await waitFor(() => expect(within(step('Delivery address and time')).getByRole('button', { name: 'Continue' })).toBeEnabled());
  });

  it('tells an under-age customer to remove the items, in French', async () => {
    const under = { ...ALCOHOL_AGE, minimumAge: 19, state: 'under_age' };
    routes['GET /api/v1/me/checkout'] = () => ({ body: setup({ age: under }) });
    routes['POST /api/v1/me/checkout/quote'] = () => ({ body: { ...quote(299), age: under } });
    open('fr');
    expect(await screen.findByRole('heading', { level: 2, name: 'Pièce d’identité avec photo requise' })).toBeInTheDocument();
    expect(screen.getByText('Votre panier contient de l’alcool. Vous devez avoir 19 ans ou plus pour les acheter là où ils sont livrés.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('vous avez moins de 19 ans');
    expect(screen.queryByRole('button', { name: 'Vérifier mon âge' })).toBeNull();
  });
});

describe('order placed page', () => {
  it('works without the order’s details, and without an order', async () => {
    routes['GET /api/v1/me/orders/ORD9'] = () => ({ status: 404, body: { detail: 'no' } });
    mockFetch(server);
    renderApp('/checkout/complete?order=ORD9&ref=NL-9', { routes: { checkoutComplete: () => <Complete /> } });
    expect(await screen.findByRole('heading', { level: 1, name: 'Thank you — your order is placed.' })).toBeInTheDocument();
    expect(await screen.findByText('Order NL-9')).toBeInTheDocument();
  });

  it('says so when no order is named', async () => {
    mockFetch(server);
    renderApp('/checkout/complete', { locale: 'fr', routes: { checkoutComplete: () => <Complete /> } });
    expect(await screen.findByRole('heading', { level: 1, name: 'Commande introuvable.' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Voir vos commandes' })).toHaveAttribute('href', '/account/orders');
  });
});
