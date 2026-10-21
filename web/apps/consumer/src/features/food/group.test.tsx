import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { GroupScreen } from './GroupScreen';
import { RestaurantScreen } from './RestaurantScreen';

vi.mock('../cart/stepUp', () => ({
  stepUpWithPasskey: vi.fn(async () => 'proof-passkey'),
  stepUpWithCode: vi.fn(async () => 'proof-totp'),
  enrolPasskey: vi.fn(async () => 'proof-enrol'),
  StepUpFailed: class extends Error {},
}));

const HOST = { id: 'U1', firstName: 'Hana', lastName: 'Host', email: 'hana@example.ca', phone: null, initials: 'HH', locale: 'en-CA', memberSince: '2026-03-02' };
const GUEST = { ...HOST, id: 'U2', firstName: 'Gil', lastName: 'Guest', email: 'gil@example.ca', initials: 'GG' };
const LOCATION = { label: 'Beltline', city: 'Calgary', lat: 51.04, lng: -114.07, street: '1204 17 Ave SW', province: 'AB', postalCode: 'T2T 0B8', zone: 'Beltline', zoneId: 'Z1', marketId: 'M1' };
const card = { merchantId: 'K1', slug: 'pho-lucky', name: 'Pho Lucky', cuisines: ['vietnamese'], dietary: [], priceLevel: '$', open: true, opensAt: null, closesAt: null, paused: false, fulfilment: ['courier', 'pickup'], prepMin: 15, etaFromMin: 25, etaToMin: 35, pickupFromMin: 15, pickupToMin: 20, distanceKm: 1.2, delivers: true, deliveryFeeCents: 299, rating: 4.8, reviews: 12, brandColor: null };
const DISH = { id: 'D2', name: 'Spring rolls', description: null, priceCents: 800, dietary: [], allergens: [], soldOut: false, availableNow: true, availability: 'always', groups: [] };
const RESTAURANT = { kitchen: card, address: '1 Test St', province: 'AB', ahsVerified: true, minOrderCents: 1500, serviceFeeBps: 800, taxBps: 500, slots: [], sections: [{ id: 'S1', name: 'Rolls', menu: 'Main', items: [DISH] }], combos: [], groupOrders: true };

const item = (id: string, title: string, totalCents: number) => ({ id, itemId: 'D2', comboId: null, title, qty: 1, unitCents: totalCents, totalCents, choices: [], note: null });
const share = (over: Record<string, unknown> = {}) => ({ subtotalCents: 800, deliveryFeeCents: 150, serviceFeeCents: 64, feeTaxCents: 11, taxCents: 40, tipCents: 0, totalCents: 1065, state: 'due', paidBy: null, ...over });
const group = (over: Record<string, unknown> = {}) => ({
  id: 'G1', code: '7Q2M-K4XP', state: 'open', payment: 'split', merchantId: 'K1', kitchen: 'Pho Lucky', kitchenSlug: 'pho-lucky', mode: 'delivery',
  scheduledFor: null, area: 'Beltline', hostId: 'U1', you: 'U1', host: true, subtotalCents: 1600, groupMax: 12, tip: { kind: 'none', value: 0 },
  orderId: null, ref: null, canPlace: false, tracking: null, createdAt: '2026-10-20T17:00:00Z', expiresAt: '2026-10-21T17:00:00Z',
  members: [
    { userId: 'U1', name: 'H. Host', host: true, items: [item('I1', 'Spring rolls', 800)], subtotalCents: 800, share: null },
    { userId: 'U2', name: 'G. Guest', host: false, items: [item('I2', 'Spring rolls', 800)], subtotalCents: 800, share: null },
  ],
  ...over,
});

let user: typeof HOST | null;
let routes: Record<string, (c: Call) => { status?: number; body?: unknown } | undefined>;
let calls: Call[];
const server = (c: Call) => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g' } };
  return routes[`${c.method} ${c.url.split('?')[0]}`]?.(c);
};
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp(path, {
    locale,
    routes: {
      foodGroup: () => <GroupScreen code="7Q2M-K4XP" />,
      restaurant: () => <RestaurantScreen slug="pho-lucky" group={path.includes('group=') ? 'G1' : undefined} />,
    },
  });
};
const posts = (url: string) => calls.filter(c => c.method === 'POST' && c.url === url);

beforeEach(() => {
  user = HOST;
  localStorage.clear();
  localStorage.setItem(SAVED_KEY, JSON.stringify(LOCATION));
  routes = {
    'GET /api/v1/me/group-orders/join/7Q2M-K4XP': () => ({ body: { code: '7Q2M-K4XP', kitchen: 'Pho Lucky', kitchenSlug: 'pho-lucky', hostFirstName: 'Hana', state: 'open', people: 1, groupMax: 12, groupId: user?.id === 'U1' ? 'G1' : null } }),
    'GET /api/v1/me/group-orders/G1': () => ({ body: group({ you: user?.id ?? 'U1', host: user?.id !== 'U2' }) }),
    'GET /api/v1/public/kitchens/pho-lucky': () => ({ body: RESTAURANT }),
  };
});
afterEach(() => vi.unstubAllGlobals());

describe('Group food orders (S-159)', () => {
  it('a guest joins from the share link and sees everyone’s dishes', async () => {
    user = GUEST;
    routes['POST /api/v1/me/group-orders/join'] = () => ({ body: group({ you: 'U2', host: false }) });
    open('/food/group/7Q2M-K4XP');
    expect(await screen.findByRole('heading', { name: 'Hana invited you to a group order at Pho Lucky' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    await userEvent.click(screen.getByRole('button', { name: 'Join the group order' }));
    expect(posts('/api/v1/me/group-orders/join')[0]?.body).toEqual({ code: '7Q2M-K4XP' });
    expect(await screen.findByRole('heading', { name: 'Group order · Pho Lucky' })).toBeInTheDocument();
    expect(screen.getByText('You')).toBeInTheDocument();
    expect(screen.getByText('H. Host')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add dishes' })).toHaveAttribute('href', '/food/pho-lucky?group=G1');
    expect(screen.queryByRole('button', { name: 'Lock the order' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Leave the group order' })).toBeInTheDocument();
  });

  it('asks guests to sign in first', async () => {
    user = null;
    open('/food/group/7Q2M-K4XP');
    expect(await screen.findByRole('link', { name: 'Sign in to join' })).toHaveAttribute('href', '/sign-in?next=%2Ffood%2Fgroup%2F7Q2M-K4XP');
  });

  it('the host shares the link, picks the tip and locks the cart; each person pays their share', async () => {
    routes['POST /api/v1/me/group-orders/G1/lock'] = () => ({ body: group({
      state: 'locked',
      members: group().members.map((m, i) => ({ ...m, share: share(i === 0 ? { tipCents: 400, totalCents: 1465 } : {}) })),
    }) });
    routes['POST /api/v1/me/group-orders/G1/shares/U1/pay'] = () => ({ status: 201, body: { shareId: 'G1-U1', totalCents: 1465, paymentIntent: 'pi_1', clientSecret: null, status: 'authorized', mode: 'fake', publishableKey: null } });
    routes['POST /api/v1/me/group-orders/G1/shares/U1/confirm'] = () => ({ body: group({
      state: 'paying',
      members: group().members.map((m, i) => ({ ...m, share: share(i === 0 ? { tipCents: 400, totalCents: 1465, state: 'authorized' } : {}) })),
    }) });
    open('/food/group/7Q2M-K4XP');
    expect(await screen.findByText(/localhost.*\/food\/group\/7Q2M-K4XP/)).toBeInTheDocument();
    expect(screen.getByText(/Code: 7Q2M-K4XP/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '$4' }));
    await userEvent.click(screen.getByRole('button', { name: 'Lock the order' }));
    expect(posts('/api/v1/me/group-orders/G1/lock')[0]?.body).toEqual({ tip: { kind: 'amount', value: 400 } });
    // the host pays their own share or a guest's
    await userEvent.click(await screen.findByRole('button', { name: 'Pay my share · $14.65' }));
    expect(Object.keys(posts('/api/v1/me/group-orders/G1/shares/U1/pay')[0]?.headers ?? {}).some(h => /idempotency-key/i.test(h))).toBe(true);
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Paid')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('1 share still to pay.');
  });

  it('once every share is paid the host sends it to the kitchen, and everyone follows the one order', async () => {
    const paid = group().members.map(m => ({ ...m, share: share({ state: 'authorized' }) }));
    routes['GET /api/v1/me/group-orders/G1'] = () => ({ body: group({ state: 'paying', canPlace: true, members: paid }) });
    routes['POST /api/v1/me/group-orders/G1/place'] = () => ({ body: group({ state: 'placed', orderId: 'F9', ref: 'FD-10042', members: paid,
      tracking: { orderId: 'F9', ref: 'FD-10042', kitchen: 'Pho Lucky', mode: 'delivery', state: 'placed', stage: 'cooking', placedAt: '2026-10-20T17:30:00Z', totalCents: 2130, lines: [] } }) });
    open('/food/group/7Q2M-K4XP');
    await userEvent.click(await screen.findByRole('button', { name: 'Send it to the kitchen' }));
    expect(await screen.findByText(/With the kitchen · FD-10042 · Cooking/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Track the order' })).toHaveAttribute('href', '/food/orders/F9');
  });

  it('cancelling asks first and says paid shares go back', async () => {
    routes['POST /api/v1/me/group-orders/G1/cancel'] = () => ({ body: group({ state: 'cancelled' }) });
    open('/food/group/7Q2M-K4XP');
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel the group order' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel the group order' });
    expect(within(dialog).getByText(/Anyone who paid gets their money back/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel the order' }));
    expect(await screen.findByText('This group order was cancelled.')).toBeInTheDocument();
  });

  it('an unknown code says so and offers to type another', async () => {
    routes['GET /api/v1/me/group-orders/join/7Q2M-K4XP'] = () => ({ status: 404, body: { detail: 'not found' } });
    open('/food/group/7Q2M-K4XP');
    expect(await screen.findByRole('alert')).toHaveTextContent('That code doesn’t match any group order.');
    expect(screen.getByLabelText('Group order code')).toBeInTheDocument();
  });

  it('the restaurant starts a group order, and adds dishes to it from ?group=', async () => {
    routes['POST /api/v1/me/group-orders'] = () => ({ status: 201, body: group() });
    open('/food/pho-lucky');
    await userEvent.click(await screen.findByRole('button', { name: 'Start a group order' }));
    const dialog = await screen.findByRole('dialog', { name: 'Start a group order at Pho Lucky' });
    await userEvent.click(within(dialog).getByRole('radio', { name: /Everyone pays their share/ }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Start the group order' }));
    const body = posts('/api/v1/me/group-orders')[0]?.body as Record<string, unknown>;
    expect(body).toMatchObject({ merchantId: 'K1', mode: 'delivery', payment: 'split', delivery: { street: '1204 17 Ave SW', zone: 'Beltline' } });
  });

  it('speaks French', async () => {
    user = GUEST;
    open('/food/group/7Q2M-K4XP', 'fr');
    expect(await screen.findByRole('heading', { name: 'Hana vous invite à une commande de groupe chez Pho Lucky' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rejoindre la commande de groupe' })).toBeInTheDocument();
  });
});
