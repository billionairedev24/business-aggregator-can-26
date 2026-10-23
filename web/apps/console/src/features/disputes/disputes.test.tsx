import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, screenReady, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const NOW = new Date('2026-09-08T18:00:00Z');
const row = (o: Record<string, unknown>) => ({
  kind: 'dispute', merchantId: 'M1', customerName: 'Amara Osei', customerStatement: 'Report missed a coolant leak the dealer found two days later.',
  sellerStatement: 'Hoses dry at 2:14 pm; leak developed after. Offering 50%.', state: 'agent', pending: null, decision: null, ...o,
});
const DS1188 = { row: row({ id: 'D1', caseNumber: 'DS-1188', subject: 'Pre-purchase inspection · missed leak', amountCents: 16000, openedAt: '2026-09-06T18:00:00Z' }), businessName: 'Prairie Wrench', province: 'AB' };
const DS1190 = { row: row({ id: 'D2', caseNumber: 'DS-1190', subject: 'Mocktail bar · understaffed event', amountCents: 64000, openedAt: '2026-09-07T18:00:00Z', customerName: 'K. Ng',
  state: 'awaiting_cosign', pending: { id: 'AD1', outcome: 'full_refund', refundCents: 64000, note: 'One bartender for 40 guests.', decidedBy: 'U1', decidedAt: '2026-09-08T17:00:00Z', state: 'awaiting_cosign' } }), businessName: 'Sable & Soda', province: 'AB' };
const RF = { row: row({ kind: 'refund', id: 'R1', caseNumber: 'RF-2210', subject: 'Grocery run · 3 items spoiled', amountCents: 8800, openedAt: '2026-09-08T12:00:00Z', customerName: 'R. Diaz', customerStatement: null }), businessName: 'Bridgeland Butcher', province: 'AB' };
const QUEUE = { summary: { forAgent: 3, inSellerWindow: 61, closedThisWeek: 214 }, items: [DS1188, DS1190, RF] };
const detail = (item: typeof DS1188) => ({ item, detail: { evidence: [{ id: 'E1', kind: 'photo', name: 'job-photos.zip', contentType: 'application/zip', size: 10, by: 'merchant', at: '2026-09-06T20:00:00Z', file: true }], customerPriorDisputes: 0, sellerPriorDisputes: 1, sellerPriorWon: 1 }, sellerQuality: 91 });

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/disputes/dispute/D1')) return { body: detail(DS1188) };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/disputes/dispute/D2')) return { body: detail(DS1190) };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/disputes/refund/R1')) return { body: { ...detail(RF), detail: { ...detail(RF).detail, evidence: [] } } };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/disputes')) return { body: QUEUE };
    if (c.method === 'POST') return { body: { ...DS1188, row: { ...DS1188.row, state: 'decided' } } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('disputes & refunds (S-80, design 03 disputes)', () => {
  afterEach(() => vi.useRealTimers());

  it('shows the desk: KPIs, the state tabs with counts, status tags, and a case’s page with both parties and the evidence', async () => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date'] });
    api(['trust_safety']);
    const { router } = renderConsole('/disputes');
    expect((await screenReady()).textContent).toBe('Disputes & refunds');
    expect(screen.getByText('All markets · 3 cases for an agent · decided within 2 business days')).toBeTruthy();
    const kpis = within(screen.getByRole('list', { name: 'The desk at a glance' }));
    expect(kpis.getByText('For an agent').nextElementSibling?.textContent).toBe('3');
    expect(kpis.getByText('In the seller’s 24 h').nextElementSibling?.textContent).toBe('61');
    expect(kpis.getByText('Closed this week').nextElementSibling?.textContent).toBe('214');
    expect(within(screen.getByRole('tablist', { name: 'Cases by state' })).getAllByRole('tab').map(x => x.textContent)).toEqual(['For an agent2', 'Waiting for a co-sign1', 'Decided0', 'All3']);
    const ds = screen.getByText('DS-1188').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(ds).getByText('$160.00')).toBeTruthy();
    expect(within(ds).getByText('opened 2.0 d ago')).toBeTruthy();
    expect(within(ds).getByText('For an agent').className).toContain('nl-status-warning');
    expect(screen.queryByText('DS-1190')).toBeNull(); // the co-sign tab holds it
    await userEvent.setup({ delay: null, advanceTimers: () => {} }).click(within(ds).getByRole('button', { name: /Open/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/disputes/dispute/D1'));
    expect(await screen.findByRole('heading', { level: 1, name: 'DS-1188 · Pre-purchase inspection · missed leak' })).toBeTruthy();
    expect(screen.getByText('In escrow', { selector: 'dt' }).nextElementSibling?.textContent).toBe('$160.00');
    expect(screen.getByText('Customer → seller', { selector: 'dt' }).nextElementSibling?.textContent).toBe('Amara Osei → Prairie Wrench');
    expect(await screen.findByText('Quality 91')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('1 prior · 1 won')).toBeTruthy();
    expect(screen.getByText('0 prior disputes')).toBeTruthy();
    await userEvent.setup({ delay: null, advanceTimers: () => {} }).click(screen.getByRole('tab', { name: /Evidence/ }));
    expect(screen.getByRole('link', { name: 'job-photos.zip' }).getAttribute('href')).toBe('/api/v1/console/disputes/dispute/D1/evidence/E1');
    const panel = within(screen.getByRole('complementary', { name: 'Decision' }));
    for (const o of ['Full refund to customer', 'Partial (50%)', 'Release to seller', 'Goodwill credit (platform pays)']) expect(panel.getByRole('radio', { name: o })).toBeTruthy();
    expect(panel.getByRole('button', { name: 'Decide: Partial (50%)' })).toBeTruthy();
    expect(panel.getByText('Both parties get the note; seller may appeal once.')).toBeTruthy();
  });

  it('decides with the chosen outcome, amount and note, and the role header', async () => {
    const calls = api(['trust_safety']);
    renderConsole('/disputes/dispute/D1');
    await screen.findByRole('heading', { level: 1, name: /DS-1188/ });
    await user().click(screen.getByRole('radio', { name: 'Goodwill credit (platform pays)' }));
    const amount = screen.getByRole('textbox', { name: 'Amount to the customer' });
    await user().clear(amount);
    await user().type(amount, '25');
    await user().type(screen.getByRole('textbox', { name: 'Decision note (visible to both parties)' }), 'Credit for the trouble.');
    await user().click(screen.getByRole('button', { name: 'Decide: Goodwill credit (platform pays)' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST')).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.url).toMatch(/\/api\/v1\/console\/disputes\/dispute\/D1\/decision$/);
    expect(post.body).toEqual({ outcome: 'goodwill_credit', refundCents: 2500, note: 'Credit for the trouble.' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
  });

  it('is view only for a role without decide, and shows the api refusal', async () => {
    api(['support']);
    renderConsole('/disputes/dispute/D1');
    await screen.findByRole('heading', { level: 1, name: /DS-1188/ });
    expect((screen.getByRole('button', { name: 'Decide: Partial (50%)' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getAllByText('View only · Support').length).toBeGreaterThan(0);
  });

  it('a finance member co-signs a decision above $500', async () => {
    const calls = api(['finance'], c => (c.method === 'POST' && c.url.includes('cosign') && calls.filter(x => x.method === 'POST').length === 1
      ? { status: 409, body: { code: 'cosign_self', detail: 'Another person must co-sign this decision.' } } : undefined));
    renderConsole('/disputes/dispute/D2');
    expect(await screen.findByText('Waiting for a finance co-sign · Full refund to customer · $640.00')).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Co-sign' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Another person must co-sign this decision.');
    await user().click(screen.getByRole('button', { name: 'Co-sign' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/disputes/decisions/AD1/cosign')).length).toBe(2));
    expect(calls.filter(c => c.method === 'POST').at(-1)!.body).toEqual({ decision: 'approve' });
  });

  it('a refund case offers a full refund or a release only', async () => {
    api(['admin']);
    renderConsole('/disputes/refund/R1');
    expect(await screen.findByRole('heading', { level: 1, name: 'RF-2210 · Grocery run · 3 items spoiled' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Full refund to customer' })).toBeTruthy();
    expect(screen.queryByRole('radio', { name: 'Partial (50%)' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Decide: Full refund to customer' })).toBeTruthy();
  });

  it('is refused to roles that do not open it', async () => {
    const calls = api(['dispatch'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/disputes');
    expect(await screen.findByText('Not available in this role.')).toBeTruthy();
    expect(calls.some(c => c.url.includes('/api/v1/console/disputes'))).toBe(false);
  });

  it('speaks French', async () => {
    api(['admin']);
    const first = renderConsole('/disputes', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Litiges et remboursements' })).toBeTruthy();
    expect(await screen.findByText('Pour un agent', { selector: '.bo-kpi-label' })).toBeTruthy();
    first.unmount();
    renderConsole('/disputes/dispute/D1', { locale: 'fr' });
    expect(await screen.findByRole('radio', { name: 'Libérer au vendeur' })).toBeTruthy();
  });
});
