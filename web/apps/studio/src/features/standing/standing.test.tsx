import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { ago, D, M, renderScreen, stubFetch } from '../messages/testing';

const shell = vi.hoisted(() => ({ role: 'owner' }));
vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWM1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWM1', displayName: 'Prairie Wrench', type: 'provider', tier: 'master', status: 'active', role: shell.role }),
  useRole: () => shell.role,
}));

import { StandingScreen } from './StandingScreen';

const user = () => userEvent.setup({ delay: null });
const base = `/api/v1/merchants/${M}/standing`;

const PAUSED = {
  id: 'c1', kind: 'instant_book_off', cause: 'provider_no_shows', reason: '3 confirmed no-shows in the last 30 days.', detail: { noShows: '3' },
  startedAt: ago(2 * D), endsAt: null, active: true, liftedAt: null, liftReason: null, liftedByStaff: false, appealable: true,
  appealBy: new Date(Date.now() + 12 * D).toISOString(), appeal: null,
};
const PLAN = {
  id: 'p1', title: 'Raise your rating', note: 'Below the floor for three weeks.', dueOn: null, state: 'active', createdAt: ago(D), closedAt: null, closeReason: null,
  tasks: [{ id: 't1', title: 'Reply to every review within two days', done: false, doneAt: null }, { id: 't2', title: 'Add completion photos', done: true, doneAt: ago(D) }],
};
const STANDING = {
  active: [PAUSED], past: [], appeals: [], coaching: PLAN, pastCoaching: [],
  appealableDisputes: [{ disputeId: 'd1', caseNumber: 'DS-1188', subject: 'Brake job', decision: 'full_refund', refundCents: 20_000, decidedAt: ago(D), appealBy: new Date(Date.now() + 13 * D).toISOString(), note: 'The brakes squeal.' }],
};
const APPEAL = { id: 'a1', number: 'AP-1001', subjectType: 'consequence', subjectId: 'c1', subjectKind: 'instant_book_off', subjectLabel: null, statement: 'Two were the customer.', state: 'open', filedAt: ago(0), decidedAt: null, decisionReason: null, compensationCents: 0, files: [] };

beforeEach(() => { shell.role = 'owner'; });
afterEach(() => vi.unstubAllGlobals());

describe('StandingScreen (S-156)', () => {
  it('shows what limits the business, why, and until when it can be appealed', async () => {
    stubFetch({ [`GET ${base}`]: STANDING });
    renderScreen(<StandingScreen />);
    expect(await screen.findByRole('heading', { name: 'Instant book paused' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(screen.getByText("By Northline's trust rules")).toBeTruthy();
    expect(screen.getByText('Reason: 3 confirmed no-shows in the last 30 days.')).toBeTruthy();
    expect(screen.getAllByText(/You can appeal until/)).toHaveLength(2);
    expect(screen.getByRole('heading', { name: 'DS-1188 · Brake job' })).toBeTruthy();
    expect(screen.getByText('Full refund to the customer ($200.00)')).toBeTruthy();
    expect(screen.getByText('1 of 2 done')).toBeTruthy();
  });

  it('owners appeal: the rules first, then the appeal and its files', async () => {
    const calls = stubFetch({
      [`GET ${base}`]: STANDING,
      [`POST ${base}/appeals`]: () => ({ status: 201, json: APPEAL }),
      [`POST ${base}/appeals/a1/files`]: () => ({ status: 201, json: { ...APPEAL, files: [{ id: 'f1', name: 'log.pdf', contentType: 'application/pdf', size: 10 }] } }),
    });
    renderScreen(<StandingScreen />);
    const card = (await screen.findByRole('heading', { name: 'Instant book paused' })).closest('li')!;
    await user().click(within(card).getByRole('button', { name: 'Appeal' }));
    const dialog = await screen.findByRole('dialog', { name: 'Appeal: Instant book paused' });
    await user().click(within(dialog).getByRole('button', { name: 'Send appeal' }));
    expect(within(dialog).getByRole('alert').textContent).toBe('Tell us why the decision should change.');
    expect(calls.some(c => c.key === `POST ${base}/appeals`)).toBe(false);

    await user().type(within(dialog).getByLabelText('Why should the decision change?'), 'Two were the customer.');
    const input = dialog.querySelector('input[type="file"]') as HTMLInputElement;
    await user().upload(input, new File(['%PDF-1.7'], 'log.pdf', { type: 'application/pdf' }));
    expect(within(dialog).getByText('log.pdf')).toBeTruthy();
    await user().click(within(dialog).getByRole('button', { name: 'Send appeal' }));
    expect(await screen.findByText("Appeal AP-1001 is with Northline. The owners get an email when it's decided.")).toBeTruthy();
    expect(calls.find(c => c.key === `POST ${base}/appeals`)?.body).toEqual({ subjectType: 'consequence', subjectId: 'c1', statement: 'Two were the customer.' });
    expect(calls.some(c => c.key === `POST ${base}/appeals/a1/files`)).toBe(true);
  });

  it('others read it; only owners appeal; tasks tick off', async () => {
    shell.role = 'technician';
    const calls = stubFetch({ [`GET ${base}`]: STANDING, [`PUT ${base}/coaching/p1/tasks/t1`]: { ...PLAN, tasks: PLAN.tasks.map(x => ({ ...x, done: true })) } });
    renderScreen(<StandingScreen />);
    await screen.findByRole('heading', { name: 'Instant book paused' });
    expect(screen.queryByRole('button', { name: 'Appeal' })).toBeNull();
    expect(screen.getAllByText('Only an owner can appeal.').length).toBe(2);
    await user().click(screen.getByRole('checkbox', { name: 'Reply to every review within two days' }));
    await waitFor(() => expect(calls.find(c => c.key === `PUT ${base}/coaching/p1/tasks/t1`)?.body).toEqual({ done: true }));
  });

  it('decided appeals show the outcome and what Northline paid; nothing in force reads all clear', async () => {
    stubFetch({ [`GET ${base}`]: {
      active: [], appealableDisputes: [], coaching: null, pastCoaching: [],
      appeals: [{ ...APPEAL, subjectType: 'dispute', subjectId: 'd1', subjectKind: 'full_refund', subjectLabel: 'DS-1188', state: 'granted', decidedAt: ago(0), decisionReason: 'The odometer photos show the car was driven.', compensationCents: 18_000 }],
      past: [{ ...PAUSED, active: false, appealable: false, liftedAt: ago(0), liftedByStaff: true, liftReason: 'Appeal AP-1001 granted: two were the customer.' }],
    } });
    renderScreen(<StandingScreen />);
    expect(await screen.findByText('Nothing is limiting your business right now.')).toBeTruthy();
    expect(screen.getByText('Granted')).toBeTruthy();
    expect(screen.getByText('Northline paid $180.00 into your balance. The customer kept their refund.')).toBeTruthy();
    expect(screen.getByText('Appeal AP-1001 granted: two were the customer.')).toBeTruthy();
  });

  it('French', async () => {
    stubFetch({ [`GET ${base}`]: STANDING });
    renderScreen(<StandingScreen />, 'fr');
    expect(await screen.findByRole('heading', { name: 'Réservation instantanée suspendue' })).toBeTruthy();
    expect(screen.getByText('Par les règles de confiance de Northline')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Faire appel' }).length).toBe(2);
  });
});
