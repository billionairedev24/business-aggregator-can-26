import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { CartPage } from './CartPage';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** Mobile gaps part 2 on the cart: promo code, points and the courier's tip go to the quote and the payment. */
const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const item = { itemId: 'I1', merchantId: 'M1', offerId: 'o1', variantId: null, productId: 'p1', name: 'Country sourdough', option: null, unit: null, imageUrl: null, unitCents: 2000, qty: 1, lineCents: 2000, stock: 9, available: true, handlingDays: 0 };
const CART = { itemCount: 1, shopCount: 1, subtotalCents: 2000, groups: [{ merchantId: 'M1', shopName: 'Glenmore Bakery', items: [item] }] };
const DIRECT = { id: 'direct', kind: 'direct', windowId: null, day: null, startsAt: null, endsAt: null, orderBy: null, packBy: null, feeCents: 999, households: 0, etaMinutes: 45 };
const HOME = { id: 'A1', street: '1 Sample St', unit: null, city: 'Sampleville', province: 'AB', postal: 'T0T 0T0', note: null, isDefault: true };
const SETUP = { cart: CART, addresses: [HOME], options: [DIRECT], payment: { provider: 'fake', publishableKey: null }, stepUp: 'none', market: 'Sampleville', served: true };

type Body = { promoCode?: string; usePoints?: boolean; tip?: { kind: string; value: number } };
let quoteBodies: Body[];
const quote = (b: Body) => {
  if (b.promoCode === 'NOPE') return { status: 422, body: { errors: [{ field: 'promoCode', rule: 'promo', message: 'This code isn’t valid.' }] } };
  const discount = b.promoCode === 'FALL10' ? 200 : 0;
  const tip = b.tip?.kind === 'amount' ? b.tip.value : b.tip?.kind === 'percent' ? Math.round(2000 * b.tip.value / 100) : 0;
  const points = b.usePoints ? 900 : 0;
  const tax = Math.round((2000 - discount + 999) * 0.05);
  return { body: { subtotalCents: 2000, deliveryFeeCents: 999, taxCents: tax, taxes: [{ type: 'gst', percent: 5, cents: tax }], totalCents: 2000 - discount + 999 + tax + tip - points, market: 'Sampleville', promoCode: b.promoCode ?? null, discountCents: discount, points, pointsCents: points, pointsAvailable: 1240, tipCents: tip } };
};
const server = (c: Call) => {
  if (c.url === '/bff/session') return { body: { user: AMARA, guestId: 'g' } };
  const path = c.url.split('?')[0];
  if (path === '/api/v1/cart') return { body: CART };
  if (path === '/api/v1/me/checkout') return { body: SETUP };
  if (path === '/api/v1/me/checkout/quote') { quoteBodies.push(c.body as Body); return quote(c.body as Body); }
  if (path === '/api/v1/me/checkouts') return { status: 201, body: { checkoutId: 'K1', orderId: 'ORD1', ref: 'NL-1', totalCents: 1, expiresAt: '2026-10-09T23:00:00Z', payment: { provider: 'fake', publishableKey: null }, intents: [] } };
  return undefined;
};

beforeEach(() => { quoteBodies = []; localStorage.clear(); });
afterEach(() => { vi.unstubAllGlobals(); });

describe('Cart promo code, points and tip (mobile gaps part 2)', () => {
  it('applies a code, spends points and adds a tip — the quote shows each line', async () => {
    const calls = mockFetch(server);
    renderApp('/cart', { routes: { cart: () => <CartPage />, confirmed: () => <h1>Order page</h1> } });
    const u = userEvent.setup();
    await u.type(await screen.findByLabelText('Promo code'), 'fall10');
    await u.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('FALL10 applied · $2.00 off')).toBeInTheDocument();
    expect(screen.getByText('Promo FALL10')).toBeInTheDocument();
    expect(screen.getByText('−$2.00')).toBeInTheDocument();
    await u.click(screen.getByRole('checkbox', { name: 'Use my points · 1,240 points available' }));
    await waitFor(() => expect(screen.getByText('−$9.00')).toBeInTheDocument());
    await u.click(screen.getByRole('radio', { name: '$4.00' }));
    await waitFor(() => expect(quoteBodies.at(-1)).toMatchObject({ promoCode: 'FALL10', usePoints: true, tip: { kind: 'amount', value: 400 } }));
    await expectNoAxeViolations(document.body);
    await u.click(screen.getByRole('button', { name: /^Pay/ }));
    const start = calls.find(c => c.method === 'POST' && c.url.startsWith('/api/v1/me/checkouts'))!;
    expect(start.body).toMatchObject({ promoCode: 'FALL10', usePoints: true, tip: { kind: 'amount', value: 400 } });
  });

  it('shows why a code is refused, in French', async () => {
    mockFetch(c => (c.url.split('?')[0] === '/api/v1/me/checkout/quote' && (c.body as Body).promoCode === 'NOPE'
      ? { status: 422, body: { errors: [{ field: 'promoCode', rule: 'promo', message: 'Ce code n’est pas valide.' }] } }
      : server(c)));
    renderApp('/cart', { locale: 'fr', routes: { cart: () => <CartPage /> } });
    const u = userEvent.setup();
    await u.type(await screen.findByLabelText('Code promo'), 'nope');
    await u.click(screen.getByRole('button', { name: 'Appliquer' }));
    expect(await screen.findByText('Ce code n’est pas valide.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Pourboire au livreur · 100 % lui revient' })).toBeInTheDocument();
  });
});
