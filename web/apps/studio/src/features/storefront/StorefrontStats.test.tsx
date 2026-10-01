import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MERCHANT, storefront } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { StorefrontScreen } from './StorefrontScreen';

const role = vi.hoisted(() => ({ value: 'owner' }));
vi.mock('../shell/api', async importOriginal => ({
  ...(await importOriginal<typeof import('../shell/api')>()),
  useMerchantId: () => MERCHANT,
  useMerchant: () => ({ id: MERCHANT, type: 'provider' }),
  useRole: () => role.value,
}));

beforeEach(() => { vi.unstubAllGlobals(); role.value = 'owner'; });

const stats = { visits: 1204, booked: 104, bookedRateBps: 864, daily: [] };
const running = { active: true, multiplier: 2, label: 'brake jobs', endsOn: '2026-10-31', running: true };

function serve({ visits = stats as unknown, reward = undefined as unknown, put = (b: unknown) => ({ body: { ...(b as object), running: (b as { active: boolean }).active } }) as { status?: number; body?: unknown } } = {}) {
  return mockFetch(call => {
    if (call.url.endsWith(`/merchants/${MERCHANT}/storefront`)) return { body: storefront({ publishedAt: null }) };
    if (call.url.endsWith('/storefront-stats')) return { body: visits };
    if (call.url.endsWith('/reward') && call.method === 'PUT') return put(call.body);
    if (call.url.endsWith('/reward')) return { body: reward ?? null }; // never set (the server's 204; the helper cannot build an empty 204)
    return undefined;
  });
}

describe('Storefront stats and reward (S-75)', () => {
  it('leads with the 30-day visits and the booked rate', async () => {
    serve();
    renderWithProviders(<StorefrontScreen />);
    expect(await screen.findByText(/^1,204 visits last 30 days · 8\.6% booked\. Not published yet\./)).toBeTruthy();
  });

  it('says when there were no visits yet', async () => {
    serve({ visits: { visits: 0, booked: 0, bookedRateBps: null, daily: [] } });
    renderWithProviders(<StorefrontScreen />);
    expect(await screen.findByText(/^No visits in the last 30 days yet\./)).toBeTruthy();
  });

  it('switches the reward on with its terms', async () => {
    const calls = serve();
    renderWithProviders(<StorefrontScreen />);
    const user = userEvent.setup();
    expect(await screen.findByText('Off')).toBeTruthy();
    await user.selectOptions(screen.getByLabelText('Points'), '3');
    await user.type(screen.getByLabelText('On'), 'brake jobs');
    await user.clear(screen.getByLabelText('Until'));
    await user.type(screen.getByLabelText('Until'), '2026-10-31');
    await user.click(screen.getByRole('switch', { name: 'Reward on' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ active: true, multiplier: 3, label: 'brake jobs', endsOn: '2026-10-31' }));
    expect(await screen.findByText('Running · 3× points until Oct 31, 2026')).toBeTruthy();
  });

  it('shows the server messages next to the field', async () => {
    serve({ put: () => ({ status: 422, body: { errors: [{ field: 'endsOn', message: 'Pick an end date within the next 90 days.' }] } }) });
    renderWithProviders(<StorefrontScreen />);
    await userEvent.click(await screen.findByRole('switch', { name: 'Reward on' }));
    expect(await screen.findByText('Pick an end date within the next 90 days.')).toBeTruthy();
  });

  it('is read-only for a technician, in French', async () => {
    role.value = 'technician';
    serve({ reward: running });
    renderWithProviders(<StorefrontScreen />, { locale: 'fr' });
    expect(await screen.findByText('En cours · points ×2 jusqu’au 31 oct. 2026')).toBeTruthy();
    expect((screen.getByRole('switch', { name: 'Récompense active' }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/Seul le propriétaire peut financer des récompenses\./)).toBeTruthy();
    expect(await screen.findByText(/^1 204 visites ces 30 derniers jours · 8,6 % ont réservé\./)).toBeTruthy();
  });
});
