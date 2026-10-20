import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { JobPanel } from './JobPanel';
import { QuoteRequests } from './QuoteRequests';
import { RulesTab } from '../availability/RulesTab';

const navigate = vi.fn();
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }));
vi.mock('../../lib/session', () => ({ useSession: () => ({ data: { user: { id: 'u1' } } }) }));

const request = { id: 'r1', ref: 'QT-3104', title: 'Alternator, 2016 Civic', customerName: 'M. Tran', reliability: null, area: null, body: 'Battery light on', preferredAt: null, createdAt: '2026-09-29T15:00:00Z', respondBy: null, expiresAt: null, quote: null, photoIds: ['ph_1', 'ph_2'] };
const job = {
  id: 'j1', ref: 'BK-7712', title: 'Brake inspection', startsAt: '2026-10-07T13:00:00Z', endsAt: '2026-10-07T14:00:00Z', state: 'confirmed',
  memberUserId: 'u1', memberName: 'Ravi', customerName: 'A. Osei', area: null, priceCents: 30000, escrow: 'held', timeline: [], approvals: [],
  customerPhotoIds: ['ph_9'],
  balance: { state: 'failed', cents: 20000, taxCents: 1000, dueAt: '2026-10-05T13:00:00Z', attempts: 3, failure: 'declined' },
};

/** S-153: the customer's photos, "Ask a question" / "Message" opening the customer's own thread, the balance's state. */
describe('Studio — quotes and bookings (S-153)', () => {
  afterEach(() => { vi.unstubAllGlobals(); navigate.mockReset(); });

  it('shows the request’s photos through the business’s scope and asks in the customer’s thread', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [request] }),
      'POST /api/v1/merchants/m1/quote-requests/r1/thread': () => ({ threadId: 'th_7' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<QuoteRequests />);
    const photo = await screen.findByRole('img', { name: 'Customer photo 2' });
    expect(photo.getAttribute('src')).toBe('/api/v1/merchants/m1/customer-photos/ph_2');
    await user.click(screen.getByRole('button', { name: 'Ask a question' }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ search: { thread: 'th_7' } })));
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/quote-requests/r1/thread'))).toBe(true);
  });

  it('says when the conversation couldn’t be opened', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/quote-requests': () => ({ items: [request] }),
      'POST /api/v1/merchants/m1/quote-requests/r1/thread': () => { throw { status: 503, body: { detail: 'down' } }; },
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<QuoteRequests />);
    await user.click(await screen.findByRole('button', { name: 'Ask a question' }));
    expect(await screen.findByText('The conversation couldn’t be opened. Try again.')).toBeTruthy();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows a balance that couldn’t be held, the job’s photos, and messages the job’s thread', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/jobs/j1': () => job,
      'POST /api/v1/merchants/m1/jobs/j1/thread': () => ({ threadId: 'th_8' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<JobPanel jobId="j1" />);
    expect(await screen.findByText('$210.00 · not held after 3 tries — talk to the customer before you go')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Customer photo 1' }).getAttribute('src')).toBe('/api/v1/merchants/m1/customer-photos/ph_9');
    await user.click(screen.getByRole('button', { name: 'Message' }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ search: { thread: 'th_8' } })));
  });

  it('shows a late cancellation and its fee, in French', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/jobs/j1': () => ({ ...job, state: 'cancelled', balance: { ...job.balance, state: 'cancelled' }, cancellation: { at: '2026-10-06T20:00:00Z', feeCents: 4450, feeTaxCents: 223 } }) });
    renderWithProviders(<JobPanel jobId="j1" />, 'fr');
    expect(await screen.findByText(/tardive — frais de 46,73\s\$ retenus pour vous/)).toBeTruthy();
    expect(screen.getByText(/210,00\s\$ · libéré \(réservation annulée\)/)).toBeTruthy();
  });

  it('offers only the cancellation terms the region allows', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/availability/rules': () => ({ intervalMin: 30, bufferMin: 20, minNoticeMin: 60, horizonDays: 14, maxJobsPerDay: 5, acceptMode: 'instant', rescheduleFreeMin: 180, lateCancelFeeCents: 0, holidayPremiumCents: 5000, serviceAreas: [], zones: [], maxCancelWindowHours: 12, maxCancelFeeBps: 2500 }) });
    renderWithProviders(<RulesTab onState={vi.fn()} />);
    const window = await screen.findByRole('combobox', { name: 'Customer reschedules free until' });
    expect([...window.querySelectorAll('option')].map(o => o.textContent)).toEqual(['3 hours before', '12 hours before']);
    const fee = screen.getByRole('combobox', { name: /Late-cancellation fee/ });
    expect([...fee.querySelectorAll('option')].map(o => o.textContent)).not.toContain('50% of job');
    expect(screen.getByText(/Customers see these terms before they book/)).toBeTruthy();
  });
});
