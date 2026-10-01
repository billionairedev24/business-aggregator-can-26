import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider } from '@northline/ui';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// ── shell (merchant context) ──────────────────────────────────────────────────────────────────────────────────────
const shell = vi.hoisted(() => ({ role: 'owner', type: 'provider', tier: 'master' }));
vi.mock('../shell/api', () => ({
  useMerchantId: () => 'PWM1',
  useMerchant: () => ({ id: 'PWM1', displayName: 'Prairie Wrench', type: shell.type, tier: shell.tier, role: shell.role }),
  useRole: () => shell.role,
}));

import { EarningsScreen } from './EarningsScreen';
import { ReportsScreen } from './ReportsScreen';
import { PayoutsScreen } from './PayoutsScreen';
import { RefundsScreen } from './RefundsScreen';
import { weekdayLong } from './format';

// ── fake api ──────────────────────────────────────────────────────────────────────────────────────────────────────
type Reply = { status: number; body?: unknown };
let routes: Record<string, (body: unknown, headers: Record<string, string>) => Reply>;
let calls: { method: string; path: string; body: unknown; headers: Record<string, string> }[];
const ok = (body: unknown): Reply => ({ status: 200, body });
const hours = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString();
const nextPayout = hours(50);

const earnings = {
  headlineCents: 214060, availableCents: 82260, escrowNetCents: 131800, escrowCount: 11, onHoldCents: 16000, onHoldDisputes: 1, onHoldRefunds: 0,
  nextPayoutAt: nextPayout, frequency: 'weekly', tier: 'master', takeRateBps: 900, takeRates: { master: 900, trusted: 1200, registered: 1500 },
};
const ledger = { items: [
  { id: 'e1', kind: 'goods', label: 'Brake pads', orderNumber: null, occurredAt: '2026-09-06T17:00:00Z', customerName: 'D. Kowalski', grossCents: 24700, feeCents: 2223, netCents: 22477, state: 'released', releaseAt: null, releasedAt: '2026-09-08T17:00:00Z' },
  { id: 'e2', kind: 'service', label: 'Diagnostic', orderNumber: null, occurredAt: '2026-09-06T17:00:00Z', customerName: 'M. Tran', grossCents: 12000, feeCents: 1080, netCents: 10920, state: 'held', releaseAt: hours(30.5), releasedAt: null },
  { id: 'e3', kind: 'goods', label: 'Wiper blades ×2', orderNumber: 'NL-48190', occurredAt: '2026-09-05T17:00:00Z', customerName: 'S. Bouchard', grossCents: 3800, feeCents: 342, netCents: 3458, state: 'released', releaseAt: null, releasedAt: null },
  { id: 'e4', kind: 'service', label: 'Pre-purchase', orderNumber: null, occurredAt: '2026-09-04T17:00:00Z', customerName: 'A. Osei', grossCents: 16000, feeCents: 1440, netCents: 14560, state: 'disputed', releaseAt: null, releasedAt: null },
] };
const report = (period: string) => ({
  period, from: '2026-07-02T06:00:00Z', to: '2026-09-29T17:00:00Z', grossCents: 1944000, grossChangePct: 22, count: 118, averageTicketCents: 16475,
  repeatCustomerPct: 71, refundRateBps: 240, benchmarkRefundRateBps: 310, granularity: period === '30d' ? 'day' : 'week',
  series: [{ start: '2026-07-02', currentCents: 320000, previousCents: 260000 }, { start: '2026-07-09', currentCents: 360000, previousCents: 290000 }],
  byListing: [{ name: 'Brake inspection', grossCents: 582000 }, { name: null, grossCents: 116000 }],
  sources: [{ source: 'search', pct: 54 }, { source: 'repeat', pct: 31 }, { source: 'embed', pct: 11 }, { source: 'referral', pct: 4 }],
});
const account = { id: 'a1', method: 'manual', institutionName: 'TD Canada Trust', last4: '3391', holderName: 'Prairie Wrench Mobile Mechanics Ltd.', label: 'TD ··3391', state: 'active', effectiveAt: null };
const overview = {
  availableCents: 82260, payableCents: 82260, reserveCents: 0, nextPayoutAt: nextPayout, schedule: { frequency: 'weekly', weekday: 5, monthlyAnchor: null, reserve: 'none' },
  account, pendingAccount: null, pausedUntil: null, instant: { eligible: true, feeBps: 100, minFeeCents: 50, minAmountCents: 100 },
};
const payouts = { items: [{ id: 'p1', reference: 'po_Ab12', kind: 'scheduled', state: 'paid', createdAt: '2026-09-04T15:00:00Z', arrivesAt: '2026-09-07T15:00:00Z', amountCents: 191240, feeCents: 0, netCents: 191240, itemCount: 14, destination: 'TD ··3391' }] };
const dispute = {
  id: 'd1', type: 'dispute', caseNumber: 'DS-1188', subject: 'Pre-purchase inspection', amountCents: 16000, customerName: 'A. Osei',
  customerStatement: 'Report missed a coolant leak the dealer found two days later.', response: null, responseUpdatedAt: null,
  evidence: [
    ...Array.from({ length: 12 }, (_, i) => ({ id: `ph${i}`, kind: 'photo', name: `IMG_${i}.jpg`, contentType: 'image/jpeg', size: 1, by: 'merchant', at: '2026-09-04T20:14:00Z', downloadable: false })),
    { id: 'rp', kind: 'report', name: 'Inspection report.pdf', contentType: 'application/pdf', size: 1, by: 'merchant', at: '2026-09-04T20:14:00Z', downloadable: false },
  ],
  state: 'open', offer: null, dueBy: hours(40), auto: false, openedAt: '2026-09-05T17:00:00Z',
};
const cases = {
  openDisputes: 1, refundsLast30Days: 2, disputeRateBps: 30, disputeRateFloorBps: 100, open: [dispute],
  history: [
    { id: 'r1', type: 'refund', caseNumber: 'RF-2201', what: 'Wiper blade wrong size', amountCents: 1900, kind: 'refund', outcome: 'auto_refunded', openedAt: '2026-09-10T00:00:00Z' },
    { id: 'r2', type: 'refund', caseNumber: 'RF-2188', what: 'Late arrival > 30 min', amountCents: 1500, kind: 'credit', outcome: 'goodwill', openedAt: '2026-09-09T00:00:00Z' },
    { id: 'd0', type: 'dispute', caseNumber: 'DS-1102', what: 'Diagnostic disagreement', amountCents: 12000, kind: 'refund', outcome: 'won', openedAt: '2026-08-20T00:00:00Z' },
  ],
};

beforeEach(() => {
  shell.role = 'owner'; shell.type = 'provider'; shell.tier = 'master';
  calls = [];
  routes = {
    'GET /api/v1/merchants/PWM1/earnings': () => ok(earnings),
    'GET /api/v1/merchants/PWM1/earnings/ledger': () => ok(ledger),
    'GET /api/v1/merchants/PWM1/reports': () => ok(report('90d')),
    'GET /api/v1/merchants/PWM1/payouts/overview': () => ok(overview),
    'GET /api/v1/merchants/PWM1/payouts': () => ok(payouts),
    'GET /api/v1/merchants/PWM1/payouts/schedule/preview': () => ok({ nextPayoutAt: nextPayout, amountCents: 82260 }),
    'POST /api/v1/merchants/PWM1/payouts/instant': (b) => ({ status: 201, body: { ...payouts.items[0], id: 'p2', reference: 'po_9Kx2', kind: 'instant', state: 'in_transit', amountCents: (b as { amountCents: number }).amountCents, feeCents: 823, netCents: 81437, arrivesAt: hours(0.5), createdAt: hours(0) } }),
    'GET /api/v1/merchants/PWM1/refunds': () => ok(cases),
    'POST /api/v1/merchants/PWM1/disputes/d1/goodwill-offer': () => ok({ ...dispute, state: 'seller_replied', offer: { amountCents: 8000, state: 'pending', expiresAt: hours(72) } }),
  };
  vi.stubEnv('VITE_NL_DEV_STEP_UP', '1');
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const u = new URL(url, 'http://localhost');
    const method = init?.method ?? 'GET';
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    const headers = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
    calls.push({ method, path: u.pathname + u.search, body, headers });
    const handler = routes[`${method} ${u.pathname}`];
    const reply = handler ? handler(body, headers) : { status: 404 };
    return new Response(reply.body === undefined ? '' : JSON.stringify(reply.body), { status: reply.status, headers: { 'content-type': 'application/json' } });
  }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

function renderScreen(ui: ReactNode, locale: 'en' | 'fr' = 'en') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<I18nProvider initial={locale}><QueryClientProvider client={qc}>{ui}</QueryClientProvider></I18nProvider>);
  return userEvent.setup({ delay: null });
}

describe('Earnings', () => {
  it('shows the headline, KPIs, money in motion and a view-only ledger', async () => {
    renderScreen(<EarningsScreen />);
    expect(await screen.findByRole('heading', { level: 1, name: `$2,140.60 releasing ${weekdayLong(nextPayout, 'en')}` })).toBeTruthy();
    expect(screen.getByText('$1,318.00')).toBeTruthy();
    expect(screen.getByText('in escrow · 11 jobs')).toBeTruthy();
    expect(screen.getByText('released, next payout')).toBeTruthy();
    expect(screen.getByText('9%')).toBeTruthy();
    expect(screen.getByText('take rate · Master (Trusted 12%, Registered 15%)')).toBeTruthy();
    expect(screen.getByText('on hold · 1 dispute')).toBeTruthy();
    expect(screen.getByText('3 · Sign-off / 48 h')).toBeTruthy();
    expect(await screen.findAllByText('Escrow · 31 h')).not.toHaveLength(0);
    expect(screen.getAllByText('Disputed · on hold')).not.toHaveLength(0);
    expect(screen.getAllByText('Wiper blades ×2 · NL-48190')).not.toHaveLength(0);
    expect(screen.getByText(/View only/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /add/i })).toBeNull();
  });

  it('uses kitchen wording in French', async () => {
    shell.type = 'kitchen';
    renderScreen(<EarningsScreen />, 'fr');
    expect(await screen.findByText('en fiducie · 11 commandes')).toBeTruthy();
    expect(screen.getByText('3 · À la remise')).toBeTruthy();
  });

  it('shows an error with retry', async () => {
    routes['GET /api/v1/merchants/PWM1/earnings'] = () => ({ status: 500 });
    const ui = renderScreen(<EarningsScreen />);
    const retry = await screen.findByRole('button', { name: 'Retry' });
    routes['GET /api/v1/merchants/PWM1/earnings'] = () => ok(earnings);
    await ui.click(retry);
    expect(await screen.findByText('in escrow · 11 jobs')).toBeTruthy();
  });
});

describe('Reports', () => {
  it('shows the KPIs and switches the period', async () => {
    const ui = renderScreen(<ReportsScreen />);
    expect(await screen.findByText('$19,440')).toBeTruthy();
    expect(screen.getByText('gross sales · +22%')).toBeTruthy();
    expect(screen.getByText('$164.75')).toBeTruthy();
    expect(screen.getByText('71%')).toBeTruthy();
    expect(screen.getByText('refund rate · category avg 3.1%')).toBeTruthy();
    expect(screen.getByText('Everything else')).toBeTruthy();
    expect(screen.getByText('Your website embed (API)')).toBeTruthy();
    expect((screen.getByRole('link', { name: 'Export CSV' })).getAttribute('href')).toBe('/api/v1/merchants/PWM1/reports/export.csv?period=90d');
    const year = new Date().getFullYear();
    expect((screen.getByRole('link', { name: 'Tax summary (GST)' })).getAttribute('href')).toBe(`/api/v1/merchants/PWM1/reports/gst-summary.pdf?year=${year}&lang=en`);
    await ui.click(screen.getByRole('radio', { name: '30 d' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Last 30 days' })).toBeTruthy();
    await waitFor(() => expect(calls.some(c => c.path === '/api/v1/merchants/PWM1/reports?period=30d')).toBe(true));
  });
});

describe('Tax documents (S-41)', () => {
  it('offers each document as PDF in the Studio language, and as CSV', async () => {
    renderScreen(<PayoutsScreen />, 'fr');
    const year = new Date().getFullYear();
    const pdf = await screen.findByRole('link', { name: `Télécharger Relevé annuel ${year - 1} en PDF` });
    expect(pdf.getAttribute('href')).toBe(`/api/v1/merchants/PWM1/reports/annual-statement.pdf?year=${year - 1}&lang=fr`);
    expect(screen.getByRole('link', { name: `Télécharger Relevé annuel ${year - 1} en CSV` }).getAttribute('href')).toBe(`/api/v1/merchants/PWM1/reports/annual-statement.csv?year=${year - 1}`);
    expect(screen.getByRole('link', { name: `Télécharger Sommaire de la TPS perçue ${year} (cumul annuel) en PDF` }).getAttribute('href')).toContain('gst-summary.pdf');
  });
});

describe('Payouts', () => {
  it('instant payout: validates the amount, steps up, sends the Idempotency-Key and shows the receipt', async () => {
    const ui = renderScreen(<PayoutsScreen />);
    expect(await screen.findByText('$822.60')).toBeTruthy();
    await ui.click(screen.getByRole('button', { name: 'Instant payout · 1% fee' }));
    const amount = screen.getByLabelText('Amount');
    await ui.clear(amount);
    await ui.type(amount, '900');
    await ui.tab();
    expect((screen.getByRole('alert')).textContent).toContain('You can pay out up to $822.60.');
    expect(((screen.getByRole('button', { name: 'Continue' })) as HTMLButtonElement).disabled).toBe(true);
    await ui.clear(amount);
    await ui.tab();
    expect((screen.getByRole('alert')).textContent).toContain('Enter an amount.');
    await ui.click(screen.getByRole('button', { name: 'Max $822.60' }));
    expect(screen.getByText('−$8.23')).toBeTruthy();
    expect(screen.getByText('$814.37')).toBeTruthy();
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Confirm with your passkey.')).toBeTruthy();
    await ui.click(screen.getByRole('button', { name: 'Use Face ID / passkey' }));
    expect(await screen.findByText('$814.37 on its way.')).toBeTruthy();
    const post = calls.find(c => c.method === 'POST' && c.path.endsWith('/payouts/instant'))!;
    expect(post.body).toEqual({ amountCents: 82260 });
    expect(post.headers['idempotency-key']).toMatch(/.{8,}/);
    expect(post.headers['x-step-up']).toBe('dev');
    expect(screen.getByText(/Payout po_9Kx2 · TD ··3391/)).toBeTruthy();
  });

  it('bookkeepers see the money but no payout controls', async () => {
    shell.role = 'bookkeeper';
    renderScreen(<PayoutsScreen />);
    expect(await screen.findByText('$822.60')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Instant payout · 1% fee' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Change' })).toBeNull();
    expect(await screen.findAllByText('$1,912.40')).not.toHaveLength(0);
  });

  it('bank change: manual details are validated with a summary, server 422s land on fields', async () => {
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts'] = () => ({ status: 422, body: { errors: [{ field: 'accountNumber', rule: 'format', message: 'Account numbers are 7 to 12 digits.' }] } });
    const ui = renderScreen(<PayoutsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Change' }));
    expect(screen.getByText(/payouts pause for 24 hours/)).toBeTruthy();
    await ui.click(screen.getByRole('radio', { name: 'Enter details manually' }));
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('3 things need attention.')).toBeTruthy();
    expect(screen.getByText('Enter the 3-digit institution number.')).toBeTruthy();
    expect(screen.getByText('Enter the 5-digit transit number.')).toBeTruthy();
    await ui.type(screen.getByLabelText('Institution (3 digits)'), '003');
    await ui.type(screen.getByLabelText('Transit (5 digits)'), '12345');
    await ui.type(screen.getByLabelText('Account number'), '0012348820');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Account numbers are 7 to 12 digits.')).toBeTruthy();
  });

  it('bank change: instant link → confirm with passkey → 24 h hold', async () => {
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts/link-session'] = () => ok({ mode: 'fake' });
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts'] = () => ({ status: 201, body: { ...account, id: 'a2', institutionName: 'RBC', last4: '8820', label: 'RBC ··8820', state: 'draft' } });
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts/a2/confirm'] = () => ok({ ...account, id: 'a2', institutionName: 'RBC', last4: '8820', label: 'RBC ··8820', state: 'pending', effectiveAt: hours(24) });
    const ui = renderScreen(<PayoutsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Change' }));
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    // no Stripe key: the simulated Financial Connections picker, never Stripe.js
    expect(await screen.findByRole('group', { name: 'Test bank connection' })).toBeTruthy();
    expect(document.querySelector('script[src*="js.stripe.com"]')).toBeNull();
    await ui.click(screen.getByRole('button', { name: 'Link account' }));
    expect(await screen.findByText(/RBC · ··8820 · Prairie Wrench Mobile Mechanics Ltd\./)).toBeTruthy();
    const prepared = calls.find(c => c.method === 'POST' && c.path.endsWith('/payouts/bank-accounts'))!;
    expect(prepared.body).toMatchObject({ method: 'instant', linkedAccount: 'btok_local_003_8820' });
    expect((prepared.body as { financialConnectionsAccount: string }).financialConnectionsAccount).toMatch(/^fca_local_/);
    await ui.click(screen.getByRole('button', { name: 'Confirm with passkey' }));
    expect(await screen.findByText('Account changed.')).toBeTruthy();
    const confirm = calls.find(c => c.path.endsWith('/a2/confirm'))!;
    expect(confirm.headers['x-step-up']).toBe('dev');
    expect(confirm.headers['idempotency-key']).toBeTruthy();
  });

  it('bank change: with Stripe configured, Stripe.js collects the token and the Financial Connections account', async () => {
    const collect = vi.fn(async () => ({ token: { id: 'btok_1' }, financialConnectionsAccount: { id: 'fca_1' } }));
    vi.stubGlobal('Stripe', vi.fn(() => ({ collectBankAccountToken: collect })));
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts/link-session'] = () => ok({ mode: 'stripe', clientSecret: 'fcsess_secret_x', publishableKey: 'pk_test_fake' });
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts'] = () => ({ status: 201, body: { ...account, id: 'a3', method: 'instant', institutionName: 'BMO', last4: '4417', label: 'BMO ··4417', state: 'draft' } });
    const ui = renderScreen(<PayoutsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Change' }));
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText(/BMO · ··4417/)).toBeTruthy();
    expect(collect).toHaveBeenCalledWith({ clientSecret: 'fcsess_secret_x' });
    expect(calls.find(c => c.method === 'POST' && c.path.endsWith('/payouts/bank-accounts'))!.body)
      .toMatchObject({ method: 'instant', linkedAccount: 'btok_1', financialConnectionsAccount: 'fca_1' });
    vi.unstubAllGlobals();
  });

  it('bank change: a link the server refuses asks to connect again', async () => {
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts/link-session'] = () => ok({ mode: 'fake' });
    routes['POST /api/v1/merchants/PWM1/payouts/bank-accounts'] = () => ({ status: 422, body: { errors: [{ field: 'linkedAccount', rule: 'format', message: "We couldn't use that bank link. Connect your bank again." }] } });
    const ui = renderScreen(<PayoutsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Change' }));
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('button', { name: 'Link account' }));
    expect(await screen.findByText("We couldn't use that bank link. Connect your bank again.")).toBeTruthy();
  });

  it('a bank connection that ended shows a note and Reconnect (en + fr)', async () => {
    routes['GET /api/v1/merchants/PWM1/payouts/overview'] = () => ok({ ...overview, account: { ...account, method: 'instant', disconnectedAt: '2026-09-28T16:00:00Z' } });
    const ui = renderScreen(<PayoutsScreen />);
    expect(await screen.findByText(/^Bank connection ended .*reconnect to keep it verified\.$/)).toBeTruthy();
    await ui.click(screen.getByRole('button', { name: 'Reconnect' }));
    expect(screen.getByRole('region', { name: 'Change payout account' })).toBeTruthy();
  });

  it('a bank connection that ended, in French', async () => {
    routes['GET /api/v1/merchants/PWM1/payouts/overview'] = () => ok({ ...overview, account: { ...account, method: 'instant', disconnectedAt: '2026-09-28T16:00:00Z' } });
    renderScreen(<PayoutsScreen />, 'fr');
    expect(await screen.findByText(/^La connexion bancaire a pris fin le .*reconnectez-le pour qu’il reste vérifié\.$/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reconnecter' })).toBeTruthy();
  });

  it('schedule: previews the next payout and saves', async () => {
    routes['PUT /api/v1/merchants/PWM1/payouts/schedule'] = () => ok({ ...overview, schedule: { frequency: 'weekly', weekday: 1, monthlyAnchor: null, reserve: 'none' } });
    const ui = renderScreen(<PayoutsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Change schedule' }));
    await ui.click(screen.getByRole('radio', { name: 'Mon' }));
    expect(await screen.findByText(/Next payout would be .* · \$822\.60 plus anything released before then\./)).toBeTruthy();
    await ui.click(screen.getByRole('button', { name: 'Save schedule' }));
    expect(((await screen.findByRole('button', { name: 'Saved' })) as HTMLButtonElement).disabled).toBe(true);
    expect(calls.find(c => c.method === 'PUT')!.body).toEqual({ frequency: 'weekly', reserve: 'none', weekday: 1, monthlyAnchor: null });
  });
});

describe('Refunds & disputes', () => {
  it('shows the open dispute, evidence and history', async () => {
    renderScreen(<RefundsScreen />);
    expect(await screen.findByRole('heading', { level: 1, name: '1 open dispute, 2 refunds this month' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Dispute DS-1188 · Pre-purchase inspection · $160' })).toBeTruthy();
    expect(screen.getByText('Photos attached · 12')).toBeTruthy();
    expect(screen.getByText('Report PDF')).toBeTruthy();
    expect(screen.getAllByText('Won · evidence')).not.toHaveLength(0);
    expect(screen.getAllByText('$15.00 credit')).not.toHaveLength(0);
    expect(screen.getByText(/Dispute rate 0.3% \(floor for Master: 1%\)/)).toBeTruthy();
  });

  it('sends a 50% goodwill offer after confirming', async () => {
    const ui = renderScreen(<RefundsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Send 50% goodwill offer' }));
    const dialog = screen.getByRole('dialog', { name: 'Offer $80.00 as goodwill?' });
    await ui.click(within(dialog).getByRole('button', { name: 'Send 50% goodwill offer' }));
    await waitFor(() => expect(calls.some(c => c.path.endsWith('/goodwill-offer'))).toBe(true));
    const post = calls.find(c => c.path.endsWith('/goodwill-offer'))!;
    expect(post.body).toEqual({ amountCents: 8000 });
    expect(post.headers['idempotency-key']).toBeTruthy();
  });

  it('contesting needs a written response first', async () => {
    const ui = renderScreen(<RefundsScreen />);
    await ui.click(await screen.findByRole('button', { name: 'Contest — send to agent' }));
    expect((screen.getByRole('alert')).textContent).toContain('Write your response before sending this to an agent.');
    expect(screen.getByLabelText('Your response')).toBeTruthy();
  });

  it('bookkeepers can read but not answer', async () => {
    shell.role = 'bookkeeper';
    renderScreen(<RefundsScreen />);
    expect(await screen.findByText('Photos attached · 12')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Send 50% goodwill offer' })).toBeNull();
    expect(screen.queryByText('Add evidence')).toBeNull();
  });
});
