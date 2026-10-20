import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useParams } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { OrderStatus } from './OrderStatus';

/** S-151: cancelling an order, a missed delivery's choice and returns on the order page. */
const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const steps = (current: number) => ['paid', 'packing', 'pickup', 'delivered'].map((key, i) => ({ key, state: i < current ? 'done' : i === current ? 'current' : 'todo' }));
const ORDER = {
  orderId: 'ORD1', ref: 'NL-48213', type: 'goods', state: 'placed', placedAt: '2026-09-30T21:00:00Z',
  subtotalCents: 4250, deliveryFeeCents: 299, taxCents: 227, totalCents: 4776,
  delivery: { kind: 'pooled', runLabel: 'R-611', day: 'today', startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-10-01T03:00:00Z', households: 6, etaAt: null },
  shops: [
    { merchantId: 'M1', name: 'Glenmore Bakery', items: 2, packed: false },
    { merchantId: 'M2', name: 'Bridgeland Butcher', items: 1, packed: false },
  ],
  steps: steps(1), deliveredAt: null, canCancel: true, cancellations: [], returnable: [], returns: [],
};
const CANCELLED = {
  ...ORDER, state: 'cancelled', canCancel: false,
  cancellations: [{ by: 'customer', reason: 'changed_mind', at: '2026-09-30T21:05:00Z', whole: true, merchantName: null }],
};
const FAILED = {
  ...ORDER, state: 'delivery_failed', canCancel: false, steps: steps(3),
  deliveryFailure: { reason: 'absent', failedAt: '2026-10-01T01:00:00Z', decideBy: '2026-10-03T01:00:00Z', attempts: 1, choice: null, chosenAt: null, feeCents: 250 },
};
const RETURN = {
  id: 'RET1', ref: 'RT-1001', orderId: 'ORD1', orderRef: 'NL-48213', merchantId: 'M1', merchantName: 'Glenmore Bakery',
  state: 'declined', reason: 'damaged', note: null, hasPhoto: true, method: null, restock: false, declineReason: 'It was used',
  amountCents: 1200, lines: [{ lineId: 'L1', title: 'Sourdough', qty: 2, amountCents: 1200 }],
  createdAt: '2026-10-02T10:00:00Z', decidedAt: '2026-10-02T12:00:00Z', collectedAt: null, receivedAt: null, refundedAt: null,
  canWithdraw: false, canEscalate: true,
};
const DELIVERED = {
  ...ORDER, state: 'delivered', canCancel: false, steps: steps(4), deliveredAt: '2026-10-01T01:10:00Z',
  returnable: [
    { lineId: 'L1', merchantId: 'M1', title: 'Sourdough', qty: 2, amountCents: 1200, returnUntil: '2026-10-31T01:10:00Z' },
    { lineId: 'L2', merchantId: 'M2', title: 'Brisket', qty: 1, amountCents: 2200, returnUntil: '2026-10-31T01:10:00Z' },
  ],
};

class FakeEventSource {
  addEventListener() {}
  close() {}
}

type Reply = { status?: number; body?: unknown } | undefined;
let order: unknown;
let post: (c: Call) => Reply;
const server = (c: Call): Reply => (c.url === '/bff/session' ? { body: { user: AMARA, guestId: 'g' } }
  : c.url === '/api/v1/me/orders/ORD1' && c.method === 'GET' ? { body: order }
    : c.method === 'POST' ? post(c) : undefined);
function Page() {
  const { orderId } = useParams({ strict: false }) as { orderId: string };
  return <OrderStatus orderId={orderId} />;
}
const open = (locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  renderApp('/orders/ORD1', { locale, routes: { confirmed: () => <Page /> } });
  return calls;
};

beforeEach(() => {
  order = ORDER;
  post = () => undefined;
  vi.stubGlobal('EventSource', FakeEventSource);
});
afterEach(() => vi.unstubAllGlobals());

describe('Cancelling an order (S-151)', () => {
  it('asks why, sends the reason and note, then shows the cancelled order', async () => {
    post = c => (c.url === '/api/v1/me/orders/ORD1/cancel' ? { body: CANCELLED } : undefined);
    const calls = open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Cancel order' }));
    expect(screen.getByRole('heading', { name: 'Cancel this order?' })).toBeInTheDocument();
    // a reason is needed
    await u.click(screen.getByRole('button', { name: 'Cancel the order' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a reason.');
    await u.click(screen.getByRole('button', { name: 'I ordered by mistake' }));
    expect(screen.getByRole('button', { name: 'I ordered by mistake' })).toHaveAttribute('aria-pressed', 'true');
    await u.type(screen.getByLabelText('Anything to add? (optional)'), ' wrong address ');
    await u.click(screen.getByRole('button', { name: 'Cancel the order' }));
    expect(await screen.findByText('You cancelled this order. The card holds were released.')).toBeInTheDocument();
    expect(calls.find(c => c.url.endsWith('/cancel'))?.body).toEqual({ reason: 'ordered_by_mistake', note: 'wrong address' });
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  it('shows the server’s refusal when a shop packed meanwhile', async () => {
    post = () => ({ status: 409, body: { code: 'too_late', detail: 'A shop has already packed this order.' } });
    open();
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Cancel order' }));
    await u.click(screen.getByRole('button', { name: 'I changed my mind' }));
    await u.click(screen.getByRole('button', { name: 'Cancel the order' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A shop has already packed this order.');
  });

  it('isn’t offered once a shop has packed', async () => {
    order = { ...ORDER, canCancel: false };
    open();
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  it('says when a shop cancelled its part', async () => {
    order = { ...ORDER, cancellations: [{ by: 'merchant', reason: 'out_of_stock', at: '2026-09-30T22:00:00Z', whole: false, merchantName: 'Bridgeland Butcher' }] };
    open();
    expect(await screen.findByText('Bridgeland Butcher cancelled its part of the order. That part goes back to your card.')).toBeInTheDocument();
  });
});

describe('A missed delivery (S-151)', () => {
  it('says why and lets the customer choose re-delivery', async () => {
    order = FAILED;
    post = c => (c.url === '/api/v1/me/orders/ORD1/delivery-failure'
      ? { body: { ...FAILED, deliveryFailure: { ...FAILED.deliveryFailure, choice: 'redeliver', chosenAt: '2026-10-01T02:00:00Z' } } } : undefined);
    const calls = open();
    expect(await screen.findByRole('heading', { level: 1, name: 'We couldn’t deliver your order.' })).toBeInTheDocument();
    const card = screen.getByRole('region', { name: 'What would you like us to do?' });
    expect(card).toHaveTextContent('The courier came by but nobody answered.');
    expect(card).toHaveTextContent('A refund keeps $2.50 of the delivery fee for the missed trip.');
    await userEvent.setup({ delay: null }).click(within(card).getByRole('button', { name: 'Deliver it on the next run' }));
    expect(await screen.findByText('It goes out again on the next run.')).toBeInTheDocument();
    expect(calls.find(c => c.url.endsWith('/delivery-failure'))?.body).toEqual({ choice: 'redeliver' });
  });

  it('or a refund', async () => {
    order = FAILED;
    post = () => ({ body: {
      ...FAILED, state: 'cancelled', deliveryFailure: { ...FAILED.deliveryFailure, choice: 'refund' },
      cancellations: [{ by: 'customer', reason: 'delivery_failed', at: '2026-10-01T02:00:00Z', whole: true, merchantName: null }],
    } });
    const calls = open();
    await userEvent.setup({ delay: null }).click(await screen.findByRole('button', { name: 'Refund me' }));
    expect(await screen.findByText('You chose a refund after the missed delivery. It goes back to your card.')).toBeInTheDocument();
    expect(calls.find(c => c.url.endsWith('/delivery-failure'))?.body).toEqual({ choice: 'refund' });
  });

  it('is in French', async () => {
    order = FAILED;
    open('fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Nous n’avons pas pu livrer votre commande.' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'La livrer à la prochaine tournée' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Me rembourser' })).toBeInTheDocument();
  });
});

describe('Returns (S-151)', () => {
  it('checks the form, then sends the lines, the reason and the photo', async () => {
    order = DELIVERED;
    post = c => (c.url === '/api/v1/me/orders/ORD1/returns' ? { body: { ...RETURN, state: 'requested', canWithdraw: true, canEscalate: false } } : undefined);
    const calls = open();
    const u = userEvent.setup({ delay: null, applyAccept: false });
    await u.click(await screen.findByRole('button', { name: 'Return items' }));
    await u.click(screen.getByRole('button', { name: 'Send the return request' }));
    expect(screen.getByText('Choose what you’re returning.')).toBeInTheDocument();
    expect(screen.getByText('Add a photo of the item.')).toBeInTheDocument();
    // one shop at a time
    await u.click(screen.getByRole('checkbox', { name: /Sourdough ×2/ }));
    await u.click(screen.getByRole('checkbox', { name: /Brisket/ }));
    await u.click(screen.getByRole('button', { name: 'It arrived damaged' }));
    await u.click(screen.getByRole('button', { name: 'Send the return request' }));
    expect(screen.getByText('Return items from one shop at a time.')).toBeInTheDocument();
    await u.click(screen.getByRole('checkbox', { name: /Brisket/ }));
    // only images
    const input = screen.getByLabelText('A photo of the item');
    await u.upload(input, new File(['x'], 'r.pdf', { type: 'application/pdf' }));
    expect(screen.getByText('Upload a JPG, PNG or WebP image under 5 MB.')).toBeInTheDocument();
    await u.upload(input, new File(['jpeg'], 'r.jpg', { type: 'image/jpeg' }));
    await u.click(screen.getByRole('button', { name: 'Send the return request' }));
    await vi.waitFor(() => expect(calls.some(c => c.url.endsWith('/returns'))).toBe(true));
    const form = calls.find(c => c.url.endsWith('/returns'))!.body as FormData;
    expect(form.getAll('lineIds')).toEqual(['L1']);
    expect(form.get('reason')).toBe('damaged');
    expect((form.get('photo') as File).name).toBe('r.jpg');
  });

  it('shows a decline with the shop’s reason and lets the customer ask an agent', async () => {
    order = { ...DELIVERED, returnable: [], returns: [RETURN] };
    post = c => (c.url === '/api/v1/me/returns/RET1/escalate' ? { body: { ...RETURN, state: 'escalated', canEscalate: false } } : undefined);
    const calls = open();
    const returns = await screen.findByRole('region', { name: 'Returns' });
    expect(returns).toHaveTextContent('RT-1001 · Sourdough ×2');
    expect(returns).toHaveTextContent('Glenmore Bakery declined it');
    expect(returns).toHaveTextContent('The shop said: “It was used”');
    const u = userEvent.setup({ delay: null });
    await u.click(within(returns).getByRole('button', { name: 'Ask a Northline agent' }));
    await u.click(within(returns).getByRole('button', { name: 'Send to an agent' }));
    expect(within(returns).getByRole('alert')).toHaveTextContent('Tell the agent why the return should be accepted.');
    await u.type(within(returns).getByLabelText('Tell the agent why it should be accepted'), 'It arrived torn');
    await u.click(within(returns).getByRole('button', { name: 'Send to an agent' }));
    await vi.waitFor(() => expect(calls.find(c => c.url.endsWith('/escalate'))?.body).toEqual({ statement: 'It arrived torn' }));
  });

  it('is in French', async () => {
    order = { ...DELIVERED, returns: [{ ...RETURN, state: 'refunded', canEscalate: false }] };
    open('fr');
    const returns = await screen.findByRole('region', { name: 'Retours' });
    expect(returns).toHaveTextContent(/Remboursé 12,00\s\$ plus taxes/);
    expect(within(returns).getByRole('button', { name: 'Retourner des articles' })).toBeInTheDocument();
  });
});
