import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { HoursTab } from './HoursTab';
import { HOURS_MESSAGES, slotCount, timeOffSchema, validateDays } from './rules';
import type { Days } from './api';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('../../lib/session', () => ({ useSession: () => ({ data: { user: { id: 'ravi' } } }) }));

const days = (patch: Partial<Days> = {}): Days => ({ mon: [['07:00', '18:00']], tue: [['07:00', '18:00']], wed: [], thu: [], fri: [], sat: [['09:00', '14:00']], sun: [], ...patch });

describe('availability rules', () => {
  it('validates ranges like the server', () => {
    expect(validateDays(days({ wed: [['16:00', '08:00']], thu: [['08:00', '12:00'], ['11:00', '14:00']] }))).toEqual({ 'wed[0]': HOURS_MESSAGES.endBeforeStart, 'thu[1]': HOURS_MESSAGES.overlap });
    expect(validateDays(days())).toEqual({});
  });
  it('counts start times like the design ("5 slots")', () => {
    expect(slotCount(['09:00', '14:00'], 45, 30)).toBe(9);
    expect(slotCount(['09:00', '09:30'], 45, 30)).toBe(0);
  });
  it('time off messages', () => {
    const r = timeOffSchema.safeParse({ startsOn: '', endsOn: '', kind: 'special', specialFrom: '', specialTo: '', reason: '' });
    expect(r.success).toBe(false);
    expect(r.error?.issues.map(i => i.message)).toEqual(expect.arrayContaining(['Pick the first day.', "Add the hours you're open."]));
  });
});

describe('Weekly hours', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('toggles a day, copies to the team, and saves every changed member', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/availability/hours': () => ({ members: [{ userId: 'ravi', name: 'Ravi Sandhu', role: 'owner', bookable: true, effectiveFrom: '2026-09-02', days: days() }, { userId: 'jas', name: 'Jas Gill', role: 'technician', bookable: true, effectiveFrom: '2026-09-02', days: days({ mon: [] }) }], lastSavedAt: '2026-09-02T16:00:00Z' }),
      'GET /api/v1/merchants/m1/availability/rules': () => ({ intervalMin: 30, bufferMin: 20, minNoticeMin: 60, horizonDays: 14, maxJobsPerDay: 5, acceptMode: 'instant', rescheduleFreeMin: 180, lateCancelFeeCents: 0, holidayPremiumCents: 5000, serviceAreas: [], zones: [] }),
      'GET /api/v1/merchants/m1/availability/services': () => ({ items: [{ id: 's1', name: 'Brake inspection', durationMin: 45 }] }),
      'POST /api/v1/merchants/m1/availability/preview': () => ({ slots: [{ start: '07:00', free: true }, { start: '07:30', free: false }], jobs: 1, intervalMin: 30, bufferMin: 20 }),
      'PUT /api/v1/merchants/m1/availability/hours': (_u, init) => { const b = JSON.parse(String(init.body)); return { members: b.members.map((m: { memberUserId: string; days: Days }) => ({ userId: m.memberUserId, name: m.memberUserId, role: 'owner', bookable: true, effectiveFrom: b.effectiveFrom, days: m.days })), lastSavedAt: '2026-09-29T16:00:00Z' }; },
    });
    const user = userEvent.setup();
    const onState = vi.fn();
    renderWithProviders(<HoursTab onState={onState} />);
    expect(await screen.findByRole('button', { name: 'Ravi Sandhu', pressed: true })).toBeTruthy();
    expect(await screen.findByText(/1 of 2 start times free for a 45-min job/)).toBeTruthy();

    await user.click(screen.getByRole('switch', { name: 'Bookable on Wed' }));
    expect(screen.getAllByRole('combobox', { name: /^Wed/ })).toHaveLength(2);
    await waitFor(() => expect(onState).toHaveBeenLastCalledWith('dirty'));
    await user.click(screen.getByRole('button', { name: 'Copy to other members' }));
    await user.click(screen.getByRole('button', { name: 'Save hours' }));

    await waitFor(() => expect(calls.find(c => c.method === 'PUT')).toBeDefined());
    const put = calls.find(c => c.method === 'PUT')!.body as { members: { memberUserId: string; days: Days }[] };
    expect(put.members.map(m => m.memberUserId).sort()).toEqual(['jas', 'ravi']);
    expect(put.members[0]!.days.wed).toEqual([['09:00', '17:00']]);
  });

  it('blocks saving invalid ranges with the summary', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/availability/hours': () => ({ members: [{ userId: 'ravi', name: 'Ravi Sandhu', role: 'owner', bookable: true, effectiveFrom: null, days: days() }] }),
      'GET /api/v1/merchants/m1/availability/rules': () => { throw { status: 500, body: {} }; },
      'GET /api/v1/merchants/m1/availability/services': () => ({ items: [] }),
      'POST /api/v1/merchants/m1/availability/preview': () => ({ slots: [], jobs: 0, intervalMin: 30, bufferMin: 20 }),
    });
    const user = userEvent.setup();
    renderWithProviders(<HoursTab onState={() => {}} />);
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Mon Until' }), '06:30');
    await user.click(screen.getByRole('button', { name: 'Save hours' }));
    expect(screen.getByText('End time must be after start time.').getAttribute('role')).toBe('alert');
    expect(screen.getByText('1 thing needs attention.')).toBeTruthy();
  });
});
