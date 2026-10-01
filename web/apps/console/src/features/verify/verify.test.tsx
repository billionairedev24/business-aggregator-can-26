import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const NOW = new Date('2026-09-08T18:00:00Z');
const check = (key: string, state: string, registry: string | null = null, type = 'kyc') => ({ id: `v-${key}`, key, type, registry, status: state === 'passed' ? 'verified' : 'submitted', state, reference: null, expiresAt: null });

const SABLE = {
  merchantId: 'M1', businessName: 'Sable & Soda Bar Co.', legalName: 'Sable and Soda Ltd.', type: 'provider', structure: 'corp_ab',
  categories: ['Events & bar'], province: 'AB', city: 'Calgary', status: 'pending', submittedAt: '2026-09-08T14:00:00Z',
  checks: [check('kyc', 'passed'), check('registry', 'passed', null, 'registry'), check('insurance', 'waiting', null, 'insurance')], risk: 'low', decision: null, decidedAt: null,
};
const QUICKFIX = {
  ...SABLE, merchantId: 'M2', businessName: 'QuickFix Auto', legalName: 'QuickFix Auto Inc.', categories: ['Mobile mechanic'], submittedAt: '2026-09-05T18:00:00Z',
  checks: [check('kyc', 'passed'), check('licence:AMVIC', 'review', 'AMVIC', 'licence')], risk: 'high',
};
const FRASER = { ...SABLE, merchantId: 'M3', businessName: 'Fraser Electric', province: 'BC', city: null, status: 'active', decision: 'approved', decidedAt: '2026-09-08T10:00:00Z' };
const QUEUE = { items: [QUICKFIX, SABLE, FRASER], pending: 2, medianDecisionHours: 33.6 };
const DETAIL = {
  application: QUICKFIX,
  owners: [{ checkId: 'OC1', principalName: 'Lee Cardinal', role: 'owner', ownershipPct: 100, status: 'review', nameMatch: 'mismatch', dobMatch: 'unavailable', lastError: null, attempts: 1, reviewNote: null, reviewedAt: null }],
  registryReviews: [{ id: 'RC1', checkKey: 'licence:AMVIC', source: 'manual', registry: 'AMVIC', queryNumber: '44812', expectedName: 'QuickFix Auto', outcome: 'manual', reasons: [], recordName: null, recordStatus: null, reviewState: 'open', reviewNote: null }],
  decisions: [],
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/verification/applications/M2')) return { body: DETAIL };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/verification/applications')) return { body: QUEUE };
    if (c.method === 'POST' && c.url.includes('/decision')) return { body: DETAIL };
    return undefined;
  });
}

describe('verification queue (S-79, design 03 verify)', () => {
  afterEach(() => vi.useRealTimers());
  const user = () => userEvent.setup({ delay: null });
  const setNow = () => vi.useFakeTimers({ now: NOW, toFake: ['Date'] });

  it('shows the design copy, the applications with their checks, region, risk, waiting and decision', async () => {
    setNow();
    api(['trust_safety']);
    renderConsole('/verification');
    expect(await screen.findByRole('heading', { level: 1, name: '2 applications · median 1.4 days · SLA 2' })).toBeTruthy();
    expect(screen.getByText('Seller verification')).toBeTruthy();
    expect(screen.getByText(/Automated checks run first \(KYC, registry, insurance OCR, sanctions\)\. Humans decide the rest\./)).toBeTruthy();
    const quick = screen.getByText('QuickFix Auto').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(quick).getByText('KYC ✓ · AMVIC ✗')).toBeTruthy();
    expect(within(quick).getByText('High · no licence')).toBeTruthy();
    expect(within(quick).getByText('3.0 d')).toBeTruthy();
    expect(within(quick).getByText('Pending')).toBeTruthy();
    const sable = screen.getByText('Sable & Soda Bar Co.').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(sable).getByText('KYC ✓ · Registry ✓ · Insurance ○')).toBeTruthy();
    expect(within(sable).getByText('4 h')).toBeTruthy();
    expect(within(sable).getByText('Alberta')).toBeTruthy();
    const fraser = screen.getByText('Fraser Electric').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(fraser).getByText('BC pilot')).toBeTruthy();
    expect(within(fraser).getByText('Approved · Registered')).toBeTruthy();
    expect(within(fraser).queryByRole('button', { name: /Approve/ })).toBeNull();
  });

  it('approves inline with the role view header, and shows the api refusal inline', async () => {
    let refuse = false;
    const calls = api(['trust_safety'], c => (c.method === 'POST' && refuse ? { status: 409, body: { code: 'reviews_open', detail: 'Decide the open registry and identity reviews first.' } } : undefined));
    renderConsole('/verification');
    const sable = (await screen.findByText('Sable & Soda Bar Co.')).closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(sable).getByRole('button', { name: /Approve/ }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/verification/applications/M1/decision'))).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ decision: 'approve' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
    expect(await screen.findByText('Sable & Soda Bar Co. approved.')).toBeTruthy();

    refuse = true;
    const quick = screen.getByText('QuickFix Auto').closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(quick).getByRole('button', { name: /Approve/ }));
    expect((await screen.findByRole('alert')).textContent).toBe('Decide the open registry and identity reviews first.');
  });

  it('requests info with the checks to redo and a note, showing the api messages', async () => {
    let first = true;
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && first) { first = false; return { status: 422, body: { errors: [{ field: 'note', rule: 'required', message: 'Tell the business what to fix.' }] } }; }
      return undefined;
    });
    renderConsole('/verification');
    const quick = (await screen.findByText('QuickFix Auto')).closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(quick).getByRole('button', { name: /Request info/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Request info from QuickFix Auto' });
    expect((within(dialog).getByRole('checkbox', { name: 'AMVIC' }) as HTMLInputElement).checked).toBe(true);
    expect((within(dialog).getByRole('checkbox', { name: 'KYC' }) as HTMLInputElement).checked).toBe(false);
    await user().click(within(dialog).getByRole('button', { name: 'Send request' }));
    expect(await within(dialog).findByText('Tell the business what to fix.')).toBeTruthy();
    await user().type(within(dialog).getByRole('textbox', { name: 'Note to the business' }), 'Upload your AMVIC licence.');
    await user().click(within(dialog).getByRole('button', { name: 'Send request' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(calls.filter(c => c.method === 'POST').at(-1)!.body).toEqual({ decision: 'request_info', checkKeys: ['licence:AMVIC'], note: 'Upload your AMVIC licence.' });
    expect(screen.getByText('Request sent to QuickFix Auto.')).toBeTruthy();
  });

  it('opens an application with its identity and registry reviews, decided by the agent', async () => {
    const calls = api(['admin']);
    renderConsole('/verification?application=M2');
    const panel = (await screen.findByRole('heading', { level: 2, name: 'QuickFix Auto · QuickFix Auto Inc.' })).closest('section') as HTMLElement;
    expect(within(panel).getByText('Name differs from the application')).toBeTruthy();
    expect(within(panel).getByText('AMVIC 44812 · manual')).toBeTruthy();
    const owner = within(panel).getByText(/Lee Cardinal · Owner/).closest('li') as HTMLElement;
    await user().click(within(owner).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/applications/M2/identity-reviews/OC1/decision'))).toBe(true));
    const registry = within(panel).getByText('AMVIC 44812 · manual').closest('li') as HTMLElement;
    await user().click(within(registry).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/registry-reviews/RC1/decision') && (c.body as { decision: string }).decision === 'reject')).toBe(true));
  });

  it('filters by province from the region model', async () => {
    const calls = api(['admin']);
    renderConsole('/verification');
    await screen.findByRole('heading', { level: 1 });
    await user().selectOptions(screen.getByRole('combobox', { name: 'Province' }), 'BC');
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/verification/applications?province=BC'))).toBe(true));
  });

  it('is refused to roles that do not open it (the denied banner)', async () => {
    const calls = api(['support'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/verification');
    expect(await screen.findByText('Not available in this role.')).toBeTruthy();
    expect(calls.some(c => c.url.includes('/verification/applications'))).toBe(false);
  });

  it('speaks French', async () => {
    api(['admin']);
    renderConsole('/verification', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: '2 demandes · médiane 1,4 jours · délai 2' })).toBeTruthy();
    expect(screen.getByText('Vérification des vendeurs')).toBeTruthy();
    expect(screen.getAllByText('Élevé · sans permis').length).toBeGreaterThan(0);
  });
});
