import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { CheckoutScreen } from './CheckoutScreen';
import { FOOD_CART_KEY } from './foodCart';
import { FoodScreen } from './FoodScreen';
import { RestaurantScreen } from './RestaurantScreen';
import { TrackScreen } from './TrackScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550201', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };

const card = (extra = {}) => ({
  merchantId: 'K1', slug: 'pho-lucky', name: 'Pho Lucky', cuisines: ['vietnamese'], dietary: ['halal'], priceLevel: '$',
  open: true, opensAt: null, closesAt: '2026-10-01T04:00:00Z', paused: false, fulfilment: ['courier', 'pickup', 'scheduled'], prepMin: 15,
  etaFromMin: 25, etaToMin: 35, pickupFromMin: 15, pickupToMin: 20, distanceKm: 1.2, delivers: true, deliveryFeeCents: 299,
  rating: 4.8, reviews: 120, brandColor: null, ...extra,
});
const PHO = card();
const NONNA = card({ merchantId: 'K2', slug: 'nonnas', name: 'Nonna’s', cuisines: ['italian'], dietary: ['vegan'], priceLevel: '$$', open: false, opensAt: '2026-10-01T00:00:00Z', fulfilment: ['courier'], etaFromMin: 40, etaToMin: 55, distanceKm: 3.4, deliveryFeeCents: 399, reviews: 0, rating: 0 });
const RAMEN = card({ merchantId: 'K3', slug: 'ramen-ya', name: 'Ramen Ya', cuisines: ['ramen'], dietary: [], open: false, paused: true, fulfilment: ['pickup'], pickupFromMin: 10, pickupToMin: 15 });

const opt = (id: string, name: string, deltaCents = 0, extra = {}) => ({ id, name, deltaCents, isDefault: false, soldOut: false, ...extra });
const DISH_PHO = {
  id: 'D1', name: 'Pho tai', description: 'Rare beef, 12-hour broth', priceCents: 1600, dietary: [], allergens: ['wheat'], soldOut: false, availableNow: true, availability: 'always',
  groups: [
    { id: 'G1', name: 'Size', rule: 'exactly', count: 1, required: true, showForOptionIds: [], options: [opt('O1', 'Regular'), opt('O2', 'Large', 300)] },
    { id: 'G2', name: 'Extras', rule: 'up_to', count: 2, required: false, showForOptionIds: [], options: [opt('O3', 'Brisket', 250), opt('O4', 'Egg', 150, { soldOut: true })] },
  ],
};
const DISH_ROLLS = { id: 'D2', name: 'Spring rolls', description: null, priceCents: 800, dietary: ['vegetarian'], allergens: [], soldOut: false, availableNow: true, availability: 'always', groups: [] };
const DISH_GONE = { ...DISH_ROLLS, id: 'D3', name: 'Banh mi', soldOut: true };
const COMBO = { id: 'CB1', name: 'Lunch for one', pricing: 'fixed', priceCents: 2000, discountBps: null, fromCents: 2000, saveCents: 400, availableNow: true, slots: [{ label: 'Soup', qty: 1, itemIds: ['D1'] }, { label: 'Side', qty: 1, itemIds: ['D2'] }] };
const restaurant = (kitchen = {}, extra = {}) => ({
  kitchen: card(kitchen), address: '1129 Edmonton Tr NE', province: 'AB', ahsVerified: true, minOrderCents: 1500, serviceFeeBps: 800, taxBps: 500,
  slots: ['2026-10-01T01:00:00Z', '2026-10-01T01:30:00Z'], sections: [{ id: 'S1', name: 'Soups & rolls', menu: 'Main', items: [DISH_PHO, DISH_ROLLS, DISH_GONE] }], combos: [COMBO], ...extra,
});

const TOTALS = {
  kitchen: 'Pho Lucky', mode: 'delivery', scheduledFor: null,
  lines: [{ itemId: 'D1', comboId: null, title: 'Pho tai', qty: 2, unitCents: 1900, totalCents: 3800, choices: ['Large'], note: null }],
  subtotalCents: 3800, deliveryFeeCents: 299, serviceFeeCents: 304, tipCents: 400, taxCents: 205, feeTaxCents: 30, totalCents: 5008,
  etaFromMin: 25, etaToMin: 35, estimate: false,
};
const STARTED = { orderId: 'F1', ref: 'NL-F10001', totals: TOTALS, paymentIntent: 'pi_fake_1', clientSecret: null, status: 'authorized', mode: 'fake', publishableKey: null };
const LOCATION = { label: 'Beltline, Calgary', city: 'Calgary', lat: 51.04, lng: -114.07, street: '1204 17 Ave SW', unit: 'Apt 804', province: 'AB', postalCode: 'T2T 0B8', zone: 'Beltline', zoneId: 'Z1', marketId: 'M1' };
const CART = {
  merchantId: 'K1', slug: 'pho-lucky', name: 'Pho Lucky',
  lines: [{ key: 'l1', kind: 'item', itemId: 'D1', title: 'Pho tai', qty: 2, unitCents: 1900, optionIds: ['O2'], choices: ['Large'], itemIds: [] }],
};

type Reply = { status?: number; body?: unknown } | undefined;
let user: typeof AMARA | null;
let routes: Record<string, (c: Call) => Reply>;
const server = (c: Call): Reply => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g_x' } };
  const path = c.url.split('?')[0]!;
  return routes[`${c.method} ${path}`]?.(c) ?? routes[path]?.(c);
};
const screens = {
  food: () => <FoodScreen />,
  restaurant: () => <RestaurantScreen slug="pho-lucky" />,
  foodCheckout: () => <CheckoutScreen />,
  foodTrack: () => <TrackScreen orderId="F1" />,
};
const open = (path: string, locale: 'en' | 'fr' = 'en', extra: Partial<typeof screens> = {}) => {
  const calls = mockFetch(server);
  return { calls, ...renderApp(path, { locale, routes: { ...screens, ...extra } }) };
};

beforeEach(() => {
  user = AMARA;
  localStorage.clear();
  sessionStorage.clear();
  localStorage.setItem(SAVED_KEY, JSON.stringify(LOCATION));
  routes = {
    'GET /api/v1/public/kitchens': () => ({ body: { city: 'Calgary', items: [PHO, NONNA, RAMEN] } }),
    'GET /api/v1/public/kitchens/pho-lucky': () => ({ body: restaurant() }),
    'POST /api/v1/me/food-orders/quote': () => ({ body: TOTALS }),
    'POST /api/v1/me/food-orders': () => ({ status: 201, body: STARTED }),
    'POST /api/v1/me/food-orders/F1/confirm': () => ({ body: { orderId: 'F1', ref: 'NL-F10001' } }),
  };
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe('Food landing (design 06 food)', () => {
  it('lists the kitchens delivering to the saved location, open ones with their ETA and fee', async () => {
    const { calls } = open('/food');
    expect(await screen.findByRole('heading', { level: 2, name: '2 kitchens delivering to Beltline' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText(/^1 kitchen open · delivering to Beltline in 25–35 min/)).toBeInTheDocument();
    expect(calls.find(c => c.url.startsWith('/api/v1/public/kitchens'))!.url).toBe('/api/v1/public/kitchens?city=Calgary&lat=51.04000&lng=-114.07000');
    const pho = screen.getByRole('link', { name: /Pho Lucky/ });
    expect(pho).toHaveAttribute('href', '/food/pho-lucky');
    expect(pho).toHaveTextContent('Vietnamese · $ · 1.2 km · $2.99');
    expect(pho).toHaveTextContent('★ 4.8 (120)');
    expect(pho).toHaveTextContent('Halal');
    const nonna = screen.getByRole('link', { name: /Nonna’s/ });
    expect(nonna).toHaveTextContent(/Opens/);
    expect(nonna).toHaveTextContent('New');
    expect(screen.queryByRole('link', { name: /Ramen Ya/ })).not.toBeInTheDocument(); // pickup only
  });

  it('switches to pickup, where paused kitchens say so', async () => {
    open('/food');
    const u = userEvent.setup({ delay: null });
    await screen.findByRole('heading', { level: 2, name: '2 kitchens delivering to Beltline' });
    await u.click(screen.getByRole('radio', { name: 'Pickup' }));
    expect(screen.getByRole('heading', { level: 2, name: '2 kitchens for pickup near Beltline' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Ramen Ya/ })).toHaveTextContent('Paused');
    expect(screen.getByRole('link', { name: /Pho Lucky/ })).toHaveTextContent('Ready in 15–20 min');
    expect(sessionStorage.getItem('nl.food.mode')).toBe('pickup');
  });

  it('filters by diet and cuisine, and clears filters when nothing matches', async () => {
    open('/food');
    const u = userEvent.setup({ delay: null });
    await screen.findByRole('heading', { level: 2, name: '2 kitchens delivering to Beltline' });
    const filters = screen.getByRole('group', { name: 'Filters' });
    await u.click(within(filters).getByRole('button', { name: 'Halal' }));
    expect(screen.getByRole('heading', { level: 2, name: '1 kitchen delivering to Beltline' })).toBeInTheDocument();
    await u.click(within(screen.getByRole('group', { name: 'Cuisines' })).getByRole('button', { name: 'Italian' }));
    expect(screen.getByRole('heading', { level: 2, name: 'Italian · 0 kitchens' })).toBeInTheDocument();
    expect(screen.getByText('No kitchen matches these filters.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByRole('heading', { level: 2, name: '2 kitchens delivering to Beltline' })).toBeInTheDocument();
  });

  it('starts on the cuisine from the link (home tiles)', async () => {
    open('/food', 'en', { food: () => <FoodScreen cuisine="italian" /> });
    expect(await screen.findByRole('heading', { level: 2, name: 'Italian · 1 kitchen' })).toBeInTheDocument();
  });

  it('says when no kitchen serves the area, and when loading fails', async () => {
    routes['GET /api/v1/public/kitchens'] = () => ({ body: { city: 'Calgary', items: [] } });
    const first = open('/food');
    expect(await screen.findByText('No kitchens near Beltline yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Choose another location' })).toHaveAttribute('href', '/location');
    first.unmount();
    routes['GET /api/v1/public/kitchens'] = () => ({ status: 500, body: { detail: 'boom' } });
    open('/food');
    expect(await screen.findByText('We couldn’t load the kitchens.')).toBeInTheDocument();
  });

  it('speaks French', async () => {
    open('/food', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Restaurants' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: '2 cuisines livrent à Beltline' })).toBeInTheDocument();
    expect(screen.getByText(/^1 cuisine ouverte · livraison à Beltline en 25 à 35 min/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Pho Lucky/ })).toHaveTextContent('Vietnamien · $ · 1,2 km');
  });
});

describe('Restaurant (design 06 restaurant)', () => {
  it('shows the banner, combos first, dish tags, sold-out dishes and the empty order', async () => {
    open('/food/pho-lucky');
    expect(await screen.findByRole('heading', { level: 1, name: 'Pho Lucky' })).toBeInTheDocument();
    expect(screen.getByText('Vietnamese · 1129 Edmonton Tr NE · ★ 4.8 (120)')).toBeInTheDocument();
    expect(screen.getByText('AHS permit verified')).toBeInTheDocument();
    expect(screen.getByText('Min order $15')).toBeInTheDocument();
    const sections = screen.getByRole('navigation', { name: 'Menu sections' });
    expect(within(sections).getAllByRole('link').map(a => a.textContent)).toEqual(['Combos', 'Soups & rolls']);
    expect(screen.getByText('from $20.00 · save $4.00')).toBeInTheDocument();
    expect(screen.getByText('Contains wheat')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add Banh mi' })).toBeDisabled();
    expect(screen.getByText('Nothing yet — tap + on a dish. Minimum order $15.')).toBeInTheDocument();
  });

  it('enforces the pick rules, prices the choices and adds to the food order', async () => {
    open('/food/pho-lucky');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Add Pho tai' }));
    expect(screen.getByRole('group', { name: /Size · pick 1 · required/ })).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Add · $16.00' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Pick 1 for Size.');
    await u.click(screen.getByRole('button', { name: 'Large +$3.00' }));
    expect(screen.getByRole('button', { name: /Egg \+\$1\.50 · Sold out/ })).toBeDisabled();
    await u.click(screen.getByRole('button', { name: 'One more' }));
    await u.type(screen.getByLabelText('Special instructions'), 'no onions');
    await u.click(screen.getByRole('button', { name: 'Add · $38.00' }));

    const aside = screen.getByRole('complementary', { name: 'Your order · Pho Lucky' });
    expect(within(aside).getByText('Large, no onions')).toBeInTheDocument();
    expect(within(aside).getAllByText('$38.00')).toHaveLength(2); // the line and the items total
    expect(within(aside).getByText('$3.04')).toBeInTheDocument(); // 8% service fee
    expect(within(aside).getByText('$2.05')).toBeInTheDocument(); // GST 5% on items + service
    expect(within(aside).getByRole('button', { name: 'Checkout · $46.08' })).toBeEnabled();
    const saved = JSON.parse(localStorage.getItem(FOOD_CART_KEY)!);
    expect(saved).toMatchObject({ merchantId: 'K1', slug: 'pho-lucky', lines: [{ kind: 'item', itemId: 'D1', qty: 2, unitCents: 1900, optionIds: ['O2'], note: 'no onions' }] });
    expect(localStorage.getItem('nl.cart')).toBeNull(); // the shop cart is separate
  });

  it('holds checkout until the minimum, and adds combos with their picks', async () => {
    open('/food/pho-lucky');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Add Spring rolls' }));
    await u.click(screen.getByRole('button', { name: 'Add · $8.00' }));
    expect(screen.getByRole('button', { name: 'Add $7.00 to reach minimum' })).toBeDisabled();
    await u.click(screen.getByRole('button', { name: 'Add Lunch for one' }));
    expect(screen.getByLabelText('Soup · 1 of 1')).toHaveValue('D1');
    await u.click(screen.getByRole('button', { name: 'Add · $20.00' }));
    const saved = JSON.parse(localStorage.getItem(FOOD_CART_KEY)!);
    expect(saved.lines[1]).toMatchObject({ kind: 'combo', comboId: 'CB1', itemIds: ['D1', 'D2'], choices: ['Pho tai', 'Spring rolls'], unitCents: 2000 });
    expect(screen.getByRole('button', { name: /^Checkout · / })).toBeEnabled();
  });

  it('asks before replacing an order from another kitchen', async () => {
    localStorage.setItem(FOOD_CART_KEY, JSON.stringify({ ...CART, merchantId: 'K9', slug: 'other', name: 'Taco Norte' }));
    open('/food/pho-lucky');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Add Spring rolls' }));
    await u.click(screen.getByRole('button', { name: 'Add · $8.00' }));
    const dialog = screen.getByRole('dialog', { name: 'Start a new order?' });
    expect(dialog).toHaveTextContent('Your order from Taco Norte will be cleared.');
    await u.click(within(dialog).getByRole('button', { name: 'Start new order' }));
    expect(JSON.parse(localStorage.getItem(FOOD_CART_KEY)!)).toMatchObject({ merchantId: 'K1', lines: [{ itemId: 'D2' }] });
  });

  it('says when the kitchen is paused and keeps its menu closed', async () => {
    routes['GET /api/v1/public/kitchens/pho-lucky'] = () => ({ body: restaurant({ open: false, paused: true }, { slots: [] }) });
    open('/food/pho-lucky');
    expect(await screen.findByText('Pho Lucky isn’t taking orders right now. Try again in a few minutes.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add Pho tai' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add Lunch for one' })).toBeDisabled();
  });

  it('speaks French', async () => {
    open('/food/pho-lucky', 'fr');
    expect(await screen.findByText('Commande min. 15 $')).toBeInTheDocument();
    expect(screen.getByText('Contient : blé')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ajouter Pho tai' })).toBeInTheDocument();
  });
});

describe('Food checkout (design 06 foodCheckout)', () => {
  beforeEach(() => localStorage.setItem(FOOD_CART_KEY, JSON.stringify(CART)));

  it('asks guests to sign in to pay', async () => {
    user = null;
    open('/food/checkout');
    expect(await screen.findByRole('link', { name: 'Sign in to pay' })).toHaveAttribute('href', expect.stringContaining('food%2Fcheckout'));
  });

  it('prices on the server, pays into escrow with an idempotency key and opens tracking', async () => {
    const { calls } = open('/food/checkout', 'en', { foodTrack: () => <h1>Tracking page</h1> });
    const u = userEvent.setup({ delay: null });
    expect(await screen.findByRole('heading', { level: 1, name: 'Food checkout · Pho Lucky' })).toBeInTheDocument();
    expect(screen.getByText('1204 17 Ave SW, Apt 804')).toBeInTheDocument();
    const pay = await screen.findByRole('button', { name: 'Pay $50.08' });
    const quote = calls.find(c => c.url === '/api/v1/me/food-orders/quote')!;
    expect(quote.body).toMatchObject({
      merchantId: 'K1', mode: 'delivery', scheduledFor: null, items: [{ itemId: 'D1', qty: 2, optionIds: ['O2'] }], combos: [],
      tip: { kind: 'amount', value: 400 }, delivery: { street: '1204 17 Ave SW', province: 'AB', lat: 51.04, lng: -114.07, zoneId: 'Z1', dropoff: 'hand', extras: [] },
    });
    expect(screen.getByText('The kitchen is paid once your order is handed off; until then the money is held in escrow.')).toBeInTheDocument();
    await u.click(pay);
    expect(await screen.findByText('Your bank · Verified by Visa')).toBeInTheDocument();
    const start = calls.find(c => c.method === 'POST' && c.url === '/api/v1/me/food-orders')!;
    expect(start.headers['idempotency-key']).toBeTruthy();
    await u.click(screen.getByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('heading', { name: 'Tracking page' })).toBeInTheDocument();
    expect(calls.find(c => c.url === '/api/v1/me/food-orders/F1/confirm')!.headers['idempotency-key']).toBeTruthy();
    expect(localStorage.getItem(FOOD_CART_KEY)).toBeNull();
  });

  it('sends pickup without an address or tip, and a scheduled window', async () => {
    const { calls } = open('/food/checkout');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('radio', { name: /Pickup/ }));
    await waitFor(() => expect(calls.some(c => (c.body as { mode?: string } | undefined)?.mode === 'pickup')).toBe(true));
    expect(calls.find(c => (c.body as { mode?: string } | undefined)?.mode === 'pickup')!.body).toMatchObject({ delivery: null, tip: { kind: 'none', value: 0 } });
    expect(screen.queryByText('Drop-off')).not.toBeInTheDocument();
    await u.click(screen.getByRole('radio', { name: /Schedule/ }));
    await waitFor(() => expect(calls.some(c => (c.body as { scheduledFor?: string } | undefined)?.scheduledFor === '2026-10-01T01:00:00Z')).toBe(true));
  });

  it('asks for the photo ID check for a dish with alcohol, returning to the food checkout (2026-10-04)', async () => {
    const age = { required: true, minimumAge: 18, classes: ['alcohol'], state: 'none' };
    const base = routes['POST /api/v1/me/food-orders/quote']!;
    routes['POST /api/v1/me/food-orders/quote'] = c => { const r = base(c)!; return { ...r, body: { ...(r.body as object), age } }; };
    open('/food/checkout');
    expect(await screen.findByRole('heading', { level: 2, name: 'Photo ID needed' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Pay $50.08' })).toBeDisabled();
    expect(screen.getByText(/the alcohol goes back to the restaurant and is refunded; the rest of the meal, the fees and the tip are charged/)).toBeInTheDocument();
  });

  it('"when" is one Tab stop; the arrow keys move between ASAP, schedule and pickup and select (S-140)', async () => {
    const { calls } = open('/food/checkout');
    const u = userEvent.setup({ delay: null });
    const group = await screen.findByRole('radiogroup', { name: /when/i });
    const radios = within(group).getAllByRole('radio');
    expect(radios.filter(r => r.getAttribute('tabindex') === '0')).toHaveLength(1);
    const first = radios.find(r => r.getAttribute('tabindex') === '0')!;
    first.focus();
    await u.keyboard('{End}');
    const pickup = within(group).getByRole('radio', { name: /Pickup/ });
    expect(pickup).toHaveFocus();
    expect(pickup).toHaveAttribute('aria-checked', 'true');
    await waitFor(() => expect(calls.some(c => (c.body as { mode?: string } | undefined)?.mode === 'pickup')).toBe(true));
    await u.keyboard('{ArrowLeft}');
    expect(within(group).getByRole('radio', { name: /Schedule/ })).toHaveAttribute('aria-checked', 'true');
  });

  it('steps up with the authenticator when the sign-in was a phone code only (S-51’s rule)', async () => {
    vi.stubEnv('VITE_NL_DEV_STEP_UP', '1');
    let asked = 0;
    routes['POST /api/v1/me/food-orders'] = c => (c.headers['x-step-up'] === 'dev' ? { status: 201, body: STARTED } : (asked++, { status: 403, body: { code: 'step_up_required', detail: 'x' } }));
    const { calls } = open('/food/checkout');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Pay $50.08' }));
    const dialog = await screen.findByRole('dialog', { name: 'Confirm it’s you' });
    await u.click(within(dialog).getByRole('button', { name: 'Use my passkey' }));
    expect(await screen.findByText('Your bank · Verified by Visa')).toBeInTheDocument();
    expect(asked).toBe(1);
    const keys = calls.filter(c => c.method === 'POST' && c.url === '/api/v1/me/food-orders').map(c => c.headers['idempotency-key']);
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(1);
  });

  it('shows the kitchen closing and validation messages', async () => {
    routes['POST /api/v1/me/food-orders'] = () => ({ status: 409, body: { code: 'kitchen_closed', detail: 'closed' } });
    open('/food/checkout');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Pay $50.08' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This kitchen stopped taking orders. Schedule for later, or pick another kitchen.');
  });

  it('says the order is empty', async () => {
    localStorage.removeItem(FOOD_CART_KEY);
    open('/food/checkout');
    expect(await screen.findByText('Your food order is empty.')).toBeInTheDocument();
  });

  it('speaks French', async () => {
    open('/food/checkout', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Paiement · Pho Lucky' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^Payer 50,08/ })).toBeInTheDocument();
  });
});

describe('Food tracking (design 06 foodTrack)', () => {
  const tracking = (extra = {}) => ({
    orderId: 'F1', ref: 'NL-F10001', kitchen: 'Pho Lucky', kitchenSlug: 'pho-lucky', mode: 'delivery', state: 'placed', stage: 'cooking',
    placedAt: '2026-09-30T23:00:00Z', scheduledFor: null, acceptedAt: '2026-09-30T23:01:00Z', prepMin: 15, readyBy: '2026-09-30T23:16:00Z',
    readyAt: null, handedOffAt: null, deliveredAt: null, eta: '2026-09-30T23:35:00Z', totalCents: 5008, lines: TOTALS.lines, ...extra,
  });

  it('follows the kitchen display: cooking, then on the way', async () => {
    routes['GET /api/v1/me/food-orders/F1'] = () => ({ body: tracking() });
    open('/food/orders/F1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Pho Lucky is cooking' })).toBeInTheDocument();
    expect(screen.getByText('Kitchen confirmed')).toBeInTheDocument();
    expect(screen.getByText(/^NL-F10001 · Pho Lucky · \$50\.08 · arriving/)).toBeInTheDocument();
    expect(screen.getByText('Cooking · 15 min').closest('li')).toHaveAttribute('aria-current', 'step');
  });

  it('shows the courier on the way, live from the order’s stream, with the drop-off PIN (S-88)', async () => {
    const listeners: Record<string, ((e: MessageEvent<string>) => void)[]> = {};
    let url = '';
    vi.stubGlobal('EventSource', class {
      constructor(u: string) { url = u; }
      addEventListener(name: string, fn: (e: MessageEvent<string>) => void) { (listeners[name] ??= []).push(fn); }
      close() {}
    });
    const courier = { state: 'planned', runLabel: null, courierName: 'Kai', eta: '2026-09-30T23:35:00Z', stopsBefore: 0, lat: null, lng: null, positionAt: null, pin: '4821' };
    routes['GET /api/v1/me/food-orders/F1'] = () => ({ body: tracking({ courier }) });
    open('/food/orders/F1');
    expect(await screen.findByText(/^Kai will bring your order\. At your door about /)).toBeInTheDocument();
    expect(screen.getByText('Drop-off PIN 4821')).toBeInTheDocument();
    await waitFor(() => expect(url).toBe('/api/v1/me/food-orders/F1/events'));
    act(() => listeners.food?.forEach(fn => fn({ data: JSON.stringify(tracking({ stage: 'on_the_way', courier: { ...courier, state: 'picked_up', lat: 50.01, lng: -100, positionAt: '2026-09-30T23:30:00Z' } })) } as MessageEvent<string>)));
    expect(await screen.findByText(/^Kai is on the way\. You’re next\. At your door about /)).toBeInTheDocument();
    expect(screen.getByText(/^Live · updated /)).toBeInTheDocument();
    vi.unstubAllGlobals();
  });

  it('shows pickup orders ready at the counter', async () => {
    routes['GET /api/v1/me/food-orders/F1'] = () => ({ body: tracking({ mode: 'pickup', stage: 'ready' }) });
    open('/food/orders/F1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Ready for pickup at Pho Lucky' })).toBeInTheDocument();
    expect(screen.getByText('Ready for pickup').closest('li')).toHaveAttribute('aria-current', 'step');
  });

  it('says when the order is not found, and asks guests to sign in', async () => {
    routes['GET /api/v1/me/food-orders/F1'] = () => ({ status: 404, body: { detail: 'no' } });
    const first = open('/food/orders/F1');
    expect(await screen.findByText('We couldn’t find this order.')).toBeInTheDocument();
    first.unmount();
    user = null;
    open('/food/orders/F1', 'fr');
    // the page's own sign-in link (the header has one too for guests)
    expect(await within(await screen.findByRole('main')).findByRole('link', { name: 'Se connecter' })).toBeInTheDocument();
  });
});
