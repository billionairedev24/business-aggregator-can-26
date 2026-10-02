import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, SESSION, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const NOW = new Date('2026-09-08T18:00:00Z');
const ME = SESSION.user.id;
const ticket = (o: Record<string, unknown>) => ({
  requesterType: 'provider', merchantId: 'M1', priority: 'normal', state: 'new', agentId: null, agentName: null,
  slaDueAt: '2026-09-08T19:00:00Z', lang: 'en', escalated: false, ...o,
});
const HD4471 = ticket({ id: 'T1', code: 'HD-4471', requesterName: 'Prairie Wrench', subject: 'WCB letter uploaded — restore instant book?', priority: 'priority', createdAt: '2026-09-08T17:20:00Z' });
const HD4472 = ticket({ id: 'T2', code: 'HD-4472', requesterType: 'customer', merchantId: null, requesterName: 'R. Diaz', subject: 'Order NL-48102 arrived late', priority: 'urgent', createdAt: '2026-09-08T17:50:00Z', lang: 'fr', agentId: 'U9', agentName: 'Dev K.', state: 'in_progress' });
const QUEUE = {
  kpis: { open: 23, urgent: 3, medianFirstReplyMinutes: 38, slaAtRisk: 2, resolvedWithoutEscalation: 0.94, csat: 4.7, frenchShare: 0.61 },
  counts: { all: 23, urgent: 3, unassigned: 6, mine: 5, sla_risk: 2, providers: 12, sellers: 4, kitchens: 3, customers: 4 },
  items: [HD4471, HD4472],
};
const detail = (tk: typeof HD4471, o: Record<string, unknown> = {}) => ({
  ticket: tk,
  context: tk.requesterType === 'customer' ? { portal: 'consumer', refunds: ['RF1'] } : { portal: 'provider', tier: 'master', role: 'owner' },
  refLabel: tk.requesterType === 'customer' ? 'Order NL-48102' : 'Document · WCB clearance',
  notes: [{ by: tk.requesterType === 'customer' ? 'customer' : 'merchant', name: null, body: 'Can instant book be turned back on today?', at: '2026-09-08T17:20:00Z' }],
  refundRequests: [],
  ...o,
});
const MACROS = { items: [
  { id: 'MC1', key: 'support.payout_hold', title: { en: 'Payout hold — policy explanation', fr: 'Retenue de versement — explication de la politique' },
    body: { en: 'After a dispute payouts are held for 7 days.', fr: 'Après un litige, les versements sont retenus 7 jours.' }, updatedAt: null },
] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/support/macros')) return { body: MACROS };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/support/tickets/T1')) return { body: detail(HD4471) };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/support/tickets/T2')) return { body: detail(HD4472) };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/support/tickets')) return { body: QUEUE };
    if (c.method === 'POST' && c.url.includes('/api/v1/console/support/macros')) return { status: 201, body: { ...MACROS.items[0], id: 'MC2', key: 'support.thanks' } };
    if (c.method === 'POST') return { body: detail(HD4471) };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });
const rowOf = (text: string) => screen.getAllByText(text)[0]!.closest('tr, .nl-dt-card') as HTMLElement;

describe('support desk (S-83, design 03 support)', () => {
  afterEach(() => vi.useRealTimers());

  it('shows the design copy, KPIs, chips with counts, the queue and the requester context', async () => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date'] });
    api(['support']);
    renderConsole('/support');
    expect(await screen.findByRole('heading', { level: 1, name: '23 open · 3 urgent · median first reply 38 min' })).toBeTruthy();
    expect(screen.getByText('Support desk · agents')).toBeTruthy();
    for (const k of ['open · 3 urgent', 'median first reply · SLA 1 h Master / 4 h', 'SLA at risk', 'resolved without escalation · 30 d', 'CSAT · 30 d', 'in French']) expect(screen.getByText(k)).toBeTruthy();
    expect(screen.getByText('94%')).toBeTruthy();
    expect(screen.getByText('4.7')).toBeTruthy();
    const chips = screen.getByRole('group', { name: 'Filter cases' });
    for (const c of ['All · 23', 'Urgent · 3', 'Unassigned · 6', 'Mine · 5', 'SLA at risk · 2', 'Providers', 'Kitchens', 'Customers']) expect(within(chips).getByRole('button', { name: c })).toBeTruthy();
    const row = rowOf('HD-4472');
    expect(within(row).getByText('R. Diaz · Order NL-48102 arrived late')).toBeTruthy();
    expect(within(row).getByText('Dev K.')).toBeTruthy();
    expect(within(row).getByText('Urgent')).toBeTruthy();
    expect(within(row).getByText('10 min')).toBeTruthy();
    expect(within(rowOf('HD-4471')).getByText('Unassigned')).toBeTruthy();
    expect(await screen.findByRole('heading', { level: 2, name: 'Prairie Wrench · master' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Portal: provider · tier master · role owner')).toBeTruthy();
    expect(screen.getByText('HD-4471 · Unassigned · Document · WCB clearance')).toBeTruthy();
    expect(screen.getByText('Can instant book be turned back on today?')).toBeTruthy();
    expect(screen.getByText(/Every action is logged to the audit trail and visible to the requester as a case update\./)).toBeTruthy();
  });

  it('filters by chip through the api', async () => {
    const calls = api(['support']);
    const { router } = renderConsole('/support');
    await user().click(await screen.findByRole('button', { name: 'Urgent · 3' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ filter: 'urgent' }));
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/support/tickets?filter=urgent'))).toBe(true));
  });

  it('inserts a macro in the requester’s language and sends it, keeping the case open or resolving it', async () => {
    const calls = api(['support']);
    renderConsole('/support?ticket=T2');
    expect(await screen.findByRole('heading', { level: 2, name: 'R. Diaz' })).toBeTruthy();
    expect(screen.getByText('Requester writes in French')).toBeTruthy();
    expect(screen.getByText('HD-4472 · Dev K. · Order NL-48102 · 1 refund case attached')).toBeTruthy();
    await user().selectOptions(screen.getByRole('combobox', { name: 'Insert macro…' }), 'support.payout_hold');
    const reply = screen.getByPlaceholderText("Reply in the requester's language (EN/FR auto-detected)…") as HTMLTextAreaElement;
    expect(reply.value).toBe('Après un litige, les versements sont retenus 7 jours.');
    await user().click(screen.getByRole('button', { name: 'Send & keep open' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/tickets/T2/reply'))?.body)
      .toEqual({ body: 'Après un litige, les versements sont retenus 7 jours.', resolve: false, macroKey: 'support.payout_hold' }));
    expect(await screen.findByText('Reply sent.')).toBeTruthy();
    await user().type(reply, 'Merci!');
    await user().click(screen.getByRole('button', { name: 'Send & resolve' }));
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/tickets/T2/reply')).at(-1)?.body).toEqual({ body: 'Merci!', resolve: true }));
  });

  it('assigns, escalates and shows the api refusal inline', async () => {
    const calls = api(['support'], c => (c.url.endsWith('/escalate') ? { status: 409, body: { code: 'already_escalated', detail: 'This case is already with trust & safety.' } } : undefined));
    renderConsole('/support');
    await user().click(await screen.findByRole('button', { name: 'Assign to me' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/tickets/T1/take'))).toBe(true));
    await user().click(screen.getByRole('button', { name: 'Escalate to T&S' }));
    expect((await screen.findByRole('alert')).textContent).toBe('This case is already with trust & safety.');
  });

  it('sends a refund request to finance, with the amount in cents and inline 422s', async () => {
    let first = true;
    const calls = api(['support'], c => {
      if (c.url.endsWith('/refund-requests') && first) { first = false; return { status: 422, body: { errors: [{ field: 'amountCents', code: 'range', message: 'Enter an amount more than $0.' }] } }; }
      return undefined;
    });
    renderConsole('/support');
    await user().click(await screen.findByRole('button', { name: 'Request refund (finance approves)' }));
    const dialog = await screen.findByRole('dialog', { name: 'Request a refund — HD-4471' });
    await user().click(within(dialog).getByRole('button', { name: 'Send to finance' }));
    expect(await within(dialog).findByText('Enter an amount more than $0.')).toBeTruthy();
    await user().type(within(dialog).getByLabelText('Amount'), '25.00');
    await user().type(within(dialog).getByLabelText('Why (finance sees this)'), 'Fee charged twice');
    await user().click(within(dialog).getByRole('button', { name: 'Send to finance' }));
    await waitFor(() => expect(calls.filter(c => c.url.endsWith('/tickets/T1/refund-requests')).at(-1)?.body).toEqual({ amountCents: 2500, note: 'Fee charged twice' }));
    expect(await screen.findByText('Refund request sent to finance.')).toBeTruthy();
  });

  it('lets someone with refund approve another agent’s request, never their own', async () => {
    const pending = (id: string, by: string) => ({ id, amountCents: 3100, note: 'Late delivery', requestedBy: by, requestedAt: '2026-09-08T17:00:00Z', state: 'pending', requestedByName: by === ME ? 'Priya N.' : 'Dev K.' });
    const calls = api(['admin'], c => (c.method === 'GET' && c.url.endsWith('/tickets/T1')
      ? { body: detail(HD4471, { refundRequests: [pending('RR1', 'U9'), pending('RR2', ME)] }) } : undefined));
    renderConsole('/support');
    expect(await screen.findByText(/\$31\.00 · asked by Dev K\./)).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Approve' })).toHaveLength(1);
    await user().click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/refund-requests/RR1/decision'))?.body).toEqual({ decision: 'approve' }));
  });

  it('is view only for dispatch, and macros are kept by support leads only', async () => {
    api(['dispatch']);
    renderConsole('/support');
    expect(await screen.findByText('View only · Ops dispatcher')).toBeTruthy();
    expect((await screen.findByRole('button', { name: 'Send & keep open' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Assign to me' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'Manage macros' })).toBeNull();
  });

  it('lets a support lead add a macro in English and French', async () => {
    const calls = api(['support_lead']);
    renderConsole('/support');
    await user().click(await screen.findByRole('button', { name: 'Manage macros' }));
    const dialog = await screen.findByRole('dialog', { name: 'Macros' });
    expect(within(dialog).getByText('Payout hold — policy explanation')).toBeTruthy();
    await user().click(within(dialog).getByRole('button', { name: 'New macro' }));
    await user().type(within(dialog).getByLabelText('Key'), 'support.thanks');
    await user().type(within(dialog).getByLabelText('Title (English)'), 'Thanks');
    await user().type(within(dialog).getByLabelText('Title (French)'), 'Merci');
    await user().type(within(dialog).getByLabelText('Text (English)'), 'Thanks for writing.');
    await user().type(within(dialog).getByLabelText('Text (French)'), 'Merci de nous avoir écrit.');
    await user().click(within(dialog).getByRole('button', { name: 'Save macro' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/support/macros'))?.body).toEqual({
      key: 'support.thanks', title: { en: 'Thanks', fr: 'Merci' }, body: { en: 'Thanks for writing.', fr: 'Merci de nous avoir écrit.' },
    }));
    expect(await within(dialog).findByText('Macro saved.')).toBeTruthy();
  });

  it('speaks French', async () => {
    api(['support']);
    renderConsole('/support', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: '23 ouverts · 3 urgents · première réponse médiane 38 min' })).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Envoyer et garder ouvert' })).toBeTruthy();
    expect(screen.getByText(/94\s?%/)).toBeTruthy();
  });
});
