import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, SESSION, staffApi } from '../../test/render';
import type { Rota } from './api';

const ME = SESSION.user.id;
const ROTA: Rota = {
  asOf: '2026-09-08T18:00:00Z',
  shifts: [
    { id: 's1', userId: ME, name: 'Priya Natarajan', startsAt: '2026-09-08T15:00:00Z', endsAt: '2026-09-09T00:00:00Z', duty: 'Ops lead · province & finance approvals' },
    { id: 's2', userId: 'u2', name: 'Dev Kaur', startsAt: '2026-09-09T00:00:00Z', endsAt: '2026-09-09T08:00:00Z', duty: 'Trust & safety · disputes, suspensions' },
  ],
  now: [],
  staff: [{ id: ME, name: 'Priya Natarajan', roles: ['staff'] }, { id: 'u2', name: 'Dev Kaur', roles: ['staff'] }],
};
ROTA.now = [ROTA.shifts[0]!];

describe('on-call & escalations (S-96, design 03)', () => {
  it('shows the rota with who is on call, hands over my shift, and lists the escalation paths', async () => {
    const calls = staffApi(['support'], c => {
      if (c.method === 'GET' && c.url.endsWith('/api/v1/console/oncall')) return { body: ROTA };
      if (c.url.endsWith('/hand-over')) return { body: { ...ROTA.shifts[0], userId: 'u2', name: 'Dev Kaur' } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/on-call');
    expect(await screen.findByText('Priya Natarajan (you)')).toBeTruthy();
    expect(within(screen.getByText('Priya Natarajan (you)').closest('li') as HTMLElement).getByText('on call')).toBeTruthy();
    expect(screen.getByText('Payment stuck / escrow error')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add shift' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Swap a shift' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hand over · Ops lead · province & finance approvals' });
    await user.click(within(dialog).getByRole('button', { name: 'Hand over' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/shifts/s1/hand-over'))?.body).toEqual({ userId: 'u2' }));
  });

  it('lets an admin add a shift and shows the api’s messages', async () => {
    const calls = staffApi(['admin'], c => {
      if (c.method === 'GET' && c.url.endsWith('/api/v1/console/oncall')) return { body: ROTA };
      if (c.method === 'POST' && c.url.endsWith('/oncall/shifts')) return { status: 422, body: { errors: [{ field: 'duty', rule: 'length', message: 'Say what the shift covers, 1 to 120 characters.' }] } };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/on-call');
    await user.click(await screen.findByRole('button', { name: 'Add shift' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a shift' });
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    expect(await within(dialog).findByText('Say what the shift covers, 1 to 120 characters.')).toBeTruthy();
    expect(calls.some(c => c.method === 'POST')).toBe(true);
  });
});
