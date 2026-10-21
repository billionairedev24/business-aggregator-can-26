import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { JobPanel } from './JobPanel';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('../../lib/session', () => ({ useSession: () => ({ data: { user: { id: 'u1' } } }) }));

const job = (startsAt: string, noShows = 0) => ({
  id: 'j1', ref: 'BK-7712', title: 'Brake inspection', startsAt, endsAt: startsAt, state: 'confirmed',
  memberUserId: 'u1', memberName: 'Ravi', customerName: 'A. Osei', area: null, priceCents: 30000, escrow: 'held',
  timeline: [], approvals: [], customer: { name: 'A. Osei', reliability: 4.1, pastJobs: 2, noShows },
});

/** S-156: providers see a customer's confirmed no-shows, and report one 15 minutes after the start. */
describe('Job card no-shows', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the warning, and reports a no-show once it is late enough', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/jobs/j1': () => job(new Date(Date.now() - 30 * 60_000).toISOString(), 2),
      'POST /api/v1/merchants/m1/jobs/j1/customer-no-show': () => undefined,
    });
    renderWithProviders(<JobPanel jobId="j1" />);
    expect(await screen.findByText('2 confirmed no-shows')).toBeTruthy();
    await userEvent.setup({ delay: null }).click(screen.getByRole('button', { name: "The customer didn't show" }));
    expect(await screen.findByText('Northline trust & safety will look at it. Confirmed no-shows count against the customer.')).toBeTruthy();
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/customer-no-show'))).toBe(true);
    expect(screen.queryByRole('button', { name: "The customer didn't show" })).toBeNull();
  });

  it('no report before start + 15 minutes, in French too', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/jobs/j1': () => job(new Date(Date.now() + 60 * 60_000).toISOString()) });
    renderWithProviders(<JobPanel jobId="j1" />, 'fr');
    await screen.findByText(/A\. Osei/);
    expect(screen.queryByRole('button', { name: 'Le client ne s’est pas présenté' })).toBeNull();
    expect(screen.queryByText(/absence/)).toBeNull();
  });
});
