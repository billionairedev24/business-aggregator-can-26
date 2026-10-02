import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
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

  it('shows the design copy, the cases and both parties with the evidence', async () => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date'] });
    api(['trust_safety']);
    renderConsole('/disputes');
    expect(await screen.findByRole('heading', { level: 1, name: '3 disputes for an agent · 61 refund requests in the seller window · 214 closed this week' })).toBeTruthy();
    expect(screen.getByText(/Every customer refund request opens a case\. The seller has 24 h to accept or respond/)).toBeTruthy();
    const list = screen.getByRole('list', { name: 'Cases' });
    expect(within(list).getByText('DS-1188')).toBeTruthy();
    expect(within(list).getByText('$160')).toBeTruthy();
    expect(within(list).getByText('opened 2.0 d ago')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 2, name: 'DS-1188 · Pre-purchase inspection · missed leak' })).toBeTruthy();
    expect(screen.getByText('Amara Osei → Prairie Wrench · $160.00 in escrow · opened 2.0 d ago')).toBeTruthy();
    expect(await screen.findByText('Quality 91')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('1 prior · 1 won')).toBeTruthy();
    expect(screen.getByText('0 prior disputes')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'job-photos.zip' }).getAttribute('href')).toBe('/api/v1/console/disputes/dispute/D1/evidence/E1');
    for (const o of ['Full refund to customer', 'Partial (50%)', 'Release to seller', 'Goodwill credit (platform pays)']) expect(screen.getByRole('button', { name: o })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Decide: Partial (50%)' })).toBeTruthy();
    expect(screen.getByText('Both parties get the note; seller may appeal once.')).toBeTruthy();
  });

  it('decides with the chosen outcome, amount and note, and the role header', async () => {
    const calls = api(['trust_safety']);
    renderConsole('/disputes');
    await screen.findByRole('heading', { level: 2 });
    await user().click(screen.getByRole('button', { name: 'Goodwill credit (platform pays)' }));
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
    renderConsole('/disputes');
    await screen.findByRole('heading', { level: 2 });
    expect((screen.getByRole('button', { name: 'Decide: Partial (50%)' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('View only · Support')).toBeTruthy();
  });

  it('a finance member co-signs a decision above $500', async () => {
    const calls = api(['finance'], c => (c.method === 'POST' && c.url.includes('cosign') && calls.filter(x => x.method === 'POST').length === 1
      ? { status: 409, body: { code: 'cosign_self', detail: 'Another person must co-sign this decision.' } } : undefined));
    renderConsole('/disputes?case=dispute:D2');
    expect(await screen.findByText('Waiting for a finance co-sign · Full refund to customer · $640.00')).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Co-sign' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Another person must co-sign this decision.');
    await user().click(screen.getByRole('button', { name: 'Co-sign' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/disputes/decisions/AD1/cosign')).length).toBe(2));
    expect(calls.filter(c => c.method === 'POST').at(-1)!.body).toEqual({ decision: 'approve' });
  });

  it('a refund case offers a full refund or a release only', async () => {
    api(['admin']);
    renderConsole('/disputes?case=refund:R1');
    expect(await screen.findByRole('heading', { level: 2, name: 'RF-2210 · Grocery run · 3 items spoiled' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Full refund to customer' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Partial (50%)' })).toBeNull();
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
    renderConsole('/disputes', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: /^3 litiges pour un agent · 61 demandes de remboursement dans le délai du vendeur · 214 fermés cette semaine$/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Libérer au vendeur' })).toBeTruthy();
  });
});
