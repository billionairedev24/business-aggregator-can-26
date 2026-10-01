import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const base = { merchantId: 'M1', province: 'AB', category: null, medianCents: null, deviationPct: null, regulator: null, flags: [], revetReasons: [], trustFlags: [], submittedAt: '2026-09-08T16:00:00Z', decidedAt: null, reasons: [], note: null };
const BRAKES = { ...base, id: 'L1', kind: 'service', businessName: 'QuickFix Auto', name: 'Full brake job', priceCents: 4900, medianCents: 18000, deviationPct: -73, flags: ['price_outlier'], state: 'pending' };
const CLAIM = { ...base, id: 'L2', kind: 'service', businessName: 'Bow River Mechanics', name: 'Guaranteed to pass inspection', priceCents: 12000, state: 'pending',
  trustFlags: [{ flagId: 'F1', rule: 'ai_screening', source: 'ai', explanation: 'Guarantee language without terms', categories: ['misleading_claim'] }] };
const PANEL = { ...base, id: 'L3', kind: 'service', businessName: 'Handy Hal', name: 'Electrical panel upgrade', priceCents: 120000, flags: ['missing_licence'], regulator: 'Safety Codes', revetReasons: ['category'], state: 'pending' };
const PHO = { ...base, id: 'D1', kind: 'dish', businessName: 'Pho Dau Bo', name: 'Wagyu pho', priceCents: 3400, medianCents: 2000, deviationPct: 70, flags: ['price_check'], state: 'pending' };
const BREAD = { ...base, id: 'L4', kind: 'product', businessName: 'Glenmore Bakery', name: 'Sourdough', priceCents: 750, flags: ['duplicate_image'], state: 'approved', decidedAt: '2026-09-08T10:00:00Z' };
const QUEUE = { autoApproved: 2318, flagged: 4, items: [BRAKES, CLAIM, PANEL, PHO, BREAD] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/vetting')) return { body: QUEUE };
    if (c.method === 'POST' && c.url.includes('/listings/L2/')) return { body: { ...CLAIM, state: 'rejected', reasons: ['misleading'] } };
    if (c.method === 'POST' && c.url.includes('/decision')) return { body: { ...BRAKES, state: 'approved' } };
    return undefined;
  });
}
const row = (name: string) => screen.getByText(name).closest('tr, .nl-dt-card') as HTMLElement;
const user = () => userEvent.setup({ delay: null });

describe('listing vetting (S-92, design 03 vetting)', () => {
  it('shows the design copy, each rule tripped and its evidence', async () => {
    api(['trust_safety']);
    renderConsole('/vetting');
    expect(await screen.findByRole('heading', { level: 1, name: '2,318 listings auto-approved this week · 4 flagged for a human' })).toBeTruthy();
    expect(screen.getByText('Listing vetting', { selector: '.nl-q-kicker' })).toBeTruthy();
    expect(screen.getByText(/Rules: banned categories, price ±60% from category median/)).toBeTruthy();
    expect(within(row('Full brake job')).getByText('Price −73% vs median')).toBeTruthy();
    expect(within(row('Full brake job')).getByText('Median $180')).toBeTruthy();
    expect(within(row('Full brake job')).getByText('$49.00')).toBeTruthy();
    expect(within(row('Guaranteed to pass inspection')).getByText('Restricted claim')).toBeTruthy();
    expect(within(row('Guaranteed to pass inspection')).getByText('Guarantee language without terms')).toBeTruthy();
    expect(within(row('Electrical panel upgrade')).getByText('Missing licence')).toBeTruthy();
    expect(within(row('Electrical panel upgrade')).getByText('Regulated · no Safety Codes licence on file · Changed: category')).toBeTruthy();
    expect(within(row('Wagyu pho')).getByText('Price +70% vs median')).toBeTruthy();
    expect(within(row('Wagyu pho')).getByText('Cuisine median $20')).toBeTruthy();
    expect(within(row('Sourdough')).getByText('Approved')).toBeTruthy();
    expect(within(row('Sourdough')).queryByRole('button', { name: /Approve/ })).toBeNull();
  });

  it('approves inline (dishes to their own endpoint) with the role header', async () => {
    const calls = api(['admin']);
    renderConsole('/vetting');
    await screen.findByRole('heading', { level: 1 });
    await user().click(within(row('Wagyu pho')).getByRole('button', { name: /Approve/ }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/vetting/dishes/D1/decision'))).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ decision: 'approve' });
    expect(post.headers['X-Console-Role']).toBe('admin');
    expect(await screen.findByText('“Wagyu pho” approved.')).toBeTruthy();
  });

  it('rejects with reasons and a note, showing the api messages', async () => {
    let first = true;
    const calls = api(['trust_safety'], c => {
      if (c.method === 'POST' && first) { first = false; return { status: 422, body: { errors: [{ field: 'reasons', rule: 'required', message: 'Choose why the listing is rejected.' }] } }; }
      return undefined;
    });
    renderConsole('/vetting');
    await screen.findByRole('heading', { level: 1 });
    await user().click(within(row('Guaranteed to pass inspection')).getByRole('button', { name: /Reject/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject “Guaranteed to pass inspection”' });
    await user().click(within(dialog).getByRole('button', { name: 'Reject listing' }));
    expect(await within(dialog).findByText('Choose why the listing is rejected.')).toBeTruthy();
    await user().click(within(dialog).getByRole('checkbox', { name: 'Misleading claim' }));
    await user().type(within(dialog).getByRole('textbox', { name: 'Note to the seller (optional)' }), 'Add the guarantee terms.');
    await user().click(within(dialog).getByRole('button', { name: 'Reject listing' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(calls.filter(c => c.method === 'POST').at(-1)).toMatchObject({ url: expect.stringMatching(/\/api\/v1\/console\/vetting\/listings\/L2\/decision$/), body: { decision: 'reject', reasons: ['misleading'], note: 'Add the guarantee terms.' } });
    expect(screen.getByText('“Guaranteed to pass inspection” rejected · seller notified.')).toBeTruthy();
  });

  it('shows the api refusal inline', async () => {
    api(['trust_safety'], c => (c.method === 'POST' ? { status: 409, body: { code: 'not_in_review', detail: "This listing isn't waiting for a decision." } } : undefined));
    renderConsole('/vetting');
    await screen.findByRole('heading', { level: 1 });
    await user().click(within(row('Full brake job')).getByRole('button', { name: /Approve/ }));
    expect((await screen.findByRole('alert')).textContent).toBe("This listing isn't waiting for a decision.");
  });

  it('filters by province from the region model', async () => {
    const calls = api(['admin']);
    renderConsole('/vetting');
    await screen.findByRole('heading', { level: 1 });
    await user().selectOptions(screen.getByRole('combobox', { name: 'Province' }), 'AB');
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/vetting?province=AB'))).toBe(true));
  });

  it('is refused to roles that do not open it', async () => {
    const calls = api(['finance'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/vetting');
    expect(await screen.findByText('Not available in this role.')).toBeTruthy();
    expect(calls.some(c => c.url.includes('/api/v1/console/vetting'))).toBe(false);
  });

  it('speaks French', async () => {
    api(['admin']);
    renderConsole('/vetting', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: /^2\s318 annonces approuvées automatiquement cette semaine · 4 signalées pour un humain$/ })).toBeTruthy();
    expect(within(row('Sourdough')).getByText('Approuvée')).toBeTruthy();
    expect(within(row('Full brake job')).getByText('Prix −73 % par rapport à la médiane')).toBeTruthy();
  });
});
