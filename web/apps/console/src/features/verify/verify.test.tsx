import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, staffApi, type Call, screenReady, SESSION } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const NOW = new Date('2026-09-08T18:00:00Z');
const check = (key: string, state: string, registry: string | null = null, type = 'kyc') => ({ id: `v-${key}`, key, type, registry, status: state === 'passed' ? 'verified' : 'submitted', state, reference: null, expiresAt: null });

const SABLE = {
  merchantId: 'M1', businessName: 'Sable & Soda Bar Co.', legalName: 'Sable and Soda Ltd.', type: 'provider', structure: 'corp_prov',
  categories: ['Events & bar'], province: 'AB', city: 'Calgary', status: 'pending', submittedAt: '2026-09-08T14:00:00Z',
  checks: [check('kyc', 'passed'), check('registry', 'passed', null, 'registry'), check('insurance', 'waiting', null, 'insurance')], risk: 'low', decision: null, decidedAt: null,
  stage: 'decision' as string | null,
  review: { id: 'R1', recommendation: 'approve', checkKeys: [] as string[], note: 'Insurance certificate matches.', reviewedBy: 'U2', reviewerName: 'Lena Park', reviewedAt: '2026-09-08T16:00:00Z', decisionId: null } as Record<string, unknown> | null,
};
const QUICKFIX = {
  ...SABLE, merchantId: 'M2', businessName: 'QuickFix Auto', legalName: 'QuickFix Auto Inc.', categories: ['Mobile mechanic'], submittedAt: '2026-09-05T18:00:00Z',
  checks: [check('kyc', 'passed'), check('licence:AMVIC', 'review', 'AMVIC', 'licence')], risk: 'high', stage: 'review', review: null,
};
const FRASER = { ...SABLE, merchantId: 'M3', businessName: 'Fraser Electric', province: 'BC', city: null, status: 'active', decision: 'approved', decidedAt: '2026-09-08T10:00:00Z', stage: null, review: null };
const QUEUE = { items: [QUICKFIX, SABLE, FRASER], pending: 2, medianDecisionHours: 33.6, fourEyes: true };
const DETAIL = {
  application: QUICKFIX,
  owners: [{ checkId: 'OC1', principalName: 'Lee Cardinal', role: 'owner', ownershipPct: 100, status: 'review', nameMatch: 'mismatch', dobMatch: 'unavailable', lastError: null, attempts: 1, reviewNote: null, reviewedAt: null }],
  registryReviews: [{ id: 'RC1', checkKey: 'licence:AMVIC', source: 'manual', registry: 'AMVIC', queryNumber: '44812', expectedName: 'QuickFix Auto', outcome: 'manual', reasons: [], recordName: null, recordStatus: null, reviewState: 'open', reviewNote: null }],
  reviews: [], decisions: [], fourEyes: true,
};
/** Sable at stage 2: reviewed by `review.reviewedBy` with `review.recommendation`. */
const sableDetail = (review: Record<string, unknown> = {}) => {
  const r = { ...SABLE.review, ...review };
  return { application: { ...SABLE, review: r }, owners: [], registryReviews: [], reviews: [r], decisions: [], fourEyes: true };
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined, sable = sableDetail()) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/verification/applications/M2')) return { body: DETAIL };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/verification/applications/M1')) return { body: sable };
    if (c.method === 'POST' && c.url.includes('/review')) return { body: DETAIL };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/verification/applications')) return { body: QUEUE };
    if (c.method === 'POST' && c.url.includes('/decision')) return { body: DETAIL };
    return undefined;
  });
}

describe('verification queue (S-79, design 03 verify)', () => {
  afterEach(() => vi.useRealTimers());
  const user = () => userEvent.setup({ delay: null });
  const setNow = () => vi.useFakeTimers({ now: NOW, toFake: ['Date'] });

  it('shows Applications: the stages, the applications with their checks, region, risk, waiting and stage', async () => {
    setNow();
    api(['trust_safety']);
    renderConsole('/verification');
    expect((await screenReady()).textContent).toBe('Applications');
    expect(screen.getByText('2 waiting · median decision 1.4 days · SLA 2 days')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(within(screen.getByRole('tablist', { name: 'Application queues' })).getAllByRole('tab').map(t => t.textContent)).toEqual(['Businesses', 'Registry checks', 'Listings', 'Licences', 'Age checks']);
    expect(within(screen.getByRole('tablist', { name: 'Applications by stage' })).getAllByRole('tab').map(t => t.textContent)).toEqual(['Stage 1 · Review1', 'Stage 2 · Decision1', 'Info requested0', 'Approved1', 'All3']);
    const kpis = within(screen.getByRole('list', { name: 'Applications by stage' }));
    expect(kpis.getByText('Stage 1 · Review').nextElementSibling?.textContent).toBe('1');
    expect(kpis.getByText('Stage 2 · Decision').nextElementSibling?.textContent).toBe('1');
    expect(kpis.getByText('Approved').nextElementSibling?.textContent).toBe('1');
    const quick = screen.getByText('QuickFix Auto').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(quick).getByText('KYC ✓ · AMVIC ✗')).toBeTruthy();
    expect(within(quick).getByText('High · no licence')).toBeTruthy();
    expect(within(quick).getByText('3.0 d')).toBeTruthy();
    expect(within(quick).getByText('1 · Review').className).toContain('nl-status-info');
    expect(within(quick).getByText('High · no licence').className).toContain('tag-danger');
    const sable = screen.getByText('Sable & Soda Bar Co.').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(sable).getByText('KYC ✓ · Registry ✓ · Insurance ○')).toBeTruthy();
    expect(within(sable).getByText('4 h')).toBeTruthy();
    expect(within(sable).getByText('Alberta')).toBeTruthy();
    expect(within(sable).getByText('2 · Decision').className).toContain('nl-status-warning');
    const fraser = screen.getByText('Fraser Electric').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(fraser).getByText('BC pilot')).toBeTruthy();
    expect(within(fraser).getByText('Approved').className).toContain('nl-status-success');
    expect(within(fraser).queryByRole('button', { name: /Approve|Review|Decide/ })).toBeNull();
    // stage 1 offers "Review", stage 2 "Decide" (two-person review)
    expect(within(quick).getByRole('button', { name: /Review/ })).toBeTruthy();
    expect(within(quick).queryByRole('button', { name: /Decide/ })).toBeNull();
    expect(within(sable).getByRole('button', { name: /Decide/ })).toBeTruthy();
    expect(within(sable).queryByRole('button', { name: /Review/ })).toBeNull();
  });

  it('stage 2: "Decide" opens the page with the reviewer and recommendation; approving sends the role view header', async () => {
    let refuse = false;
    const calls = api(['trust_safety'], c => (c.method === 'POST' && refuse ? { status: 409, body: { code: 'reviews_open', detail: 'Decide the open registry and identity reviews first.' } } : undefined));
    renderConsole('/verification');
    const sable = (await screen.findByText('Sable & Soda Bar Co.')).closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(sable).getByRole('button', { name: /Decide/ }));
    await screen.findByRole('heading', { level: 1, name: 'Sable & Soda Bar Co.' });
    expect(screen.getByText('2 · Decision', { selector: '.nl-status' })).toBeTruthy();
    const panel = document.body;
    expect((await within(panel).findByText('Reviewer')).nextElementSibling?.textContent).toBe('Lena Park');
    expect(within(panel).getByText('Recommendation').nextElementSibling?.textContent).toBe('Approve');
    expect(within(panel).getByText('Note for the approver').nextElementSibling?.textContent).toBe('Insurance certificate matches.');
    expect(within(panel).getByRole('list', { name: 'Review stages' }).querySelector('[aria-current="step"]')?.textContent).toContain('Decision');
    await expectNoAxeViolations(document.body);
    await user().click(within(panel).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/verification/applications/M1/decision'))).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ decision: 'approve' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
    expect(await screen.findByText('Sable & Soda Bar Co. approved.')).toBeTruthy();

    refuse = true;
    await user().click(within(panel).getByRole('button', { name: 'Approve' }));
    expect((await within(panel).findByRole('alert')).textContent).toBe('Decide the open registry and identity reviews first.');
  });

  it('disables Approve and Request info, with the reason, for the person who reviewed it', async () => {
    const calls = api(['admin'], undefined, sableDetail({ reviewedBy: SESSION.user.id, reviewerName: 'Priya Natarajan' }));
    renderConsole('/verification/M1');
    await screen.findByRole('heading', { level: 1, name: 'Sable & Soda Bar Co.' });
    const panel = document.body;
    const approve = await within(panel).findByRole('button', { name: 'Approve' }) as HTMLButtonElement;
    const info = within(panel).getByRole('button', { name: 'Request info…' }) as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
    expect(info.disabled).toBe(true);
    expect(within(panel).getByText('You reviewed this application, so someone else has to decide it.')).toBeTruthy();
    await expectNoAxeViolations(document.body);
    await user().click(approve);
    expect(calls.some(c => c.method === 'POST')).toBe(false);
  });

  it('approving against a reject recommendation asks why, and sends the note', async () => {
    let first = true;
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && first) { first = false; return { status: 422, body: { errors: [{ field: 'note', rule: 'required', message: 'Say why you approve against the review.' }] } }; }
      return undefined;
    }, sableDetail({ recommendation: 'reject', note: 'Address is a parking lot.' }));
    renderConsole('/verification/M1');
    await screen.findByRole('heading', { level: 1, name: 'Sable & Soda Bar Co.' });
    const panel = document.body;
    await user().click(await within(panel).findByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve Sable & Soda Bar Co. against the review' });
    expect(within(dialog).getByText('Lena Park recommended “Reject”. Say why you approve anyway.')).toBeTruthy();
    await user().click(within(dialog).getByRole('button', { name: 'Approve' }));
    expect(await within(dialog).findByText('Say why you approve against the review.')).toBeTruthy();
    await user().type(within(dialog).getByRole('textbox', { name: 'Why you approve' }), 'Called them: a market stall.');
    await user().click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST').at(-1)!.body).toEqual({ decision: 'approve', note: 'Called them: a market stall.' }));
  });

  it('stage 1: reviews with a recommendation and a note for the approver, showing the api messages', async () => {
    let first = true;
    const calls = api(['trust_safety'], c => {
      if (c.method === 'POST' && first) { first = false; return { status: 422, body: { errors: [{ field: 'note', rule: 'required', message: 'Say why you recommend rejecting it.' }] } }; }
      return undefined;
    });
    renderConsole('/verification');
    const quick = (await screen.findByText('QuickFix Auto')).closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(quick).getByRole('button', { name: /Review/ }));
    await screen.findByRole('heading', { level: 1, name: 'QuickFix Auto' });
    const panel = document.body;
    expect(await within(panel).findByText('Not reviewed yet. A reviewer recommends; someone else then decides.')).toBeTruthy();
    expect(within(panel).queryByRole('button', { name: 'Request info…' })).toBeNull(); // stage 2's, not offered yet
    await user().click(within(panel).getByRole('button', { name: 'Review…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Review QuickFix Auto' });
    await expectNoAxeViolations(document.body);
    const radios = within(within(dialog).getByRole('radiogroup', { name: 'Recommendation' })).getAllByRole('radio');
    expect(radios.map(r => r.getAttribute('aria-checked'))).toEqual(['true', 'false', 'false']);
    await user().click(within(dialog).getByRole('radio', { name: /Reject/ }));
    await user().click(within(dialog).getByRole('button', { name: 'Send review' }));
    expect(await within(dialog).findByText('Say why you recommend rejecting it.')).toBeTruthy();
    await user().type(within(dialog).getByRole('textbox', { name: 'Note for the approver' }), 'Licence number belongs to another shop.');
    await user().click(within(dialog).getByRole('button', { name: 'Send review' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Review QuickFix Auto' })).toBeNull());
    const post = calls.filter(c => c.method === 'POST').at(-1)!;
    expect(post.url).toMatch(/\/applications\/M2\/review$/);
    expect(post.body).toEqual({ recommendation: 'reject', note: 'Licence number belongs to another shop.' });
    expect(await screen.findByText('QuickFix Auto reviewed. It now waits for someone else’s decision.')).toBeTruthy();
  });

  it('stage 1: recommending request info sends the checks to redo', async () => {
    const calls = api(['trust_safety']);
    renderConsole('/verification/M2');
    await screen.findByRole('heading', { level: 1, name: 'QuickFix Auto' });
    const panel = document.body;
    await user().click(await within(panel).findByRole('button', { name: 'Review…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Review QuickFix Auto' });
    await user().click(within(dialog).getByRole('radio', { name: /Request info/ }));
    expect((within(dialog).getByRole('checkbox', { name: 'AMVIC' }) as HTMLInputElement).checked).toBe(true);
    await user().type(within(dialog).getByRole('textbox', { name: 'Note for the approver' }), 'Licence missing.');
    await user().click(within(dialog).getByRole('button', { name: 'Send review' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST').at(-1)?.body).toEqual({ recommendation: 'request_info', checkKeys: ['licence:AMVIC'], note: 'Licence missing.' }));
  });

  it('stage 2: requests info prefilled with the reviewer’s checks and note, showing the api messages', async () => {
    let first = true;
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && first) { first = false; return { status: 422, body: { errors: [{ field: 'note', rule: 'required', message: 'Tell the business what to fix.' }] } }; }
      return undefined;
    }, sableDetail({ recommendation: 'request_info', checkKeys: ['insurance'], note: '' }));
    renderConsole('/verification/M1');
    await screen.findByRole('heading', { level: 1, name: 'Sable & Soda Bar Co.' });
    const panel = document.body;
    await user().click(await within(panel).findByRole('button', { name: 'Request info…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Request info from Sable & Soda Bar Co.' });
    expect((within(dialog).getByRole('checkbox', { name: 'Insurance' }) as HTMLInputElement).checked).toBe(true);
    expect((within(dialog).getByRole('checkbox', { name: 'KYC' }) as HTMLInputElement).checked).toBe(false);
    await user().click(within(dialog).getByRole('button', { name: 'Send request' }));
    expect(await within(dialog).findByText('Tell the business what to fix.')).toBeTruthy();
    await user().type(within(dialog).getByRole('textbox', { name: 'Note to the business' }), 'Upload your insurance certificate.');
    await user().click(within(dialog).getByRole('button', { name: 'Send request' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Request info/ })).toBeNull());
    expect(calls.filter(c => c.method === 'POST').at(-1)!.body).toEqual({ decision: 'request_info', checkKeys: ['insurance'], note: 'Upload your insurance certificate.' });
    expect(await screen.findByText(/^Request sent to Sable & Soda Bar Co\./)).toBeTruthy();
  });

  it('opens an application’s page: stages, every submitted field, identity and registry reviews, decided by the agent', async () => {
    const calls = api(['admin']);
    renderConsole('/verification/M2');
    await screen.findByRole('heading', { level: 1, name: 'QuickFix Auto' });
    const panel = document.body;
    const current = within(panel).getByRole('list', { name: 'Review stages' }).querySelector('[aria-current="step"]');
    expect(current?.textContent).toContain('Review');
    const facts: [string, string][] = [['Legal name', 'QuickFix Auto Inc.'], ['Structure', 'Provincial corporation'], ['Categories', 'Mobile mechanic'], ['Location', 'Calgary, Alberta'], ['Risk', 'High · no licence']];
    for (const [k, v] of facts) {
      expect(within(panel).getAllByText(k).at(-1)!.nextElementSibling?.textContent, k).toBe(v);
    }
    expect(within(panel).getByRole('button', { name: 'Review…' })).toBeTruthy();
    await user().click(within(panel).getByRole('tab', { name: /Checks & identity/ }));
    expect(within(panel).getByText('Name differs from the application')).toBeTruthy();
    expect(within(panel).getByText('AMVIC 44812 · manual')).toBeTruthy();
    const owner = within(panel).getByText(/Lee Cardinal · Owner/).closest('li') as HTMLElement;
    await user().click(within(owner).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/applications/M2/identity-reviews/OC1/decision'))).toBe(true));
    const registry = within(panel).getByText('AMVIC 44812 · manual').closest('li') as HTMLElement;
    await user().click(within(registry).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/registry-reviews/RC1/decision') && (c.body as { decision: string }).decision === 'reject')).toBe(true));
    expect(calls.some(c => c.url.endsWith('/applications/M2/decision'))).toBe(false);
  });

  it('shows the page view only to a role without the verify action', async () => {
    api(['admin'], c => (c.url.endsWith('/api/v1/console/me') ? { body: { userId: 'U1', roles: [{ role: 'admin', screens: ['overview', 'verify', 'vetting'], actions: [] }] } } : undefined));
    renderConsole('/verification/M2');
    await screen.findByRole('heading', { level: 1, name: 'QuickFix Auto' });
    const panel = document.body;
    expect(await within(panel).findByText('View only · Admin')).toBeTruthy();
    expect(within(panel).queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(within(panel).queryByRole('button', { name: 'Review…' })).toBeNull();
  });

  it('follows the top bar’s scope, and filters by stage and high risk', async () => {
    const calls = api(['admin']);
    const { router } = renderConsole('/verification?province=BC');
    await screenReady();
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/verification/applications?province=BC'))).toBe(true));
    await user().click(screen.getByRole('button', { name: 'High risk' }));
    await waitFor(() => expect(screen.queryByText('Sable & Soda Bar Co.')).toBeNull());
    expect(screen.getByText('QuickFix Auto')).toBeTruthy();
    await user().click(screen.getByRole('tab', { name: /^Approved/ }));
    expect(router.state.location.search).toMatchObject({ risk: true, stage: 'approved' });
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
    expect(await screen.findByRole('heading', { level: 1, name: 'Demandes' })).toBeTruthy();
    expect(await screen.findByText('2 en attente · décision médiane 1,4 jours · délai 2 jours')).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Entreprises' })).toBeTruthy();
    expect(screen.getAllByText('Élevé · sans permis').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: /Examiner/ }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: /Trancher/ }).length).toBeGreaterThan(0);
  });
});
