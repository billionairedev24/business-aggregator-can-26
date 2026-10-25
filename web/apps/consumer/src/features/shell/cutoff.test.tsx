import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { SAVED_KEY } from '../location/useDeliveryLocation';
import { HomeScreen } from '../home/HomeScreen';

const RUN = { windowId: 'W1', label: 'R-1', day: 'today', startsAt: '2026-10-20T00:00:00Z', endsAt: '2026-10-20T03:00:00Z', orderBy: '2026-10-19T23:20:00Z', feeCents: 299, households: 5 };
const LANDING = { market: 'Calgary', served: true, run: RUN, shopCount: 1, departments: [], shops: [], popular: [] };
const api = (landing: unknown = LANDING) => (c: Call) => {
  if (c.url === '/bff/session') return { body: { user: null, guestId: 'g_1' } };
  if (c.url.startsWith('/api/v1/public/shop?')) return { body: landing };
  return undefined;
};

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(SAVED_KEY, JSON.stringify({ label: 'Beltline, Calgary', city: 'Calgary', province: 'AB' }));
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('delivery cut-off strip (E-15)', () => {
  it('says when the next pooled run closes and counts down: the run card on the home (S-188), the strip on the Shop pages', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-19T20:32:00Z') });
    mockFetch(api());
    const { unmount } = renderApp('/', { routes: { home: () => <HomeScreen /> } });
    const card = await screen.findByRole('complementary', { name: 'Next pooled run' });
    await within(card).findByText('2 h 48 min left');
    expect(card).toHaveTextContent('Tonight’s pooled run');
    expect(card).toHaveTextContent('Mix shops for one $2.99 delivery');
    expect(screen.queryByRole('complementary', { name: 'Delivery cut-off' })).toBeNull();
    unmount();
    renderApp('/shop');
    const strip = await screen.findByRole('complementary', { name: 'Delivery cut-off' });
    expect(strip).toHaveTextContent(/Tonight’s pooled run to Beltline closes at/);
    expect(strip).toHaveTextContent('Mix shops for one $2.99 delivery.');
    expect(within(strip).getByText('2 h 48 min left')).toBeInTheDocument();
  });

  it('is not shown once the order-by time has passed, nor off the home and Shop pages', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-19T23:30:00Z') });
    mockFetch(api());
    const { unmount } = renderApp('/');
    await screen.findByRole('banner');
    await new Promise(r => setTimeout(r, 50));
    expect(screen.queryByRole('complementary', { name: 'Delivery cut-off' })).toBeNull();
    unmount();
    vi.setSystemTime(new Date('2026-10-19T20:32:00Z'));
    renderApp('/help');
    await screen.findByRole('banner');
    await new Promise(r => setTimeout(r, 50));
    expect(screen.queryByRole('complementary', { name: 'Delivery cut-off' })).toBeNull();
  });
});
