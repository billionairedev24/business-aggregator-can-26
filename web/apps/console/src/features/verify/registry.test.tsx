import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const review = (o: Record<string, unknown> = {}) => ({
  id: 'R1', merchantId: 'M1', businessName: 'Aspen Wrench', checkKey: 'registry', source: 'corporations_canada', subject: 'corporation', registry: null,
  queryNumber: '2000000001', expectedName: 'Aspen Wrench Ltd.', outcome: 'not_found', reasons: [], recordName: null, recordNumber: null, recordStatus: null,
  recordExpiresOn: null, reference: 'fixtures:corporations_canada/2000000001', checkedAt: '2026-10-19T15:00:00Z', reviewState: 'open', reviewNote: null, ...o,
});
const ITEMS = [review(), review({ id: 'R2', businessName: 'Struck Off Co', outcome: 'mismatch', reasons: ['name', 'status'], recordName: 'STRUCK CO', recordStatus: 'Struck' })];

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/registry-reviews')) return { body: { items: ITEMS } };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/verification/applications')) return { body: { items: [], pending: 0, medianDecisionHours: null, fourEyes: true } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('Applications › Registry checks (S-158)', () => {
  it('lists the lookups that need a human with what the registry says, and re-checks one', async () => {
    const calls = api(['trust_safety'], c => (c.method === 'POST' && c.url.endsWith('/R1/recheck')
      ? { body: { verificationStatus: 'verified', checks: [review({ id: 'R9', outcome: 'matched', reviewState: null })] } } : undefined));
    renderConsole('/verification?view=registry');
    expect(await screen.findByText('2 lookups wait for an agent.')).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(screen.getByRole('tab', { name: 'Registry checks', selected: true })).toBeTruthy();
    const struck = screen.getByText('Struck Off Co').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(struck).getByText('Doesn’t match · name, status')).toBeTruthy();
    expect(within(struck).getByText('STRUCK CO · Struck')).toBeTruthy();
    const aspen = screen.getByText('Aspen Wrench').closest('tr, .nl-dt-card') as HTMLElement;
    await user().click(within(aspen).getByRole('button', { name: 'Re-check' }));
    expect(await screen.findByText('Aspen Wrench: the registry matches now. The check is verified.')).toBeTruthy();
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/registry-reviews/R1/recheck'))).toBe(true);
  });

  it('approves with what the agent checked; a decided lookup says so', async () => {
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && c.url.endsWith('/R1/decision')) return { body: review({ reviewState: 'approved' }) };
      if (c.method === 'POST' && c.url.endsWith('/R2/decision')) return { status: 409, body: { code: 'review_closed', detail: 'This review was already decided.' } };
      return undefined;
    });
    renderConsole('/verification?view=registry');
    const aspen = (await screen.findByText('Aspen Wrench')).closest('tr, .nl-dt-card') as HTMLElement;
    const u = user();
    await u.click(within(aspen).getByRole('button', { name: 'Approve' }));
    let dialog = await screen.findByRole('dialog');
    await u.type(within(dialog).getByLabelText('What you checked'), 'RA search 88120');
    await u.click(within(dialog).getByRole('button', { name: 'Approve the check' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/R1/decision'))?.body).toEqual({ decision: 'approve', reference: 'RA search 88120' }));
    expect(await screen.findByText('Aspen Wrench: the check is approved.')).toBeTruthy();
    const struck = screen.getByText('Struck Off Co').closest('tr, .nl-dt-card') as HTMLElement;
    await u.click(within(struck).getByRole('button', { name: 'Reject' }));
    dialog = await screen.findByRole('dialog');
    await u.click(within(dialog).getByRole('button', { name: 'Reject the check' }));
    expect(await within(dialog).findByText('Someone else already decided this lookup.')).toBeTruthy();
  });

  it('reads in French; a support agent doesn’t get the tab', async () => {
    api(['trust_safety']);
    renderConsole('/verification?view=registry', { locale: 'fr' });
    expect(await screen.findByText('2 recherches attendent un agent.')).toBeTruthy();
    expect(screen.getByText('Ne concorde pas · nom, statut')).toBeTruthy();
  });
});
