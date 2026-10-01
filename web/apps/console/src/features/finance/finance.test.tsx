import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import type { Day, Finance } from './api';

const FINANCE: Finance = {
  asOf: '2026-09-08T18:00:00Z', timeZone: 'America/Edmonton', escrowHeldCents: 41_822_000, escrowItems: 3104, payoutsInFlightCents: 18_640_000, payoutsInFlightSellers: 1140,
  nextPayoutArrival: null, revenueWeekCents: 2_410_000, mix: { takeCents: 1_840_000, deliveryCents: 310_000, adjustmentsCents: 0, plusCents: null, rewardsCents: null },
  tiers: [{ tier: 'master', sellers: 142, rateBps: 900, gmvShare: 0.41 }, { tier: 'trusted', sellers: 486, rateBps: 1200, gmvShare: 0.44 }, { tier: 'registered', sellers: 576, rateBps: 1500, gmvShare: 0.15 }],
  tax: { period: '2026-Q3', platformFeeCents: 1_488_000, facilitatorCents: 6_121_000, nextFiling: '2026-10-31' },
};
const day = (o: Partial<Day>): Day => ({ day: '2026-09-07', stripeCents: 3_120_410, ledgerCents: 3_120_410, feeCents: 0, items: 120, mismatches: 0, status: 'matched',
  computedAt: '2026-09-08T10:41:00Z', resolvedNote: null, resolvedBy: null, resolvedAt: null, varianceCents: 0, ...o });
const DAYS = [day({}), day({ day: '2026-09-06', stripeCents: 2_891_155, ledgerCents: 2_891_155 }),
  day({ day: '2026-09-05', stripeCents: 3_340_200, ledgerCents: 3_338_950, varianceCents: 1_250, status: 'mismatch', mismatches: 1 })];
const DETAIL = { day: DAYS[2], items: [
  { kind: 'refund', stripeId: 're_1', stripeCents: -1_250, ledgerRefType: null, ledgerRefId: null, ledgerCents: null, status: 'missing_in_ledger' },
  { kind: 'charge', stripeId: 'ch_1', stripeCents: 9_345, ledgerRefType: 'escrow', ledgerRefId: 'e1', ledgerCents: 9_345, status: 'matched' },
] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.url.endsWith('/api/v1/console/finance')) return { body: FINANCE };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/payments/reconciliation')) return { body: { items: DAYS } };
    if (c.method === 'GET' && c.url.endsWith('/reconciliation/2026-09-05')) return { body: DETAIL };
    if (c.method === 'POST' && c.url.includes('/reconciliation/')) return { body: DAYS[2] };
    if (c.method === 'POST' && c.url.includes('/tax-reconciliations')) return { body: { period: '2026-Q3', reported: 0, checked: 412, mismatched: 1, rows: 30, stillPending: 0 } };
    return undefined;
  });
}
const card = (text: string) => screen.getAllByText(text).map(e => e.closest('tr, .nl-dt-card') as HTMLElement | null).find(Boolean) as HTMLElement;

describe('finance (S-85, design 03)', () => {
  it('shows escrow, payouts, revenue, the mix, take by tier, the daily reconciliation and tax', async () => {
    api(['finance']);
    renderConsole('/finance');
    expect(await screen.findByRole('heading', { level: 1, name: 'Escrow, payouts and reconciliation' })).toBeTruthy();
    expect(screen.getByText('escrow held · 3,104 items').previousElementSibling?.textContent).toBe('$418,220');
    expect(screen.getByText('payouts in flight · 1,140 sellers').previousElementSibling?.textContent).toBe('$186,400');
    expect(screen.getByText('net revenue · week').previousElementSibling?.textContent).toBe('$24,100');
    expect(screen.getByText('Stripe ↔ ledger variance · Sep 7').previousElementSibling?.textContent).toBe('$0.00');
    expect(screen.getByText('Take rate on GMV')).toBeTruthy();
    expect(screen.getByText('Provider-funded rewards (pass-through)')).toBeTruthy();
    expect(within(card('Master')).getByText('9%')).toBeTruthy();
    expect(within(card('Master')).getByText('41%')).toBeTruthy();
    expect(within(card('Sep 5')).getByText('Mismatch')).toBeTruthy();
    expect(within(card('Sep 5')).getByText('$12.50')).toBeTruthy();
    expect(within(card('Sep 7')).getByText('Matched')).toBeTruthy();
    expect(screen.getByText('GST collected on platform fees · Q3').nextElementSibling?.textContent).toBe('$14,880');
    expect(screen.getByText('Marketplace facilitator GST on goods · Q3').nextElementSibling?.textContent).toBe('$61,210');
    expect(screen.getByText('Next filing').nextElementSibling?.textContent).toBe('Oct 31');
    expect(screen.getByRole('link', { name: 'Export reconciliation' }).getAttribute('href')).toBe('/api/v1/console/payments/reconciliation/export?from=2026-09-05&to=2026-09-07');
    expect(screen.getByRole('link', { name: 'Export to accounting' }).getAttribute('href')).toMatch(/^\/api\/v1\/console\/payments\/reconciliation\/ledger-export\?from=2026-07-01&to=\d{4}-\d{2}-\d{2}$/);
  });

  it('drills into a day that doesn’t match and resolves it with a note', async () => {
    const calls = api(['finance']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/finance');
    await user.click(await screen.findByText('Sep 5'));
    const drawer = await screen.findByRole('dialog', { name: 'Reconciliation · Sep 5' });
    expect(await within(drawer).findByText('Not in the ledger')).toBeTruthy();
    expect(within(drawer).getByText('re_1 · -$12.50')).toBeTruthy();
    expect(within(drawer).getByText('escrow e1 · $93.45')).toBeTruthy();
    await user.type(within(drawer).getByRole('textbox', { name: 'How was it resolved?' }), 'Late refund');
    await user.click(within(drawer).getByRole('button', { name: 'Mark resolved' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/reconciliation/2026-09-05/resolve'))?.body).toEqual({ note: 'Late refund' }));
    await user.click(within(drawer).getByRole('button', { name: 'Run again' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/reconciliation/run'))?.body).toEqual({ day: '2026-09-05' }));
  });

  it('reconciles a day and Stripe Tax on demand', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/finance');
    await screen.findByRole('heading', { level: 1 });
    const input = screen.getByLabelText('Day') as HTMLInputElement;
    await user.clear(input);
    await user.type(input, '2026-09-01');
    await user.click(screen.getByRole('button', { name: 'Reconcile' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/reconciliation/run'))?.body).toEqual({ day: '2026-09-01' }));
    await user.click(screen.getByRole('button', { name: 'Reconcile Stripe Tax' }));
    expect(await screen.findByText('412 checked · 1 differ · 0 still pending')).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/tax-reconciliations'))?.body).toEqual({ period: '2026-Q3' });
  });

  it('is denied to roles that don’t open finance, and speaks French', async () => {
    staffApi(['trust_safety'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    const first = renderConsole('/finance');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
    first.unmount();
    api(['finance']);
    renderConsole('/finance', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Fiducie, versements et rapprochement' })).toBeTruthy();
    expect(screen.getByText('Commission sur la VMB')).toBeTruthy();
  });
});
