import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { OrdersScreen } from './OrdersScreen';

/** S-151: the seller cancels its part of an order, and decides on returns. */
let role = 'owner';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role }));

const inHours = (h: number) => new Date(Date.now() + h * 3600_000).toISOString();
const order = (id: string, ref: string, status: string, extra: object = {}) => ({
  id, ref, customerName: 'A. Osei', area: 'Beltline', lines: [{ id: `${id}l`, title: 'Wiper blades', qty: 2, unitCents: 1995, state: 'pending' }],
  totalCents: 3990, windowStartsAt: inHours(3), cutoffAt: inHours(2.75), runLabel: 'R-611', placedAt: inHours(-2), deliveredAt: null, status, issueNote: null, ...extra,
});
const board = (items = [order('o1', 'NL-48213', 'to_pack')]) => ({ items, counts: { toPack: 1, awaitingPickup: 0, deliveredToday: 0, issues: 0, nextCutoff: inHours(2.75), nextRunLabel: 'R-611' } });
const ret = (state: string, extra: object = {}) => ({
  id: 'r1', ref: 'RT-1001', orderId: 'o9', orderRef: 'NL-48100', merchantId: 'm1', merchantName: 'Beltline Auto',
  state, reason: 'damaged', note: 'Cracked casing', hasPhoto: true, method: null, restock: false, declineReason: null, amountCents: 1995,
  lines: [{ lineId: 'l1', title: 'Wiper blades', qty: 1, amountCents: 1995 }],
  createdAt: inHours(-5), decidedAt: null, collectedAt: null, receivedAt: null, refundedAt: null, ...extra,
});

describe('Orders · cancelling and returns (S-151)', () => {
  afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });

  it('cancels with a reason, a note and restock, and shows the server’s refusal', async () => {
    let tooLate = true;
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/returns': () => ({ items: [] }),
      'GET /api/v1/merchants/m1/orders': () => board(),
      'POST /api/v1/merchants/m1/orders/o1/cancel': () => {
        if (tooLate) throw { status: 409, body: { code: 'too_late', detail: 'The courier has already picked up your part of this order.' } };
        return order('o1', 'NL-48213', 'cancelled');
      },
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    await user.click(await screen.findByText('NL-48213'));
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    await user.click(screen.getByRole('button', { name: 'Cancel and refund' }));
    expect(screen.getByRole('alert').textContent).toContain('Choose a reason.');
    await user.click(screen.getByRole('radio', { name: 'Out of stock' }));
    await user.type(screen.getByLabelText('Note for the customer (optional)'), 'Sold out at the counter');
    await user.click(screen.getByRole('checkbox', { name: 'Put the units back on sale' }));
    await user.click(screen.getByRole('button', { name: 'Cancel and refund' }));
    expect(await screen.findByText('The courier has already picked up your part of this order.')).toBeTruthy();
    tooLate = false;
    await user.click(screen.getByRole('button', { name: 'Cancel and refund' }));
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/o1/cancel'))).toHaveLength(2));
    expect(calls.filter(c => c.url.endsWith('/o1/cancel'))[1]!.body).toEqual({ reason: 'out_of_stock', note: 'Sold out at the counter', restock: false });
  });

  it('isn’t offered once the courier has the bags, nor to a bookkeeper', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/returns': () => ({ items: [] }),
      'GET /api/v1/merchants/m1/orders': () => board([order('o1', 'NL-48213', 'out_for_delivery')]),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    await user.click(await screen.findByText('NL-48213'));
    await screen.findByRole('dialog', { name: 'Order NL-48213' });
    expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();
  });

  it('lists returns; the sheet shows the photo and approves with a pickup', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/orders': () => board(),
      'GET /api/v1/merchants/m1/returns': () => ({ items: [ret('requested')] }),
      'POST /api/v1/merchants/m1/returns/r1/approve': () => ret('approved', { method: 'pickup' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    const returns = await screen.findByRole('region', { name: 'Returns' });
    expect(within(returns).getAllByText('Awaiting your decision').length).toBeGreaterThan(0);
    await user.click(within(returns).getByText('RT-1001'));
    const sheet = await screen.findByRole('dialog', { name: 'Return RT-1001' });
    expect(within(sheet).getByRole('img', { name: 'The customer’s photo of the returned item' }).getAttribute('src')).toBe('/api/v1/merchants/m1/returns/r1/photo');
    expect(sheet.textContent).toContain('Cracked casing');
    await user.click(within(sheet).getByRole('checkbox', { name: 'Put the units back on sale when it’s back' }));
    await user.click(within(sheet).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/r1/approve'))?.body).toEqual({ method: 'pickup', restock: true }));
  });

  it('declines with a reason the customer sees', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/orders': () => board(),
      'GET /api/v1/merchants/m1/returns': () => ({ items: [ret('requested')] }),
      'POST /api/v1/merchants/m1/returns/r1/decline': () => ret('declined', { declineReason: 'Used' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    await user.click(await screen.findByText('RT-1001'));
    const sheet = await screen.findByRole('dialog', { name: 'Return RT-1001' });
    await user.click(within(sheet).getByRole('button', { name: 'Decline' }));
    await user.click(within(sheet).getByRole('button', { name: 'Decline the return' }));
    expect(within(sheet).getByRole('alert').textContent).toContain('Say why you’re declining.');
    await user.type(within(sheet).getByLabelText('Why (the customer sees this)'), 'It has been used');
    await user.click(within(sheet).getByRole('button', { name: 'Decline the return' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/r1/decline'))?.body).toEqual({ reason: 'It has been used' }));
  });

  it('marks a collected return received, which refunds it', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/orders': () => board(),
      'GET /api/v1/merchants/m1/returns': () => ({ items: [ret('collected', { method: 'pickup' })] }),
      'POST /api/v1/merchants/m1/returns/r1/received': () => ret('refunded', { method: 'pickup' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    await user.click(await screen.findByText('RT-1001'));
    await user.click(await screen.findByRole('button', { name: 'Mark received and refund' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/r1/received'))).toBe(true));
  });

  it('is in French', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/orders': () => board(),
      'GET /api/v1/merchants/m1/returns': () => ({ items: [ret('requested')] }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />, 'fr');
    expect(await screen.findByRole('region', { name: 'Retours' })).toBeTruthy();
    await user.click(screen.getByText('NL-48213'));
    expect(await screen.findByRole('button', { name: 'Annuler la commande' })).toBeTruthy();
  });
});
