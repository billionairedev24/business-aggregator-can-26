import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/ops';
import { DashboardView } from './DashboardScreen';
import type { Dashboard } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const navigate = vi.fn();
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }));
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useMerchant: () => ({ type: 'provider', city: 'Calgary' }) }));

const soon = (min: number) => new Date(Date.now() + min * 60_000).toISOString();
const data: Dashboard = {
  today: '2026-09-08',
  jobsToday: [
    { id: 'j1', startsAt: '2026-09-08T15:00:00Z', title: 'Brake inspection', customerName: 'A. Osei', area: 'Beltline', access: 'P2 stall 118', escrowHeldCents: 9345, state: 'confirmed', memberName: 'Ravi', mine: true },
    { id: 'j2', startsAt: '2026-09-08T20:00:00Z', title: 'Diagnostic scan', customerName: 'M. Tran', area: 'Inglewood', access: null, escrowHeldCents: null, state: 'confirmed', memberName: 'Jas', mine: false },
    { id: 'j3', startsAt: '2026-09-08T17:30:00Z', title: 'Oil & filter', customerName: 'S. Bouchard', area: 'Kensington', access: null, escrowHeldCents: null, state: 'confirmed', memberName: 'Ravi', mine: true },
  ],
  run: [{ orderId: 'o1', ref: 'NL-48213', runLabel: 'R-611', customerName: 'A. Osei', area: 'Beltline', items: [{ title: 'Wiper blades', qty: 2 }], packed: false }],
  counts: { visitsToday: 3, quoteRequestsOpen: 2, quoteRespondBy: soon(72), toPack: 4, runCutoff: soon(120), runLabel: 'R-611', jobsThisMonth: 29, ordersThisMonth: 38, itemsThisMonth: 112 },
  earnings: { netThisMonthCents: 682000, netLastMonthCents: 578000, releasingCents: 214000, releasingAt: '2026-09-11T18:00:00Z', weeks: Array.from({ length: 12 }, (_, i) => ({ start: `2026-06-${10 + i}`, servicesCents: 90000 + i * 5000, partsCents: 20000 })) },
  reputation: { rating: 4.9, reviews: 312, qualityScore: 91, quality: { on_time: 98, photos: 85, response: 94, rebook: 71, dispute_rate: 0.3 }, refundRateBps: 120 },
  cases: [{ kind: 'dispute', customerName: 'A. Osei', subject: 'Pre-purchase inspection' }],
  lowStock: [{ name: 'Synthetic oil 5W-30', stock: 3 }],
  compliance: [{ checkType: 'wcb', registry: null, status: 'expired', expiresAt: '2026-08-31T18:00:00Z', pausesAt: soon(7 * 24 * 60) }],
  coaching: { jobs: 20, withoutPhotos: 3, nextTierReview: '2026-10-01' },
};

describe('Dashboard', () => {
  it('provider: headline, KPIs, today and needs-you like the design', async () => {
    renderWithProviders(<DashboardView data={data} kind="provider" city="Calgary" merchantId="m1" />);
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Three visits today, two quotes waiting, $2,140 releasing Friday.');
    expect(screen.getByText('Tuesday 8 September · Calgary')).toBeTruthy();
    expect(screen.getByText('jobs this month · 2 quotes open')).toBeTruthy();
    expect(screen.getByText('net this month · +18% vs Aug')).toBeTruthy();
    expect(screen.getByText('A. Osei · Beltline · P2 stall 118 · $93.45 escrow')).toBeTruthy();
    expect(screen.getByText('Jas')).toBeTruthy();
    expect(screen.getByRole('button', { name: /2 quote requests · respond within 1 h 1\d m to keep response score/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /1 dispute · A\. Osei, pre-purchase inspection/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /WCB clearance letter expired Aug 31 · instant book pauses in 7 days/ })).toBeTruthy();
    expect(screen.queryByText(/orders to pack/)).toBeNull();
    expect(screen.getByText(/completion photos missing on 3 of your last 20 jobs/)).toBeTruthy();
  });

  it('seller: orders headline, tonight’s run and low stock', () => {
    renderWithProviders(<DashboardView data={{ ...data, cases: [{ kind: 'refund', customerName: 'P. Nguyen', subject: 'Wiper blades wrong size' }] }} kind="seller" city="Calgary" merchantId="m1" />);
    // runCutoff is now + 2 h: on the hour it reads "by 4" (clockWithPeriod drops ":00"), so the minutes are optional.
    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/^Four orders to pack by \d+(:\d\d)?, one refund case, \$2,140 releasing Friday\.$/);
    expect(screen.getByText('orders this month · 112 items')).toBeTruthy();
    expect(screen.getByText('Wiper blades ×2 · NL-48213')).toBeTruthy();
    expect(screen.getByRole('button', { name: /4 orders to pack · run R-611 closes/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Synthetic oil 5W-30 low \(3 left\)/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /1 refund case · P\. Nguyen, wiper blades wrong size/ })).toBeTruthy();
  });

  it('both: visits and orders', () => {
    renderWithProviders(<DashboardView data={data} kind="both" city="Calgary" merchantId="m1" />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Three visits today, four orders to pack, $2,140 releasing Friday.');
    expect(screen.getByText('jobs & orders · 29 services, 38 parts')).toBeTruthy();
  });
});
