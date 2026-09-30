import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { InviteAcceptScreen } from './InviteAcceptScreen';

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async orig => ({ ...(await orig<typeof import('@tanstack/react-router')>()), useNavigate: () => navigate }));
vi.mock('../../lib/session', async orig => ({ ...(await orig<typeof import('../../lib/session')>()), useSession: () => ({ data: { user: { email: 'sam@example.com' } } }), useSignOut: () => vi.fn() }));

const preview = (over: object = {}) => ({ merchantId: 'm1', businessName: 'Prairie Wrench', businessType: 'provider', role: 'technician', state: 'pending', forYou: true, ...over });

describe('Team invitation', () => {
  afterEach(() => { vi.unstubAllGlobals(); navigate.mockReset(); });

  it('joins the team and opens the business', async () => {
    mockFetch({ 'GET /api/v1/team-invitations/tok': () => preview(), 'POST /api/v1/team-invitations/tok/accept': () => ({ merchantId: 'm1', role: 'technician' }) });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<InviteAcceptScreen token="tok" />);
    expect(await screen.findByRole('heading', { name: 'Join Prairie Wrench on Northline' })).toBeTruthy();
    expect(screen.getByText(/invited as a technician/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Join Prairie Wrench' }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/b/m1' }));
  });

  it('explains when the invitation was sent to someone else, or has expired', async () => {
    mockFetch({ 'GET /api/v1/team-invitations/tok': () => preview({ forYou: false }) });
    renderWithProviders(<InviteAcceptScreen token="tok" />);
    expect(await screen.findByText(/sent to a different email or mobile than sam@example.com/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Join Prairie Wrench' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows expired and unknown links', async () => {
    mockFetch({ 'GET /api/v1/team-invitations/old': () => preview({ state: 'expired' }), 'GET /api/v1/team-invitations/bad': () => { throw { status: 404, body: { code: 'not_found' } }; } });
    const { unmount } = renderWithProviders(<InviteAcceptScreen token="old" />);
    expect(await screen.findByText('This invitation has expired. Ask the owner for a new one.')).toBeTruthy();
    unmount();
    renderWithProviders(<InviteAcceptScreen token="bad" />);
    expect(await screen.findByText(/This invitation link isn’t valid/)).toBeTruthy();
  });
});
