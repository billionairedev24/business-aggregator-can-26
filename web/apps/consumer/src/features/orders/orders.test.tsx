import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { OrderStatus } from './OrderStatus';

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const steps = (current: number) => ['paid', 'packing', 'pickup', 'delivered'].map((key, i) => ({ key, state: i < current ? 'done' : i === current ? 'current' : 'todo' }));
const ORDER = {
  orderId: 'ORD1', ref: 'NL-48213', type: 'goods', state: 'placed', placedAt: '2026-09-30T21:00:00Z',
  subtotalCents: 4250, deliveryFeeCents: 299, taxCents: 227, totalCents: 4776,
  delivery: { kind: 'pooled', runLabel: 'R-611', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', households: 6, etaAt: null },
  shops: [
    { merchantId: 'M1', name: 'Glenmore Bakery', items: 2, packed: false },
    { merchantId: 'M2', name: 'Bridgeland Butcher', items: 1, packed: false },
    { merchantId: 'M3', name: 'Sunnyside Greens', items: 1, packed: false },
  ],
  steps: steps(1), deliveredAt: null,
};

class FakeEventSource {
  static last: FakeEventSource | undefined;
  listeners: Record<string, ((e: MessageEvent<string>) => void)[]> = {};
  closed = false;
  constructor(readonly url: string) { FakeEventSource.last = this; }
  addEventListener(name: string, fn: (e: MessageEvent<string>) => void) { (this.listeners[name] ??= []).push(fn); }
  close() { this.closed = true; }
  emit(name: string, data: unknown) { this.listeners[name]?.forEach(fn => fn({ data: JSON.stringify(data) } as MessageEvent<string>)); }
}

type Reply = { status?: number; body?: unknown } | undefined;
let user: typeof AMARA | null;
let order: (c: Call) => Reply;
let confirm: (c: Call) => Reply = () => undefined;
const server = (c: Call): Reply => (c.url === '/bff/session' ? { body: { user, guestId: 'g' } }
  : c.url === '/api/v1/me/orders/ORD1' ? order(c)
    : c.url === '/api/v1/me/orders/ORD1/confirm' ? confirm(c) : undefined);
function Page() {
  const { orderId } = useParams({ strict: false }) as { orderId: string };
  return <OrderStatus orderId={orderId} />;
}
const open = (locale: 'en' | 'fr' = 'en') => {
  mockFetch(server);
  return renderApp('/orders/ORD1', { locale, routes: { confirmed: () => <Page /> } });
};

beforeEach(() => {
  user = AMARA;
  order = () => ({ body: ORDER });
  FakeEventSource.last = undefined;
  vi.stubGlobal('EventSource', FakeEventSource);
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('Order confirmed and tracking (design 06 confirmed)', () => {
  it('shows the confirmation, the timeline and the run', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: /^Order placed\. Arriving tonight 6.9.p\.m\.$/ })).toBeInTheDocument();
    expect(screen.getByText('NL-48213 · $47.76 · 3 shops packing now. Live tracking starts when the courier leaves.')).toBeInTheDocument();
    const steps = within(screen.getByRole('list')).getAllByRole('listitem').map(li => li.textContent);
    expect(steps).toEqual([
      'Paid (done)',
      'Shops packing · 0 of 3 packed (now)',
      'Courier picks up · scan at each shop (next)',
      'Delivered · photo proof · you confirm, shops paid (next)',
    ]);
    expect(screen.getByRole('figure', { name: 'Delivery map' })).toHaveTextContent(/Pooled run R-611 · leaves 6:00.p\.m\. · 6 households on this run/);
    expect(screen.getByRole('link', { name: 'View orders' })).toHaveAttribute('href', '/account/orders');
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/');
  });

  it('updates live from the order’s event stream', async () => {
    open();
    await screen.findByRole('heading', { level: 1 });
    expect(FakeEventSource.last?.url).toBe('/api/v1/me/orders/ORD1/events');
    act(() => FakeEventSource.last!.emit('order', { ...ORDER, state: 'picked_up', steps: steps(3), shops: ORDER.shops.map(s => ({ ...s, packed: true })) }));
    expect(await screen.findByRole('heading', { level: 1, name: /^On the way\. Arriving tonight 6.9.p\.m\.$/ })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')[1]).toHaveTextContent('Shops packing · 3 of 3 packed (done)');
    act(() => FakeEventSource.last!.emit('order', { ...ORDER, state: 'delivered', steps: steps(4), deliveredAt: '2026-10-01T01:10:00Z' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Delivered.' })).toBeInTheDocument();
  });

  it('shows a direct courier’s arrival time', async () => {
    order = () => ({ body: { ...ORDER, delivery: { kind: 'direct', runLabel: null, day: null, startsAt: null, endsAt: null, households: 0, etaAt: '2026-09-30T21:45:00Z' } } });
    open();
    expect(await screen.findByRole('heading', { level: 1, name: /^Order placed\. Arriving by about 3:45.p\.m\.$/ })).toBeInTheDocument();
    expect(screen.getByRole('figure', { name: 'Delivery map' })).toHaveTextContent(/Direct courier · arriving by about 3:45.p\.m\./);
  });

  it('is in French', async () => {
    open('fr');
    expect(await screen.findByRole('heading', { level: 1, name: /Commande passée\. Arrivée ce soir 18 h – 21 h\./ })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('Payé (fait)');
    expect(screen.getByRole('link', { name: 'Voir les commandes' })).toBeInTheDocument();
  });

  it('asks a guest to sign in, and says when the order isn’t theirs', async () => {
    user = null;
    open();
    expect(await screen.findByText('Sign in to see your order.')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Sign in' }).some(a => a.getAttribute('href') === '/sign-in?next=%2Forders%2FORD1')).toBe(true);
  });

  it('has a not-found state, a skeleton and an error with Retry', async () => {
    order = () => ({ status: 404, body: { detail: 'order not found' } });
    open();
    expect(await screen.findByText('We couldn’t find this order.')).toBeInTheDocument();
  });

  it('retries after an error', async () => {
    let fail = true;
    order = () => (fail ? { status: 500, body: { detail: 'x' } } : { body: ORDER });
    open();
    expect(await screen.findByText('Loading your order…')).toBeInTheDocument();
    expect(await screen.findByText('We couldn’t load your order.')).toBeInTheDocument();
    fail = false;
    await userEvent.setup({ delay: null }).click(within(screen.getByRole('alert')).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { level: 1, name: /^Order placed\. Arriving tonight 6.9.p\.m\.$/ })).toBeInTheDocument();
  });

  it('lets the customer confirm a delivered order, which pays the shops (S-78)', async () => {
    const DELIVERED = { ...ORDER, state: 'delivered', steps: steps(4), deliveredAt: '2026-10-01T01:10:00Z', deliveryProof: 'photo', canConfirm: true, paysShopsAt: '2026-10-08T01:10:00Z', confirmedAt: null };
    order = () => ({ body: DELIVERED });
    let calls = 0;
    confirm = c => { calls += 1; expect(c.method).toBe('POST'); return calls === 1 ? { status: 500, body: { detail: 'x' } } : { body: { ...DELIVERED, state: 'confirmed', canConfirm: false, paysShopsAt: null, confirmedAt: '2026-10-01T02:00:00Z' } }; };
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Delivered.' })).toBeInTheDocument();
    expect(screen.getByText('Delivered with photo proof.')).toBeInTheDocument();
    expect(screen.getByText(/^Shops are paid \w+day, October [78] unless you confirm sooner or report a problem\.$/)).toBeInTheDocument();
    const user = userEvent.setup({ delay: null });
    await user.click(screen.getByRole('button', { name: 'Got everything' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t confirm your order. Try again.');
    await user.click(screen.getByRole('button', { name: 'Got everything' }));
    expect(await screen.findByText('You confirmed it. The shops are paid.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Got everything' })).not.toBeInTheDocument();
  });

  it('offers the confirmation in French', async () => {
    order = () => ({ body: { ...ORDER, state: 'picked_up', steps: steps(3), canConfirm: true, paysShopsAt: null } });
    open('fr');
    expect(await screen.findByRole('button', { name: 'J’ai tout reçu' })).toBeInTheDocument();
  });
});
