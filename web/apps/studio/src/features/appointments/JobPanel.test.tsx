import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { JobPanel } from './JobPanel';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('../../lib/session', () => ({ useSession: () => ({ data: { user: { id: 'u1' } } }) }));

const job = {
  id: 'j1', ref: 'BK-7712', title: 'Brake inspection', startsAt: '2026-10-07T13:00:00Z', endsAt: '2026-10-07T14:00:00Z', state: 'confirmed',
  memberUserId: 'u1', memberName: 'Ravi', customerName: 'A. Osei', area: null, priceCents: 30000, escrow: 'held',
  timeline: [], approvals: [],
};

/** Engineering follow-ups (S-117 finding): the job card showed the price before GST ($300.00) where the customer saw $315.00 held. */
describe('Job card escrow', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows what the customer paid and is held, then the net after GST/HST and the fee — the ledger’s figures', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/jobs/j1': () => ({ ...job, escrowMoney: { state: 'held', heldCents: 31500, taxCents: 1500, feeCents: 2700, netCents: 27300 } }) });
    renderWithProviders(<JobPanel jobId="j1" />);
    expect(await screen.findByText('$315.00 held')).toBeTruthy();
    expect(screen.getByText('Incl. $15.00 GST/HST · $273.00 to you after the tax and Northline’s $27.00 fee')).toBeTruthy();
    expect(screen.queryByText('$300.00 held')).toBeNull();
  });

  it('says the money is on hold while a case is open, in French too', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/jobs/j1': () => ({ ...job, escrow: 'disputed', escrowMoney: { state: 'disputed', heldCents: 31500, taxCents: 1500, feeCents: 2700, netCents: 27300 } }) });
    renderWithProviders(<JobPanel jobId="j1" />, 'fr');
    expect(await screen.findByText(/315,00\s\$ bloqués \(dossier ouvert\)/)).toBeTruthy();
  });
});
