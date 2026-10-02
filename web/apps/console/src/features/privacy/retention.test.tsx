import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { periodParts } from './retentionApi';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const RUN = { category: 'messaging.conversations', dryRun: false, trigger: 'schedule', startedAt: '2026-10-02T08:47:00Z', finishedAt: '2026-10-02T08:47:03Z',
  outcome: 'succeeded', affected: 12, held: 3, remaining: 0 };
const CONVERSATIONS = { code: 'messaging.conversations', module: 'messaging', name: 'Conversations between customers and businesses, with their files',
  clause: 'Messages and dispute evidence', policy: '2 years after the transaction, or until a dispute is closed plus 1 year, whichever is later.',
  period: 'P2Y', afterDisputeClosed: 'P1Y', lawMinimum: false, starts: 'the conversation’s last message', basis: 'Claims about a transaction.',
  action: 'delete', enforcement: 'job', holds: ['open_order', 'open_dispute'], note: null, lastRun: RUN, lastSuccessAt: RUN.finishedAt,
  rowsAffected: 12, held: 3, nextDueAt: '2026-10-03T08:47:00Z', overdue: false };
const SIGN_INS = { ...CONVERSATIONS, code: 'identity.sign_ins', module: 'identity', name: 'Sign-ins (device, network address, city)', clause: 'Login and security logs',
  policy: '12 months.', period: 'P12M', afterDisputeClosed: null, holds: [], lastRun: { ...RUN, category: 'identity.sign_ins', outcome: 'failed', affected: 0, held: 0 },
  lastSuccessAt: null, rowsAffected: 0, held: 0, overdue: true };
const REVIEWS = { ...CONVERSATIONS, code: 'trust.reviews', module: 'trust', name: 'Reviews', clause: 'Reviews', policy: 'remain public…', period: null, afterDisputeClosed: null,
  action: 'pseudonymise', enforcement: 'none', holds: [], lastRun: null, lastSuccessAt: null, rowsAffected: 0, held: 0, nextDueAt: null, overdue: false };
const REPORT = {
  generatedAt: '2026-10-02T12:00:00Z', nextRunAt: '2026-10-03T08:47:00Z', dryRunOnly: false, categories: [CONVERSATIONS, SIGN_INS, REVIEWS],
  operational: [{ code: 'messaging.push_devices', name: 'Push installations not refreshed', period: 'P90D', where: 'worker · PushPruneJob, daily', setting: 'PUSH_STALE_AFTER' }],
  laws: [{ code: 'pipeda', name: 'PIPEDA', decisionRetentionDays: 0 }, { code: 'bc_pipa', name: 'PIPA (BC)', decisionRetentionDays: 365 }],
};

function api(roles: Parameters<typeof staffApi>[0]) {
  return staffApi(roles, (c: Call) => {
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/retention')) return { body: REPORT };
    if (c.method === 'POST' && c.url.endsWith('/api/v1/console/retention/runs')) {
      const body = c.body as { dryRun: boolean; category?: string };
      return { body: { items: [{ ...RUN, dryRun: body.dryRun, category: body.category ?? RUN.category, affected: 4 }] } };
    }
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('retention report (S-107)', () => {
  it('words ISO periods', () => {
    expect(periodParts('P2Y')).toEqual({ n: 2, unit: 'y' });
    expect(periodParts('P12M')).toEqual({ n: 12, unit: 'm' });
    expect(periodParts('P90D')).toEqual({ n: 90, unit: 'd' });
    expect(periodParts('PT24H')).toEqual({ n: 24, unit: 'h' });
    expect(periodParts(null)).toBeUndefined();
  });

  it('lists every category with its period, last run, rows, holds and state; laws and other deletions below', async () => {
    api(['privacy']);
    renderConsole('/privacy?view=retention');
    expect(await screen.findByRole('heading', { level: 1, name: '3 categories · 1 overdue' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getAllByText('2 years; a dispute: 1 year after its decision').length).toBeGreaterThan(0);
    expect(screen.getAllByText('12 months').length).toBeGreaterThan(0);
    expect(screen.getAllByText('While public').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Overdue').length).toBeGreaterThan(0);
    expect(screen.getAllByText('No end').length).toBeGreaterThan(0);
    expect(screen.getByText(/PIPA \(BC\)/).closest('li')!.textContent).toContain('365 days');
    expect(screen.getByText(/Push installations not refreshed/).closest('li')!.textContent).toContain('90 days');
    expect(screen.getByRole('link', { name: 'Export CSV' }).getAttribute('href')).toBe('/api/v1/console/retention/export');
  });

  it('opens a category: the policy’s words, the basis and the holds; a dry run of it', async () => {
    const calls = api(['support_lead']);
    renderConsole('/privacy?view=retention');
    await user().click((await screen.findAllByText('Conversations between customers and businesses, with their files'))[0]!);
    const drawer = await screen.findByRole('dialog', { name: 'Conversations between customers and businesses, with their files' });
    expect(within(drawer).getByText(/Privacy Policy: “Messages and dispute evidence: 2 years after the transaction/)).toBeTruthy();
    expect(within(drawer).getByText(/Order under way, Dispute \(open, or decided recently\)/)).toBeTruthy();
    await user().click(within(drawer).getByRole('button', { name: 'Dry run' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/retention/runs'))?.body).toEqual({ dryRun: true, category: 'messaging.conversations' }));
    expect(await within(drawer).findByText('4 rows are due; nothing was changed.')).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/retention/runs'))!.headers['X-Console-Role']).toBe('support_lead');
  });

  it('runs everything only after confirming', async () => {
    const calls = api(['admin']);
    renderConsole('/privacy?view=retention');
    await user().click(await screen.findByRole('button', { name: 'Run everything now' }));
    const confirm = await screen.findByRole('dialog', { name: 'Run the retention jobs now?' });
    expect(calls.some(c => c.url.endsWith('/retention/runs'))).toBe(false);
    await user().click(within(confirm).getByRole('button', { name: 'Run now' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/retention/runs'))?.body).toEqual({ dryRun: false }));
    expect(await screen.findByText('4 rows changed.')).toBeTruthy();
  });

  it('switches between requests and retention', async () => {
    api(['privacy']);
    renderConsole('/privacy?view=retention');
    await screen.findByRole('heading', { level: 1, name: '3 categories · 1 overdue' });
    expect(screen.getByRole('tab', { name: 'Retention' }).getAttribute('aria-selected')).toBe('true');
  });

  it('speaks French', async () => {
    api(['privacy']);
    renderConsole('/privacy?view=retention', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: '3 catégories · 1 en retard' })).toBeTruthy();
    expect(screen.getAllByText('2 ans; litige : 1 an après la décision').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Tout essayer' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Exporter en CSV' })).toBeTruthy();
  });
});
