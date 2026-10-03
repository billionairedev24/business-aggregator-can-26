import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { renderConsole, SESSION, staffApi, type Call } from '../../test/render';

const gate = (key: string, status: string, code: string, extra: Record<string, unknown> = {}) => ({
  key, kind: 'manual', owner: 'security', required: true, status, code, params: {}, evidence: null, evidenceUrl: null, source: null,
  recordedBy: null, recordedAt: null, recordable: true, runbook: `docs/runbooks/${key}.md`, ...extra,
});
const GATES = [
  gate('pilot_businesses', 'pass', 'pilot_ready', { kind: 'auto', owner: 'merchant_success', recordable: false, params: { ready: '12', total: '12', blocked: '0', min: '10' } }),
  gate('stripe_live', 'fail', 'stripe_not_live', { kind: 'auto', owner: 'payments', recordable: false, params: { secretKey: 'test', publishableKey: 'test', webhookSecret: 'true', connectWebhookSecret: 'false' } }),
  gate('pentest', 'pending', 'not_recorded'),
  gate('security_findings', 'pass', 'recorded', { evidence: 'No open high finding.', source: 'script', recordedBy: { id: 'U1', name: 'Avery Admin' }, recordedAt: '2026-10-02T15:00:00Z' }),
  gate('app_stores', 'pending', 'not_recorded', { owner: 'mobile', required: false }),
];
const MARKET = { id: 'mkt-pilotville', city: 'Pilotville', province: 'AB', stage: 'pilot', frenchFirst: false, zone: 'America/Edmonton' };
const CHECKLIST = { market: MARKET, generatedAt: '2026-10-03T12:00:00Z', gates: GATES, blocking: ['stripe_live', 'pentest'], ready: false, request: null, requests: [], events: [], hypercare: null };
const OTHER = { id: 'U2', name: 'Blake Admin' };
const REQUEST = { id: 'R1', state: 'pending', requestedBy: OTHER, requestedAt: '2026-10-03T12:00:00Z', expiresAt: '2026-10-04T12:00:00Z', note: 'Go/no-go said go.',
  override: true, overrideReason: 'Board decision: launch for the long weekend.', blocking: ['pentest'], decidedBy: null, decidedAt: null, decisionNote: null };
const MARKETS = { items: [{ id: 'mkt-pilotville', city: 'Pilotville', province: 'AB', stage: 'pilot', requestPending: false, launchedAt: null }] };

function api(roles: Parameters<typeof staffApi>[0], checklist: unknown = CHECKLIST, extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/go-live')) return { body: MARKETS };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/go-live/mkt-pilotville')) return { body: checklist };
    if (c.method === 'POST' && c.url.includes('/api/v1/console/go-live/mkt-pilotville/')) return { body: checklist };
    return undefined;
  });
}

describe('go-live', () => {
  it('shows every gate with its status, owner and evidence, in words', async () => {
    api(['admin']);
    const { container } = renderConsole('/go-live');
    expect(await screen.findByRole('heading', { level: 1, name: 'Pilotville: pilot (hidden from customers)' })).toBeTruthy();
    expect(screen.getByText('2 required gates block the launch.')).toBeTruthy();
    expect(screen.getByText('12 of 12 pilot businesses ready (10 needed), 0 blocked.')).toBeTruthy();
    expect(screen.getByText('Secret key: test; publishable key: test; webhook secrets set: true / false.')).toBeTruthy();
    expect(screen.getByText('No open high finding.')).toBeTruthy();
    expect(screen.getByText(/Avery Admin \(script\)/)).toBeTruthy();
    expect(screen.getAllByText('Not recorded yet.').length).toBe(2);
    await expectNoAxeViolations(container);
  });

  it('records a manual gate with its evidence', async () => {
    const calls = api(['finance']);
    renderConsole('/go-live');
    const user = userEvent.setup();
    const row = (await screen.findByText('External penetration test')).closest('li')!;
    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Record' }));
    const dialog = await screen.findByRole('dialog', { name: 'Record “External penetration test”' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Evidence' }), 'Report v1: no critical or high open.');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/gates/pentest'))?.body)
      .toEqual({ status: 'pass', evidence: 'Report v1: no critical or high open.' }));
    // finance records gates but doesn't switch markets
    expect(screen.queryByRole('button', { name: 'Request go-live' })).toBeNull();
  });

  it('asks for go-live with an emergency override while gates fail', async () => {
    const calls = api(['admin']);
    renderConsole('/go-live');
    const user = userEvent.setup();
    const ask = await screen.findByRole('button', { name: 'Request go-live' });
    expect((ask as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByRole('checkbox', { name: /Emergency override/ }));
    await user.type(screen.getByRole('textbox', { name: /can’t wait/ }), 'Board decision: launch for the long weekend.');
    await user.click(ask);
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/launch-requests'))?.body)
      .toEqual({ overrideReason: 'Board decision: launch for the long weekend.' }));
  });

  it('a second admin approves with the market’s name; the requester can only withdraw', async () => {
    const calls = api(['admin'], { ...CHECKLIST, request: REQUEST });
    const { unmount } = renderConsole('/go-live');
    const user = userEvent.setup();
    expect(await screen.findByText('Emergency override: Board decision: launch for the long weekend.')).toBeTruthy();
    await user.type(screen.getByRole('textbox', { name: 'Type “Pilotville” to confirm' }), 'Pilotville');
    await user.click(screen.getByRole('button', { name: 'Approve and go live' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/launch-requests/R1/approve'))?.body).toEqual({ confirm: 'Pilotville' }));
    unmount();

    api(['admin'], { ...CHECKLIST, request: { ...REQUEST, requestedBy: { id: SESSION.user.id, name: 'Priya Natarajan' } } });
    renderConsole('/go-live');
    expect(await screen.findByText('You asked: a second admin approves.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve and go live' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeTruthy();
  });

  it('rolls a live market back with a reason, and shows hypercare', async () => {
    const live = {
      ...CHECKLIST, market: { ...MARKET, stage: 'live' }, events: [{ kind: 'launched', by: OTHER, at: '2026-10-03T13:00:00Z', reason: null, requestId: 'R1' }],
      hypercare: { startsOn: '2026-10-03', endsOn: '2026-10-16', days: [{ date: '2026-10-03', primary: OTHER, secondary: { id: 'U1', name: 'Avery Admin' }, business: OTHER }] },
    };
    const calls = api(['admin'], live);
    renderConsole('/go-live');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Roll back to pilot' }));
    const dialog = await screen.findByRole('dialog', { name: 'Roll back to pilot' });
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Checkout errors over the threshold.');
    await user.type(within(dialog).getByRole('textbox', { name: /to confirm/ }), 'Pilotville');
    await user.click(within(dialog).getByRole('button', { name: 'Roll back to pilot' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/rollback'))?.body)
      .toEqual({ reason: 'Checkout errors over the threshold.', confirm: 'Pilotville' }));
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByText(/Went live — Blake Admin/)).toBeTruthy();
  });

  it('speaks French', async () => {
    api(['admin']);
    renderConsole('/go-live', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Pilotville : pilote (caché des clients)' })).toBeTruthy();
    expect(screen.getByText('2 points obligatoires bloquent la mise en service.')).toBeTruthy();
    expect(screen.getByText('Test d’intrusion externe')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Demander la mise en service' })).toBeTruthy();
  });
});
