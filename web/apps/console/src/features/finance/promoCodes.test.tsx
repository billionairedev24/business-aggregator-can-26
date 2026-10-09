import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** Mobile gaps part 2: promo codes on the Finance screen (finance and admins make and switch them). */
const CODE = {
  id: 'PC1', code: 'FALL10', description: null, kind: 'percent', percent: 10, amountCents: null, maxDiscountCents: null, minSpendCents: 2500,
  startsAt: '2026-10-01T06:00:00Z', endsAt: '2026-10-31T06:00:00Z', perCustomerLimit: 1, totalLimit: 500, fundedBy: 'merchant', merchantId: 'M1',
  merchantName: 'Glenmore Bakery', appliesTo: ['food', 'goods'], active: true, state: 'live', redeemed: 12, discountCents: 4810,
};
const FINANCE = {
  asOf: '2026-09-08T18:00:00Z', timeZone: 'America/Regina', escrowHeldCents: 0, escrowItems: 0, payoutsInFlightCents: 0, payoutsInFlightSellers: 0,
  nextPayoutArrival: null, revenueWeekCents: 0, mix: { takeCents: 0, deliveryCents: 0, adjustmentsCents: 0, plusCents: null, rewardsCents: null },
  tiers: [], tax: { period: '2026-Q3', platformFeeCents: 0, facilitatorCents: 0, nextFiling: '2026-10-31' },
};
const base = (c: Call) => (c.url.endsWith('/api/v1/console/finance') ? { body: FINANCE }
  : c.method === 'GET' && c.url.endsWith('/api/v1/console/payments/reconciliation') ? { body: { items: [] } }
    : c.method === 'GET' && c.url.endsWith('/api/v1/console/support/refund-requests') ? { body: { items: [] } } : undefined);
function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => extra?.(c) ?? base(c) ?? (c.method === 'GET' && c.url.endsWith('/api/v1/console/promotions/codes') ? { body: { items: [CODE] } } : undefined));
}

describe('promo codes (Finance)', () => {
  it('lists the codes with their use, and finance makes a new one', async () => {
    const calls = api(['finance'], c => (c.method === 'POST' && c.url.endsWith('/promotions/codes')
      ? { status: 422, body: { errors: [{ field: 'endsAt', rule: 'range', message: 'End the code after it starts.' }] } } : undefined));
    renderConsole('/finance');
    expect(await screen.findByText('FALL10')).toBeTruthy();
    expect(screen.getByText(/Funded by Glenmore Bakery/)).toBeTruthy();
    expect(screen.getByText(/12 uses · \$48\.10 given/)).toBeTruthy();
    await expectNoAxeViolations(document.body);
    const u = userEvent.setup();
    await u.click(screen.getByRole('button', { name: 'New promo code' }));
    await u.type(screen.getByLabelText('Code'), 'WINTER5');
    await u.selectOptions(screen.getByLabelText('Discount'), 'amount');
    await u.type(screen.getByLabelText('Amount off ($)'), '5');
    await u.click(screen.getByRole('button', { name: 'Create code' }));
    expect(await screen.findByText('End the code after it starts.')).toBeTruthy();
    const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/promotions/codes'))!;
    expect(post.body).toMatchObject({ code: 'WINTER5', kind: 'amount', amountCents: 500, fundedBy: 'northline', appliesTo: ['goods', 'food', 'service'] });
  });

  it('switches a code off', async () => {
    const calls = api(['admin'], c => (c.method === 'PATCH' ? { body: { ...CODE, active: false, state: 'off' } } : undefined));
    renderConsole('/finance');
    await userEvent.setup().click(await screen.findByRole('switch', { name: 'FALL10 on' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PATCH')?.body).toEqual({ active: false }));
  });
});
