import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { addDays, localInstant, today, weekStartOf } from '../../lib/time';
import { AppointmentsScreen, weekDays } from './AppointmentsScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('../../lib/session', () => ({ useSession: () => ({ data: { user: { id: 'u1' } } }) }));

const start = weekStartOf(today(), 'en'); // en-CA weeks start on Sunday
const wednesday = addDays(start, 3);
const saturday = addDays(start, 6);
const job = { id: 'j1', ref: 'BK-7712', title: 'Brake inspection', startsAt: localInstant(wednesday, '07:00'), endsAt: localInstant(wednesday, '08:00'), state: 'confirmed', memberUserId: 'u1', memberName: 'Ravi', customerName: 'A. Osei', area: null, priceCents: 8900 };

function serve(cells: unknown) {
  return mockFetch({
    'GET /api/v1/merchants/m1/jobs?': () => ({ items: [job] }),
    'GET /api/v1/merchants/m1/calendar-cells': () => cells,
    'GET /api/v1/merchants/m1/time-off': () => ({ entries: [] }),
    'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [] }),
  });
}

describe('the week (demo rehearsal 2026-10-19: on a Sunday "this week" was the past Monday–Saturday)', () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it('starts where the locale starts weeks and holds today, so the coming jobs are in this week', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-18T18:00:00Z')); // a Sunday
    const monday = { ...job, id: 'j9', ref: 'BK-9001', title: 'Oil & filter', startsAt: localInstant('2026-10-19', '09:00'), endsAt: localInstant('2026-10-19', '10:00') };
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/jobs?': () => ({ items: [monday] }),
      'GET /api/v1/merchants/m1/calendar-cells': () => ({ openSlots: [], quoteHolds: [] }),
      'GET /api/v1/merchants/m1/time-off': () => ({ entries: [] }),
      'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [] }),
    });
    renderWithProviders(<AppointmentsScreen />);
    expect(await screen.findByText(/Oil & filter/)).toBeTruthy();
    expect(screen.getByRole('heading', { level: 2, name: 'Week of Oct 18' })).toBeTruthy();
    const days = screen.getAllByRole('group').map(g => g.getAttribute('aria-label'));
    expect(days[0]).toBe('Sunday, October 18'); // today, even with nothing on it
    expect(days).toContain('Monday, October 19');
    expect(calls.some(c => c.url.includes('/calendar-cells?from=2026-10-18&days=7'))).toBe(true);
    const jobs = calls.find(c => c.url.includes('/jobs?'))!;
    expect(new URL(jobs.url, 'http://x').searchParams.get('from')).toBe(localInstant('2026-10-18'));
  });

  it('shows an empty Sunday only when it is today', () => {
    expect(weekDays('2026-10-18', [], { openSlots: [], quoteHolds: [] }, '2026-10-20')).toEqual(['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24']);
    expect(weekDays('2026-10-18', [], { openSlots: [], quoteHolds: [] }, '2026-10-18')).toHaveLength(7);
  });
});

describe('Appointments calendar cells (S-74)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows open slots and quote holds as dim cells in time order next to the jobs', async () => {
    const calls = serve({
      openSlots: [{ startsAt: localInstant(wednesday, '10:00') }],
      quoteHolds: [{ quoteId: 'q1', requestId: 'r1', ref: 'QT-3104', customerName: 'M. Tran', startsAt: localInstant(saturday, '10:00'), durationMin: 120 }],
    });
    renderWithProviders(<AppointmentsScreen />);
    expect(await screen.findByText('Open slot')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    const wed = screen.getAllByRole('group').find(g => (g.getAttribute('aria-label') ?? '').startsWith('Wednesday'))!;
    const cells = within(wed).getAllByText(/Brake inspection|Open slot/).map(e => e.closest('.nl-appt-job')!);
    expect(cells.map(c => c.textContent)).toEqual([expect.stringContaining('Brake inspection'), expect.stringContaining('Open slot')]);
    expect(cells[1]!.getAttribute('data-dim')).toBe('true');
    expect(cells[1]!.tagName).toBe('DIV'); // information, not a button
    const sat = screen.getAllByRole('group').find(g => (g.getAttribute('aria-label') ?? '').startsWith('Saturday'))!;
    expect(within(sat).getByText('Held for quote · M. Tran')).toBeTruthy();
    expect(calls.some(c => c.url.includes(`/calendar-cells?from=${start}&days=7`))).toBe(true);
  });

  it('opens a booking in the right sheet (PH:240), and closes it', async () => {
    serve({ openSlots: [], quoteHolds: [] });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<AppointmentsScreen />);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Calendar');
    await user.click(await screen.findByRole('button', { name: /Brake inspection/ }));
    const sheet = screen.getByRole('dialog', { name: 'Booking' });
    expect(sheet).toBeTruthy();
    await user.click(within(sheet).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('asks for one day in the day view, and the calendar still shows when the cells fail', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/jobs?': () => ({ items: [job] }),
      'GET /api/v1/merchants/m1/calendar-cells': () => { throw { status: 500, body: {} }; },
      'GET /api/v1/merchants/m1/time-off': () => ({ entries: [] }),
      'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [] }),
    });
    renderWithProviders(<AppointmentsScreen />);
    expect(await screen.findByText('Brake inspection')).toBeTruthy();
    expect(screen.queryByText('Open slot')).toBeNull();
    await userEvent.setup({ delay: null }).click(screen.getByRole('radio', { name: 'Day' }));
    expect(calls.some(c => c.url.includes(`/calendar-cells?from=${today()}&days=1`))).toBe(true);
  });

  it('is in French', async () => {
    serve({ openSlots: [{ startsAt: localInstant(wednesday, '10:00') }], quoteHolds: [{ quoteId: 'q1', requestId: 'r1', ref: null, customerName: '', startsAt: localInstant(saturday, '10:00'), durationMin: 60 }] });
    renderWithProviders(<AppointmentsScreen />, 'fr');
    expect(await screen.findByText('Plage libre')).toBeTruthy();
    expect(screen.getByText('Réservé pour un devis')).toBeTruthy();
  });

  it('never shows the last week\'s jobs under the next week\'s title (S-117)', async () => {
    const nextJob = { ...job, id: 'j2', ref: 'BK-7801', title: 'Oil & filter', startsAt: localInstant(addDays(start, 9), '09:00'), endsAt: localInstant(addDays(start, 9), '10:00') };
    let release!: () => void;
    const nextWeek = new Promise<void>(r => { release = r; });
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/jobs?')) {
        if (new URL(url, 'http://x').searchParams.get('from') === localInstant(addDays(start, 7))) {
          await nextWeek;
          return json({ items: [nextJob] });
        }
        return json({ items: [job] });
      }
      if (url.includes('/calendar-cells')) return json({ openSlots: [], quoteHolds: [] });
      if (url.includes('/time-off')) return json({ entries: [] });
      return json({ items: [] });
    }));
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<AppointmentsScreen />);
    await user.click(await screen.findByRole('radio', { name: 'List' }));
    expect(await screen.findByText(/BK-7712/)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Next week' }));
    // this week's jobs are loading: the last week's rows are gone, the list says it is busy
    expect(screen.queryByText(/BK-7712/)).toBeNull();
    expect(document.querySelector('.nl-dt-body[aria-busy="true"]')).not.toBeNull();

    release();
    expect(await screen.findByText(/BK-7801/)).toBeTruthy();
    expect(document.querySelector('.nl-dt-body[aria-busy="true"]')).toBeNull();
  });
});
