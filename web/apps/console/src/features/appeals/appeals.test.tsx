import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, screenReady, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const appeal = (o: Record<string, unknown> = {}) => ({
  id: 'A1', number: 'AP-1001', subjectType: 'consequence', subjectId: 'C1', subjectKind: 'suspended', subjectLabel: null,
  statement: 'We never asked anyone to pay outside Northline.', state: 'open', filedAt: '2026-10-19T18:00:00Z', decidedAt: null,
  decisionReason: null, compensationCents: 0, files: [{ id: 'F1', name: 'message log.pdf', contentType: 'application/pdf', size: 12 }], ...o,
});
const item = (o: Record<string, unknown> = {}) => ({ appeal: appeal(o), merchantId: 'M1', businessName: 'Standing Mechanics', province: 'AB', assigneeId: null as string | null, assigneeName: null as string | null });
const DISPUTE = { appeal: appeal({ id: 'A2', number: 'AP-1002', subjectType: 'dispute', subjectId: 'D1', subjectKind: 'full_refund', subjectLabel: 'DS-1188', files: [] }), merchantId: 'M2', businessName: 'Prairie Wrench', province: 'AB', assigneeId: null, assigneeName: null };
const detail = (i: ReturnType<typeof item>) => ({
  item: i, consequence: { id: 'C1', kind: 'suspended', cause: 'staff', reason: 'Customers report payments outside Northline.', startedAt: '2026-10-18T18:00:00Z', endsAt: null, active: true, liftedAt: null, liftReason: null },
  dispute: null, originalActor: 'U9', originalActorName: 'Dev Kaur', decidedBy: null, decidedByName: null,
});

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/appeals/A1')) return { body: detail(item()) };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/appeals')) return { body: { items: [item(), DISPUTE] } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('appeals (S-156)', () => {
  it('lists the appeals and opens one with the statement, the files and what was decided', async () => {
    api(['trust_safety']);
    renderConsole('/trust/appeals?appeal=A1');
    expect((await screenReady()).textContent).toBe('Appeals');
    expect(screen.getAllByText('AP-1001').length).toBeGreaterThan(0);
    expect(screen.getByText('Dispute DS-1188')).toBeTruthy();
    const sheet = await screen.findByRole('dialog', { name: 'AP-1001 · Standing Mechanics' });
    expect(within(sheet).getByText('We never asked anyone to pay outside Northline.')).toBeTruthy();
    expect(within(sheet).getByRole('link', { name: 'message log.pdf' }).getAttribute('href')).toBe('/api/v1/console/appeals/A1/files/F1');
    expect(await within(sheet).findByText('Decided by Dev Kaur')).toBeTruthy();
    await expectNoAxeViolations(document.body);
  });

  it('takes it and grants it with a reason; the api’s refusal is shown', async () => {
    const calls = api(['trust_safety'], c => {
      if (c.method === 'POST' && c.url.endsWith('/A1/assign')) return { body: detail({ ...item({ state: 'in_review' }), assigneeId: 'U1', assigneeName: 'Rae L.' }) };
      if (c.method === 'POST' && c.url.endsWith('/A1/decision')) {
        return (c.body as { reason: string }).reason ? { status: 409, body: { code: 'appeal_self', detail: 'Someone other than the person who made the decision must decide the appeal.' } }
          : { status: 422, body: { errors: [{ field: 'reason', rule: 'required', message: 'Give the business a reason.' }] } };
      }
      return undefined;
    });
    renderConsole('/trust/appeals?appeal=A1');
    const sheet = await screen.findByRole('dialog', { name: 'AP-1001 · Standing Mechanics' });
    await user().click(await within(sheet).findByRole('button', { name: 'Take it' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/A1/assign'))).toBe(true));
    await user().click(within(sheet).getByRole('button', { name: 'Grant' }));
    expect(await within(sheet).findByText('Give the business a reason.')).toBeTruthy();
    await user().type(within(sheet).getByLabelText('Reason (the business sees it)'), 'The messages show the customer asked.');
    await user().click(within(sheet).getByRole('button', { name: 'Grant' }));
    expect(await within(sheet).findByText('Someone other than the person who made the decision must decide the appeal.')).toBeTruthy();
    expect(calls.filter(c => c.url.endsWith('/A1/decision')).at(-1)?.body).toEqual({ outcome: 'grant', reason: 'The messages show the customer asked.' });
  });

  it('is view only without decide, and French', async () => {
    api(['analyst'] as never, c => (c.url.endsWith('/api/v1/console/me') ? { body: { userId: 'U1', roles: [{ role: 'trust_safety', screens: ['trust'], actions: [] }] } } : undefined));
    renderConsole('/trust/appeals?appeal=A1', { locale: 'fr' });
    expect((await screenReady()).textContent).toBe('Appels');
    const sheet = await screen.findByRole('dialog', { name: 'AP-1001 · Standing Mechanics' });
    expect((await within(sheet).findByRole('button', { name: 'Accueillir' })).hasAttribute('disabled')).toBe(true);
  });
});
