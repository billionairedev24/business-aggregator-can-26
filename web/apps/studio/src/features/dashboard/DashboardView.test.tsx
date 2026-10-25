import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setTimeZone } from '@northline/ui';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { DashboardView } from './DashboardScreen';
import type { DashboardInputs } from './model';
import type { Dashboard } from './api';
import type { Report } from '../finance/api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const navigate = vi.fn();
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }));

// a Tuesday afternoon where the business is (the test pins the zone: the copy reads the merchant's local time)
const NOW = new Date('2026-09-08T20:30:00Z');
const at = (min: number) => new Date(NOW.getTime() + min * 60_000).toISOString();
const data: Dashboard = {
  today: '2026-09-08',
  jobsToday: [
    { id: 'j1', startsAt: '2026-09-08T15:00:00Z', title: 'Brake inspection', customerName: 'A. Osei', area: 'Beltline', access: null, escrowHeldCents: 9345, state: 'completed', memberName: 'Ravi', mine: true },
    { id: 'j2', startsAt: '2026-09-08T22:00:00Z', title: 'Diagnostic scan', customerName: 'M. Tran', area: 'Inglewood', access: null, escrowHeldCents: null, state: 'confirmed', memberName: 'Jas', mine: false },
  ],
  run: [],
  counts: { visitsToday: 2, quoteRequestsOpen: 2, quoteRespondBy: at(72), toPack: 3, runCutoff: at(170), runLabel: 'R-611', jobsThisMonth: 29, ordersThisMonth: 38, itemsThisMonth: 112 },
  earnings: { netThisMonthCents: 682000, netLastMonthCents: 578000, releasingCents: 214000, releasingAt: '2026-09-11T18:00:00Z', weeks: Array.from({ length: 12 }, (_, i) => ({ start: `2026-06-${10 + i}`, servicesCents: 90000 + i * 5000, partsCents: 20000 })) },
  reputation: { rating: 4.8, reviews: 312, qualityScore: 91, quality: {}, refundRateBps: 120 },
  cases: [{ kind: 'refund', customerName: 'D. Kowalski', subject: 'Wrong item' }],
  lowStock: [{ name: 'Seeded rye', stock: 4 }],
  compliance: [{ checkType: 'ahs_permit', registry: null, status: 'valid', expiresAt: '2026-11-30T18:00:00Z', pausesAt: null }],
  coaching: { jobs: 20, withoutPhotos: 3, nextTierReview: '2026-10-01' },
};
const days = Array.from({ length: 30 }, (_, i) => ({ start: new Date(Date.UTC(2026, 7, 10 + i)).toISOString().slice(0, 10), currentCents: 50000 + (i % 7) * 1000, previousCents: 40000 }));
days[29]!.currentCents = 64250; // today, a Tuesday
days[22]!.currentCents = 57366; // last Tuesday
const report: Report = {
  period: '30d', from: '2026-08-10', to: '2026-09-08', grossCents: 1520000, grossChangePct: 6, count: 899, averageTicketCents: 1691, repeatCustomerPct: 40, refundRateBps: 30,
  benchmarkRefundRateBps: null, granularity: 'day', series: days, byListing: [{ name: 'Country sourdough, 900 g', grossCents: 18700 }, { name: null, grossCents: 9000 }], sources: [],
};
const base = (over: Partial<DashboardInputs> = {}): DashboardInputs => ({
  kind: 'seller', data, now: NOW, firstName: 'Mei', status: 'active', report,
  payout: { availableCents: 128460, payableCents: 128460, reserveCents: 0, nextPayoutAt: '2026-09-14T16:00:00Z', schedule: { frequency: 'weekly', weekday: 1, monthlyAnchor: null, reserve: 'none' },
    account: { id: 'a1', method: 'instant', institutionName: 'RBC', last4: '0412', holderName: 'Mei Tanaka', label: 'Chequing', state: 'active' }, pendingAccount: null, pausedUntil: null,
    instant: { eligible: false, feeBps: 100, minFeeCents: 50, minAmountCents: 1000 }, returned: null },
  threads: [
    { id: 't1', kind: 'customer', counterpartName: 'K. Nguyen', subject: null, lastMessageAt: at(-38), lastMessage: 'Can you slice the rye?', unread: true },
    { id: 't2', kind: 'customer', counterpartName: 'L. Moreau', subject: null, lastMessageAt: at(-5), lastMessage: 'Thanks!', unread: true },
    { id: 't3', kind: 'support', counterpartName: 'Support', subject: null, lastMessageAt: at(-500), lastMessage: 'Hi', unread: true },
  ],
  orders: { items: [
    { id: 'o1', ref: 'NL-1', customerName: 'A', area: null, lines: [], totalCents: 100, windowStartsAt: null, cutoffAt: null, runLabel: null, placedAt: at(-60), deliveredAt: null, status: 'to_pack', issueNote: null },
    { id: 'o2', ref: 'NL-2', customerName: 'B', area: null, lines: [], totalCents: 100, windowStartsAt: null, cutoffAt: null, runLabel: null, placedAt: at(-60 * 30), deliveredAt: null, status: 'delivered', issueNote: null },
  ], counts: { toPack: 3, awaitingPickup: 0, deliveredToday: 0, issues: 1, nextCutoff: null, nextRunLabel: null } },
  listings: [
    { id: 'p1', kind: 'product', name: 'Country sourdough, 900 g', meta: '', priceCents: 850, stock: 14, sales30d: 22, vetting: 'approved', status: 'live', vettingFlags: [], revetReasons: [], updatedAt: '2026-09-01', bundle: false, imageUrl: '/media/loaf.jpg' },
    { id: 'p2', kind: 'product', name: 'Seeded rye', meta: '', priceCents: 925, stock: 4, sales30d: 11, vetting: 'approved', status: 'live', vettingFlags: [], revetReasons: [], updatedAt: '2026-09-01', bundle: false, imageUrl: null },
    { id: 'p3', kind: 'product', name: 'Unsold scone', meta: '', priceCents: 300, stock: 9, sales30d: 0, vetting: 'approved', status: 'live', vettingFlags: [], revetReasons: [], updatedAt: '2026-09-01', bundle: false, imageUrl: null },
  ] as DashboardInputs['listings'],
  setup: { page: 'live', listings: 3 },
  ...over,
});

beforeEach(() => { navigate.mockReset(); setTimeZone('America/Vancouver'); });

describe('Dashboard (E-15)', () => {
  it('seller: greeting and the day’s run, "Needs you now" most urgent first with one button each, figures, chart and top products', async () => {
    renderWithProviders(<DashboardView inputs={base()} merchantId="m1" onRange={() => {}} />);
    await expectNoAxeViolations(document.body);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Good afternoon, Mei');
    expect(screen.getByText('Tuesday, September 8 · Run R-611 closes at 4:20 p.m.')).toBeTruthy();
    expect(screen.getByText('Store open')).toBeTruthy();

    const needs = screen.getByRole('region', { name: /Needs you now/ });
    const titles = within(needs).getAllByRole('heading', { level: 3 }).map(h => h.textContent);
    expect(titles).toEqual(['Urgent: 1 issue to resolve', 'Due soon: 3 orders to pack', 'Due soon: Seeded rye is running low', '2 messages waiting', 'Payout on Monday, Sep 14', 'Food handling permit']);
    expect(within(needs).getByText('Pack by 4:20 p.m. for run R-611')).toBeTruthy();
    expect(within(needs).getByText('Oldest 38 min · K. Nguyen: “Can you slice the rye?”')).toBeTruthy();
    expect(within(needs).getByText('$1,284.60 to RBC ••• 0412')).toBeTruthy();
    expect(within(needs).getByText('Expires Nov 30, 2026 · upload the renewal')).toBeTruthy();
    // the most urgent card's button is the primary one, the others secondary; each is a link to the screen that does it
    const resolve = within(needs).getByRole('link', { name: 'Resolve' });
    expect(resolve.className).toContain('btn-primary');
    expect(within(needs).getByRole('link', { name: 'Start packing' }).className).toContain('btn-secondary');
    expect(within(needs).getByRole('link', { name: 'Reply' }).getAttribute('href')).toBe('/b/m1/messages?thread=t1');
    await userEvent.click(within(needs).getByRole('link', { name: 'Update stock' }));
    expect(navigate).toHaveBeenCalledWith({ to: '/b/m1/listings', search: { view: 'stock', q: 'Seeded rye' } }); // S-175: the Low stock tab, searched

    const kpis = within(screen.getByRole('list', { name: 'Key figures' }));
    expect(kpis.getByRole('button', { name: /Sales today \$642\.50 \+12% vs last Tuesday/ })).toBeTruthy();
    expect(kpis.getByRole('button', { name: /Orders today 1 3 to pack/ })).toBeTruthy();
    expect(kpis.getByRole('button', { name: /Average order · 30 days \$16\.91/ })).toBeTruthy();
    expect(kpis.getByRole('button', { name: /Rating 4\.8 312 reviews/ })).toBeTruthy();
    expect(kpis.queryByText('Bookings today')).toBeNull();

    expect(screen.getByRole('img', { name: 'Sales, last 14 days' })).toBeTruthy();
    expect(screen.getByText('Today so far · $642.50')).toBeTruthy();
    const top = within(screen.getByRole('table', { name: 'Top products · 30 days' }));
    const rows = top.getAllByRole('row').slice(1).map(r => r.textContent);
    expect(rows).toEqual(['Country sourdough, 900 g22$187.0014 in stock', 'SSeeded rye11—4 left']);
  });

  it('the range control switches the chart to 30 days or 12 months', async () => {
    const onRange = vi.fn();
    const { rerender } = renderWithProviders(<DashboardView inputs={base()} merchantId="m1" onRange={onRange} />);
    await userEvent.click(screen.getByRole('radio', { name: '30 days' }));
    expect(onRange).toHaveBeenCalledWith('30d');
    rerender(<DashboardView inputs={base()} merchantId="m1" range="30d" onRange={onRange} />);
    expect(screen.getByRole('img', { name: 'Sales, last 30 days' })).toBeTruthy();
    expect(screen.getByText(/^Previous 29 days · avg \$531\.\d\d$/)).toBeTruthy();
  });

  it('provider: the next booking, quotes to send and today’s bookings; no orders or stock', () => {
    renderWithProviders(<DashboardView inputs={base({ kind: 'provider', orders: undefined, listings: undefined })} merchantId="m1" />);
    expect(screen.getByText('Tuesday, September 8 · Next booking at 3:00 p.m.: Diagnostic scan')).toBeTruthy();
    expect(screen.getByText('Taking bookings')).toBeTruthy();
    const needs = screen.getByRole('region', { name: /Needs you now/ });
    expect(within(needs).getByRole('heading', { name: 'Due soon: 2 quote requests' })).toBeTruthy();
    expect(within(needs).getByText('Reply by 2:42 p.m. to keep your response score')).toBeTruthy();
    expect(within(needs).queryByText(/to pack/)).toBeNull();
    expect(within(needs).queryByText(/running low/)).toBeNull();
    const kpis = within(screen.getByRole('list', { name: 'Key figures' }));
    expect(kpis.getByRole('button', { name: /Bookings today 2 2 quote requests/ })).toBeTruthy();
    expect(kpis.getByRole('button', { name: /Average booking · 30 days/ })).toBeTruthy();
    const today = within(screen.getByRole('table', { name: "Today's bookings" }));
    expect(today.getByText('Diagnostic scan')).toBeTruthy();
    expect(today.getByText('M. Tran · Inglewood · Jas')).toBeTruthy();
  });

  it('kitchen: tickets to accept, sold-out dishes, today’s dishes and the pause control', async () => {
    const calls = mockFetch({ 'POST /api/v1/merchants/m1/kitchen/pause': () => ({ items: [], counts: { open: 0, fresh: 0, cooking: 0, ready: 0 }, prep: { defaultPrepMin: 15, bumpMin: 0, shownMin: 15 }, pausedUntil: at(30) }) });
    const dish = (id: string, name: string, soldToday: number, soldOut = false) => ({ id, menuId: 'mn', sectionId: 's', name, priceCents: 1450, allergens: [], dietary: [], prepAddMin: 0, soldToday, soldOut, availability: 'always' as const, comboEligible: false, modifierGroups: [], status: 'published' as const, visibility: 'live' as const, hasPhoto: false });
    renderWithProviders(<DashboardView merchantId="m1" inputs={base({
      kind: 'kitchen', orders: undefined, listings: undefined,
      live: { items: [], counts: { open: 5, fresh: 2, cooking: 3, ready: 0 }, prep: { defaultPrepMin: 15, bumpMin: 0, shownMin: 15 }, pausedUntil: null },
      dishes: [dish('d1', 'Pho tai', 18), dish('d2', 'Banh mi', 7, true), dish('d3', 'Spring rolls', 0)],
    })} />);
    expect(screen.getByText('Tuesday, September 8 · 5 open tickets')).toBeTruthy();
    const needs = screen.getByRole('region', { name: /Needs you now/ });
    expect(within(needs).getAllByRole('heading', { level: 3 })[0]!.textContent).toBe('Urgent: 2 new orders to accept');
    expect(within(needs).getByRole('heading', { name: 'Due soon: 1 dish sold out' })).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Key figures' })).getByRole('button', { name: /Open tickets 5 2 new · 3 cooking/ })).toBeTruthy();
    const top = within(screen.getByRole('table', { name: 'Top dishes today' }));
    expect(top.getAllByRole('row')).toHaveLength(3);
    expect(top.getByText('Sold out')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Pause orders' }));
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/kitchen/pause'))).toBe(true);
  });

  it('a new business: setting up the page and the first listing are cards too; without a sales report the month’s net leads', () => {
    renderWithProviders(<DashboardView inputs={base({ report: undefined, setup: { page: 'none', listings: 0 }, data: { ...data, cases: [], lowStock: [], compliance: [] }, threads: [], payout: undefined })} merchantId="m1" />);
    const needs = screen.getByRole('region', { name: /Needs you now/ });
    expect(within(needs).getByRole('heading', { name: 'Set up your store page' })).toBeTruthy();
    expect(within(needs).getByRole('link', { name: 'Add a listing' }).getAttribute('href')).toBe('/b/m1/listings');
    expect(within(screen.getByRole('list', { name: 'Key figures' })).getByRole('button', { name: /Net this month \$6,820\.00 \+18% vs Aug/ })).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Net earnings, last 12 weeks' })).toBeTruthy();
    expect(screen.queryByRole('radiogroup', { name: 'Period' })).toBeNull();
  });

  it('nothing waiting: says so', () => {
    renderWithProviders(<DashboardView inputs={base({ data: { ...data, cases: [], lowStock: [], compliance: [], counts: { ...data.counts, toPack: 0 } }, threads: [], payout: undefined })} merchantId="m1" />);
    expect(screen.getByText("You're all caught up. New work shows here first.")).toBeTruthy();
  });

  it('reads French', () => {
    renderWithProviders(<DashboardView inputs={base()} merchantId="m1" onRange={() => {}} />, 'fr');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Bon après-midi, Mei');
    expect(screen.getByText('Mardi 8 septembre · La tournée R-611 ferme à 16 h 20')).toBeTruthy();
    expect(screen.getByRole('heading', { name: /À faire maintenant/ })).toBeTruthy();
    expect(screen.getByRole('link', { name: "Commencer l'emballage" })).toBeTruthy();
    expect(screen.getByText('Ventes aujourd\'hui')).toBeTruthy();
    expect(screen.getByRole('radio', { name: '30 jours' })).toBeTruthy();
  });
});
