import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, screenReady } from '../../test/render';
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
]): Monitor => ({ asOf: AS_OF, week: 6812, counts: { attention: 14, live: 431, escrow: 6, late: 9, failed: 2, all: 2000 }, items, truncated: false });

describe('orders & bookings (S-81, design 03)', () => {
  it('shows the week, what needs attention and the design’s chips', async () => {
    const calls = staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined));
    renderConsole('/orders');
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders & bookings' })).toBeTruthy();
    expect(await screen.findByText('All markets · 6,812 this week · 14 need attention')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    const kpis = within(screen.getByRole('list', { name: 'Right now' }));
    for (const [label, n] of [['Needs attention', '14'], ['Live', '431'], ['Escrow > 48 h', '6'], ['Late', '9']]) expect(kpis.getByRole('button', { name: `${label} ${n}` })).toBeTruthy();
    const tabs = within(screen.getByRole('tablist', { name: 'Show' }));
    expect(tabs.getAllByRole('tab').map(x => x.textContent)).toEqual(['Needs attention14', 'Live431', 'Escrow > 48 h6', 'Late9', 'Delivery failed2', 'All']);
    expect(tabs.getByRole('tab', { name: /Needs attention/ }).getAttribute('aria-selected')).toBe('true');
    const table = document.querySelector('.nl-dt') as HTMLElement;
    for (const header of ['Customer → seller', 'Issue']) expect(within(table).getAllByText(header).length).toBeGreaterThan(0);
    expect(within(table).getByText('P. Nguyen → Prairie Wrench')).toBeTruthy();
    expect(within(table).getByText('R. Diaz → 3 shops')).toBeTruthy();
    expect(within(table).getByText('Provider 34 min late')).toBeTruthy();
    expect(within(table).getByText('Courier run has a stop more than 10 min overdue')).toBeTruthy();
    expect(within(table).getByText('Customer hasn’t signed off · job ended 3.0 d ago')).toBeTruthy();
    expect(within(table).getByText('Escrow > 48 h', { selector: '.nl-status' }).className).toContain('nl-status-warning');
    expect(within(table).getByText('Stuck', { selector: '.nl-status' }).className).toContain('nl-status-danger');
    expect(within(table).getByText('$640.00')).toBeTruthy();
    expect(calls.find(c => c.url.includes('/api/v1/console/orders'))?.url).toContain('view=attention');
    // nothing to change here: view only
    expect(screen.getByText(/View only · Ops dispatcher/)).toBeTruthy();
  });

  it('switches views, filters the type and takes a reference from the global search', async () => {
    const calls = staffApi(['support'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/orders');
    await screenReady();
    await user.click(screen.getByRole('tab', { name: /^Late/ }));
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/orders?view=late'))).toBe(true));
    expect(router.state.location.search).toMatchObject({ view: 'late' });
    await user.click(screen.getByRole('radio', { name: 'Services' }));
    await waitFor(() => expect(screen.queryByText('P. Nguyen → Prairie Wrench')).toBeNull());
    expect(screen.getByText('L. Cardinal → Bow River Mechanics')).toBeTruthy();
    const box = screen.getByRole('combobox', { name: 'Search the console' });
    await user.type(box, 'BK-77');
    await user.click(screen.getByRole('option', { name: 'Orders and bookings with reference “BK-77”' }));
    await waitFor(() => expect(calls.some(c => c.url.includes('view=all&q=BK-77'))).toBe(true));
    expect(calls.filter(c => c.url.includes('/console/orders')).at(-1)?.headers['X-Console-Role']).toBe('support');
  });

  it('opens an order’s page with its status, facts and delivery', async () => {
    staffApi(['admin'], c => {
      if (c.url.includes('/api/v1/console/fulfilment/orders/o2')) {
        return { body: { orderId: 'o2', orderRef: 'NL-48102', kind: 'pooled', market: 'Calgary', state: 'picked_up', run: { id: 'r1', label: 'R-608', state: 'en_route', courier: { name: 'Sam T.' }, late: true },
          pickups: [{ merchantId: 'm1', name: 'Bridgeland Butcher', packedAt: '2026-09-08T23:40:00Z', pickedUpAt: null }], dropoffEta: '2026-09-09T00:30:00Z' } };
      }
      return c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined;
    });
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/orders');
    await user.click(await screen.findByText('R. Diaz → 3 shops'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/NL-48102'));
    expect(await screen.findByRole('heading', { level: 1, name: 'NL-48102' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    const drawer = document.body;
    expect(within(drawer).getByText('Stuck').className).toContain('nl-status-danger');
    expect(await within(drawer).findByText('R-608')).toBeTruthy();
    expect(within(drawer).getByText('Sam T.')).toBeTruthy();
    expect(within(drawer).getByText('late')).toBeTruthy();
    expect(within(drawer).getByText('Bridgeland Butcher')).toBeTruthy();
    expect(within(drawer).getByText('Courier run has a stop more than 10 min overdue')).toBeTruthy(); // the sheet's "Needs attention"
    expect(within(drawer).getByText('Customer → seller', { selector: 'dt' }).nextElementSibling?.textContent).toBe('R. Diaz → A, B, C');
    expect(within(drawer).getByText('Amount', { selector: 'dt' }).nextElementSibling?.textContent).toBe('$88.40');
    expect(within(drawer).getAllByText('Market', { selector: 'dt' })[0]!.nextElementSibling?.textContent).toBe('Calgary');
  });

  it('opens a view from its KPI card', async () => {
    const calls = staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor() } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/orders');
    await screenReady();
    await user.click(within(screen.getByRole('list', { name: 'Right now' })).getByRole('button', { name: /^Escrow > 48 h/ }));
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/orders?view=escrow'))).toBe(true));
    expect(router.state.location.search).toMatchObject({ view: 'escrow' });
  });

  it('shows an empty list, an error with retry, and French', async () => {
    staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/orders') ? { body: monitor([]) } : undefined));
    const first = renderConsole('/orders', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Commandes et réservations' })).toBeTruthy();
    expect(await screen.findByText(/Tous les marchés · 6\s812 cette semaine · 14 à traiter/)).toBeTruthy();
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
