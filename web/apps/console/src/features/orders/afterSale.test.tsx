import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import type { Monitor } from './api';

/** S-151: failed deliveries in the orders monitor, an order's after-sale on its page (S-177), and staff cancelling. */
const AS_OF = '2026-09-08T18:00:00Z';
const row = (o: Partial<Monitor['items'][number]>): Monitor['items'][number] => ({
  id: 'o1', ref: 'NL-48188', kind: 'order', type: 'goods', customer: 'P. Nguyen', sellers: ['Prairie Wrench'], amountCents: 1995, state: 'delivery_failed',
  status: 'delivery_failed', attention: true, at: '2026-09-08T12:00:00Z', since: null, ...o,
});
const monitor = (items = [row({})]): Monitor => ({ asOf: AS_OF, week: 120, counts: { attention: 1, live: 4, escrow: 0, late: 0, failed: 1, all: 50 }, items, truncated: false });
const AFTER_SALE = {
  orderId: 'o1', state: 'delivery_failed',
  cancellations: [{ id: 'c1', merchantId: 'm2', merchantName: 'Bridgeland Butcher', by: 'merchant', actorId: 'u9', reason: 'out_of_stock', note: null, restock: true, whole: false, lines: 1, at: '2026-09-08T13:00:00Z' }],
  deliveryFailure: { reason: 'absent', failedAt: '2026-09-08T17:00:00Z', decideBy: '2026-09-10T17:00:00Z', attempts: 1, choice: null, chosenAt: null, feeCents: 0 },
  returns: [{ id: 'r1', ref: 'RT-1001', merchantName: 'Prairie Wrench', state: 'requested', amountCents: 1995 }],
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.url.includes('/api/v1/console/orders/o1/after-sale')) return { body: AFTER_SALE };
    if (c.url.includes('/api/v1/console/fulfilment/orders/o1')) return { status: 404, body: { detail: 'no delivery' } };
    if (c.url.includes('/api/v1/console/orders?')) return { body: monitor() };
    return undefined;
  });
}

describe('orders monitor · after-sale (S-151)', () => {
  it('has a failed-deliveries view and says why the row needs attention', async () => {
    const calls = api(['dispatch']);
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/orders');
    const chip = await screen.findByRole('tab', { name: /^Delivery failed/ });
    expect(screen.getAllByText('The courier couldn’t hand it over · customer choosing re-delivery or a refund').length).toBeGreaterThan(0);
    await user.click(chip);
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/orders?view=failed'))).toBe(true));
    expect(router.state.location.search).toMatchObject({ view: 'failed' });
  });

  it('shows the order’s cancellations, failed delivery and returns; dispatch can’t cancel', async () => {
    api(['dispatch']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/orders/NL-48188');
    await user.click(await screen.findByRole('tab', { name: 'Cancellations and returns' }));
    const drawer = screen.getByRole('tabpanel');
    await within(drawer).findByText(/RT-1001/);
    const text = drawer.textContent ?? '';
    expect(text).toMatch(/cancelled by the shop \(Bridgeland Butcher\) · out of stock/);
    expect(text).toMatch(/nobody answered · attempt 1/);
    expect(text).toMatch(/Waiting for the customer until/);
    expect(text).toMatch(/RT-1001 · Prairie Wrench · awaiting the shop · \$19\.95/);
    expect((screen.getByRole('button', { name: 'Cancel order…' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Your role can’t change orders here.')).toBeTruthy();
  });

  it('support cancels with a reason and restock; the api’s refusal is shown', async () => {
    let refuse = true;
    const calls = api(['support'], c => {
      if (c.method === 'POST' && c.url.endsWith('/api/v1/console/orders/o1/cancel')) {
        return refuse ? { status: 409, body: { code: 'too_late', detail: 'This order was delivered; refund it from the refund queue.' } }
          : { body: { cancellationId: 'c2', orderId: 'o1', state: 'cancelled', whole: true, lineIds: ['l1'] } };
      }
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/orders/NL-48188');
    const open = await screen.findByRole('button', { name: 'Cancel order…' });
    expect(open.className).toContain('btn-destructive');
    await waitFor(() => expect((open as HTMLButtonElement).disabled).toBe(false));
    await user.click(open);
    const drawer = await screen.findByRole('dialog', { name: 'Cancel this order' });
    await user.click(within(drawer).getByRole('button', { name: 'Cancel and refund' }));
    expect(within(drawer).getByRole('alert').textContent).toBe('Choose a reason.');
    await user.click(within(drawer).getByRole('radio', { name: 'Delivery issue' }));
    await user.click(within(drawer).getByRole('checkbox', { name: 'Put the units back on sale' }));
    await user.click(within(drawer).getByRole('button', { name: 'Cancel and refund' }));
    expect(await within(drawer).findByText('This order was delivered; refund it from the refund queue.')).toBeTruthy();
    refuse = false;
    await user.click(within(drawer).getByRole('button', { name: 'Cancel and refund' }));
    expect(await within(drawer).findByText('Cancelled. The customer is refunded.')).toBeTruthy();
    expect(calls.filter(c => c.url.endsWith('/o1/cancel')).at(-1)?.body).toEqual({ reason: 'delivery_issue', restock: false });
  });

  it('is in French', async () => {
    api(['support']);
    const user = userEvent.setup({ delay: null });
    const first = renderConsole('/orders', { locale: 'fr' });
    expect(await screen.findByRole('tab', { name: /^Livraison manquée/ })).toBeTruthy();
    first.unmount();
    renderConsole('/orders/NL-48188', { locale: 'fr' });
    expect(await screen.findByRole('tab', { name: 'Annulations et retours' })).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Annuler la commande…' })).toBeTruthy();
    void user;
  });
});
