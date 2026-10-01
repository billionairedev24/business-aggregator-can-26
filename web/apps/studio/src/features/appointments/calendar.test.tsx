import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { addDays, localInstant, mondayOf, today } from '../../lib/time';
import { AppointmentsScreen } from './AppointmentsScreen';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('../../lib/session', () => ({ useSession: () => ({ data: { user: { id: 'u1' } } }) }));

const monday = mondayOf(today());
const wednesday = addDays(monday, 2);
const saturday = addDays(monday, 5);
const job = { id: 'j1', ref: 'BK-7712', title: 'Brake inspection', startsAt: localInstant(wednesday, '07:00'), endsAt: localInstant(wednesday, '08:00'), state: 'confirmed', memberUserId: 'u1', memberName: 'Ravi', customerName: 'A. Osei', area: null, priceCents: 8900 };

function serve(cells: unknown) {
  return mockFetch({
    'GET /api/v1/merchants/m1/jobs?': () => ({ items: [job] }),
    'GET /api/v1/merchants/m1/calendar-cells': () => cells,
    'GET /api/v1/merchants/m1/time-off': () => ({ entries: [] }),
    'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [] }),
  });
}

describe('Appointments calendar cells (S-74)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows open slots and quote holds as dim cells in time order next to the jobs', async () => {
    const calls = serve({
      openSlots: [{ startsAt: localInstant(wednesday, '10:00') }],
      quoteHolds: [{ quoteId: 'q1', requestId: 'r1', ref: 'QT-3104', customerName: 'M. Tran', startsAt: localInstant(saturday, '10:00'), durationMin: 120 }],
    });
    renderWithProviders(<AppointmentsScreen />);
    expect(await screen.findByText('Open slot')).toBeTruthy();
    const wed = screen.getAllByRole('group').find(g => (g.getAttribute('aria-label') ?? '').startsWith('Wednesday'))!;
    const cells = within(wed).getAllByText(/Brake inspection|Open slot/).map(e => e.closest('.nl-appt-job')!);
    expect(cells.map(c => c.textContent)).toEqual([expect.stringContaining('Brake inspection'), expect.stringContaining('Open slot')]);
    expect(cells[1]!.getAttribute('data-dim')).toBe('true');
    expect(cells[1]!.tagName).toBe('DIV'); // information, not a button
    const sat = screen.getAllByRole('group').find(g => (g.getAttribute('aria-label') ?? '').startsWith('Saturday'))!;
    expect(within(sat).getByText('Held for quote · M. Tran')).toBeTruthy();
    expect(calls.some(c => c.url.includes(`/calendar-cells?from=${monday}&days=7`))).toBe(true);
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
});
