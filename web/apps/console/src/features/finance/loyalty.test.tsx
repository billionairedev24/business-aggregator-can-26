import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, SESSION, staffApi, type Call } from '../../test/render';

/** S-152 on the Finance screen: Plus revenue, the points and rewards liabilities, and the points earning rules. */
const FINANCE = {
  asOf: '2026-09-08T18:00:00Z', timeZone: 'America/Edmonton', escrowHeldCents: 0, escrowItems: 0, payoutsInFlightCents: 0, payoutsInFlightSellers: 0,
  nextPayoutArrival: null, revenueWeekCents: 0, mix: { takeCents: 0, deliveryCents: 0, adjustmentsCents: 0, plusCents: 41_940, rewardsCents: 12_000 },
  tiers: [], tax: { period: '2026-Q3', platformFeeCents: 0, facilitatorCents: 0, nextFiling: '2026-10-31' },
  loyalty: { pointsOutstanding: 184_200, pointsOutstandingCents: 1_842, pointHolders: 61, rewardsFundedCents: 3_400, pointsRedeemedWeekCents: 950,
    plusMembers: 6, plusTrialing: 2, pendingReferrals: 3 },
};
const DEFAULT = { id: 'loyalty-default', regionId: null, regionName: null, pointsPerDollar: 2, kinds: ['goods', 'food', 'service'], expiryMonths: 18,
  referralPoints: 1000, referralMonthlyCap: 5, referralMinOrderCents: 2000, updatedAt: '2026-09-01T00:00:00Z' };
const PROVINCE = { ...DEFAULT, id: 'LR1', regionId: 'P1', regionName: 'Region One', pointsPerDollar: 3, kinds: ['food'], expiryMonths: null };
const REGIONS = [{ id: 'P1', kind: 'province', name: 'Region One', province: null }, { id: 'P2', kind: 'province', name: 'Region Two', province: null },
  { id: 'M1', kind: 'market', name: 'Town', province: 'P2' }];
const RULES = '/api/v1/console/loyalty/rules';

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.url.endsWith('/api/v1/console/finance')) return { body: FINANCE };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/payments/reconciliation')) return { body: { items: [] } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/support/refund-requests')) return { body: { items: [] } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/promotions/codes')) return { body: { items: [] } };
    if (c.method === 'GET' && c.url.endsWith(RULES)) return { body: { items: [DEFAULT, PROVINCE], regions: REGIONS } };
    return undefined;
  });
}

describe('loyalty on the Finance screen (S-152)', () => {
  it('shows Plus revenue and the liabilities instead of a dash', async () => {
    api(['finance']);
    renderConsole('/finance');
    const plus = await screen.findAllByText('Plus subscriptions');
    expect(plus.map(e => e.nextElementSibling?.textContent)).toContain('$419');
    expect(screen.getByText('6 households')).toBeTruthy();
    expect(screen.getByText('Points outstanding').nextElementSibling?.textContent).toBe('184,200 points · $18.42 off at checkout · 61 customers');
    expect(screen.getByText('Held from businesses’ rewards').nextElementSibling?.textContent).toBe('$34.00');
    expect(screen.getByText('Points taken off sales · week').nextElementSibling?.textContent).toBe('$9.50');
    expect(screen.getByText('Plus households').nextElementSibling?.textContent).toBe('6 · 2 in their trial');
    expect(screen.getByText('Referrals waiting').nextElementSibling?.textContent).toBe('3');
  });

  it('in French', async () => {
    api(['finance']);
    renderConsole('/finance', { locale: 'fr' });
    expect((await screen.findAllByText('Abonnements Plus')).length).toBeGreaterThan(0);
    expect(screen.getByText('6 foyers')).toBeTruthy();
    expect(screen.getByText('Points en circulation')).toBeTruthy();
    expect(await screen.findByText('Règles d’obtention des points')).toBeTruthy();
    expect(await screen.findByText('Par défaut (partout ailleurs)')).toBeTruthy();
  });

  it('lists the earning rules, and finance changes one (422 messages under the field)', async () => {
    const calls = api(['finance'], c => (c.method === 'PUT' && c.url.endsWith(`${RULES}/P1`)
      ? { status: 422, body: { errors: [{ field: 'pointsPerDollar', rule: 'range', message: 'Choose between 0 and 100 points per dollar.' }] } } : undefined));
    renderConsole('/finance');
    expect(await screen.findByText('Default (everywhere else)')).toBeTruthy();
    expect(screen.getByText('Region One')).toBeTruthy();
    expect(screen.getByText(/Expiry Never/)).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Edit — Region One' }));
    const ppd = screen.getByLabelText('Points per dollar');
    await userEvent.clear(ppd);
    await userEvent.type(ppd, '500');
    await userEvent.click(screen.getByRole('button', { name: 'Save rule' }));
    expect(await screen.findByText('Choose between 0 and 100 points per dollar.')).toBeTruthy();
    const put = calls.find(c => c.method === 'PUT')!;
    expect(put.body).toMatchObject({ pointsPerDollar: 500, kinds: ['food'], neverExpire: true, referralPoints: 1000, referralMinOrderCents: 2000 });
  });

  it('adds a rule for a province without one, and removes one', async () => {
    const calls = api(['finance'], c => (c.method === 'PUT' && c.url.endsWith(`${RULES}/P2`) ? { body: { ...PROVINCE, regionId: 'P2', regionName: 'Region Two' } }
      : c.method === 'DELETE' ? { status: 204 } : undefined));
    renderConsole('/finance');
    await userEvent.click(await screen.findByRole('button', { name: 'Add a rule for a province or market' }));
    const region = screen.getByLabelText('Province or market') as HTMLSelectElement;
    expect([...region.options].map(o => o.textContent)).toEqual(['Region Two', 'Town (P2)']);
    const expiry = screen.getByLabelText('Expire after (months)');
    await userEvent.clear(expiry);
    await userEvent.type(expiry, '12');
    await userEvent.click(screen.getByRole('button', { name: 'Save rule' }));
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && c.url.endsWith(`${RULES}/P2`))).toBe(true));
    expect(calls.find(c => c.method === 'PUT')!.body).toMatchObject({ expiryMonths: 12, neverExpire: false, pointsPerDollar: 2 });

    await userEvent.click(screen.getByRole('button', { name: 'Remove — Region One' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE' && c.url.endsWith(`${RULES}/P1`))).toBe(true));
  });

  it('is read-only without the promotions right', async () => {
    api(['finance'], c => (c.url.endsWith('/api/v1/console/me')
      ? { body: { userId: SESSION.user.id, roles: [{ role: 'finance', screens: ['overview', 'finance'], actions: ['refund'] }] } } : undefined));
    renderConsole('/finance');
    const card = (await screen.findByText('Points earning rules')).closest('section, .chart-card, div') as HTMLElement;
    expect(await within(card.parentElement ?? card).findByText('Default (everywhere else)')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Edit — / })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add a rule for a province or market' })).toBeNull();
    expect(screen.getByText(/Finance and admins with promotions rights change these/)).toBeTruthy();
  });
});
