import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi } from '../../test/render';
import type { Monitor } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AS_OF = '2026-09-08T18:00:00Z';
const row = (o: Partial<Monitor['items'][number]>): Monitor['items'][number] => ({
  id: 'o1', ref: 'NL-48188', kind: 'order', type: 'goods', customer: 'P. Nguyen', sellers: ['Prairie Wrench'], amountCents: 1995, state: 'delivered',
  status: 'issue', attention: true, at: '2026-09-08T12:00:00Z', since: null, ...o,
});
const monitor = (items = [
  row({}),
  row({ id: 'b1', ref: 'BK-7690', kind: 'booking', type: 'service', customer: 'L. Cardinal', sellers: ['Bow River Mechanics'], amountCents: 24000, state: 'confirmed', status: 'late', since: '2026-09-08T17:26:00Z' }),
  row({ id: 'o2', ref: 'NL-48102', customer: 'R. Diaz', sellers: ['A', 'B', 'C'], amountCents: 8840, state: 'picked_up', status: 'stuck' }),
  row({ id: 'b2', ref: 'BK-7655', kind: 'booking', type: 'service', customer: 'K. Ng', sellers: ['Sable & Soda'], amountCents: 64000, state: 'completed', status: 'escrow_48h', since: '2026-09-05T18:00:00Z' }),
]): Monitor => ({ asOf: AS_OF, week: 6812, counts: { attention: 14, live: 431, escrow: 6, late: 9, all: 2000 }, items, truncated: false });

describe('orders & bookings (S-81, design 03)', () => {
  it('shows the week, what needs attention and the design’s chips', async () => {
    const calls = staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined));
    renderConsole('/orders');
    expect(await screen.findByRole('heading', { level: 1, name: '6,812 this week · 14 need attention' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Orders & bookings', { selector: '.nl-or-kicker' })).toBeTruthy();
    for (const chip of ['Needs attention · 14', 'Live · 431', 'Escrow > 48 h · 6', 'Late · 9', 'All']) expect(screen.getByRole('button', { name: chip })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Needs attention · 14' }).getAttribute('aria-pressed')).toBe('true');
    const table = document.querySelector('.nl-dt') as HTMLElement;
    for (const header of ['Customer → seller', 'Issue']) expect(within(table).getAllByText(header).length).toBeGreaterThan(0);
    expect(within(table).getByText('P. Nguyen → Prairie Wrench')).toBeTruthy();
    expect(within(table).getByText('R. Diaz → 3 shops')).toBeTruthy();
    expect(within(table).getByText('Provider 34 min late')).toBeTruthy();
    expect(within(table).getByText('Courier run has a stop more than 10 min overdue')).toBeTruthy();
    expect(within(table).getByText('Customer hasn’t signed off · job ended 3.0 d ago')).toBeTruthy();
    expect(within(table).getByText('Escrow > 48 h')).toBeTruthy();
    expect(within(table).getByText('$640.00')).toBeTruthy();
    expect(calls.find(c => c.url.includes('/api/v1/console/orders'))?.url).toContain('view=attention');
    // nothing to change here: view only
    expect(screen.getByText(/View only · Ops dispatcher/)).toBeTruthy();
  });

  it('switches views and searches a reference', async () => {
    const calls = staffApi(['support'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/orders');
    await screen.findByRole('heading', { level: 1 });
    await user.click(screen.getByRole('button', { name: 'Late · 9' }));
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/orders?view=late'))).toBe(true));
    expect(router.state.location.search).toMatchObject({ view: 'late' });
    await user.type(screen.getByRole('searchbox', { name: /Search a reference/ }), 'BK-77{Enter}');
    await waitFor(() => expect(calls.some(c => c.url.includes('view=late&q=BK-77'))).toBe(true));
    expect(calls.filter(c => c.url.includes('/console/orders')).at(-1)?.headers['X-Console-Role']).toBe('support');
  });

  it('opens an order’s delivery', async () => {
    staffApi(['admin'], c => {
      if (c.url.includes('/api/v1/console/fulfilment/orders/o2')) {
        return { body: { orderId: 'o2', orderRef: 'NL-48102', kind: 'pooled', market: 'Calgary', state: 'picked_up', run: { id: 'r1', label: 'R-608', state: 'en_route', courier: { name: 'Sam T.' }, late: true },
          pickups: [{ merchantId: 'm1', name: 'Bridgeland Butcher', packedAt: '2026-09-08T23:40:00Z', pickedUpAt: null }], dropoffEta: '2026-09-09T00:30:00Z' } };
      }
      return c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/orders');
    await user.click(await screen.findByText('R. Diaz → 3 shops'));
    const drawer = await screen.findByRole('dialog', { name: 'NL-48102 · Order' });
    expect(await within(drawer).findByText('R-608')).toBeTruthy();
    expect(within(drawer).getByText('Sam T.')).toBeTruthy();
    expect(within(drawer).getByText('late')).toBeTruthy();
    expect(within(drawer).getByText('Bridgeland Butcher')).toBeTruthy();
  });

  it('shows an empty list, an error with retry, and French', async () => {
    staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor([]) } : undefined));
    const first = renderConsole('/orders', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: /6\s812 cette semaine · 14 à traiter/ })).toBeTruthy();
    expect(screen.getByText('Rien pour l’instant.')).toBeTruthy();
    first.unmount();
    staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/orders') ? { status: 500, body: { detail: 'boom' } } : undefined));
    renderConsole('/orders');
    expect(await screen.findByText("Orders couldn't load. Try again.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('is refused to roles that don’t open it', async () => {
    staffApi(['finance'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/orders');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
  });
});
