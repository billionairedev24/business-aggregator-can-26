import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { OrdersScreen, courierText } from './OrdersScreen';
import { useOrdersT } from './messages';
import { renderHook } from '@testing-library/react';

let role = 'owner';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role }));

const inHours = (h: number) => new Date(Date.now() + h * 3600_000).toISOString();
const order = (id: string, ref: string, status: string, extra: object = {}) => ({
  id, ref, customerName: 'A. Osei', area: 'Beltline', lines: [{ id: `${id}l`, title: 'Wiper blades', qty: 2, unitCents: 1995, state: status === 'to_pack' ? 'pending' : 'packed' }],
  totalCents: 3990, windowStartsAt: inHours(3), cutoffAt: inHours(2.75), runLabel: 'R-611', placedAt: inHours(-2), deliveredAt: null, status, issueNote: null, ...extra,
});
const board = () => ({ items: [order('o1', 'NL-48213', 'to_pack'), order('o2', 'NL-48201', 'awaiting_pickup'), order('o3', 'NL-48188', 'issue', { issueNote: 'wrong size' })], counts: { toPack: 1, awaitingPickup: 1, deliveredToday: 6, issues: 1, nextCutoff: inHours(2.75), nextRunLabel: 'R-611' } });

describe('Orders · products', () => {
  afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });

  it('lists the packing list with chips, and Mark packed moves the row to Awaiting pickup', async () => {
    let packed = false;
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/orders': () => { const b = board(); if (packed) b.items[0] = order('o1', 'NL-48213', 'awaiting_pickup'); return b; },
      'POST /api/v1/merchants/m1/orders/o1/pack': () => { packed = true; return order('o1', 'NL-48213', 'awaiting_pickup'); },
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    expect(await screen.findByText('To pack · 1')).toBeTruthy();
    expect(screen.getByText('Delivered today · 6')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/^Pack by /);
    expect(screen.getAllByText('Issue · wrong size').length).toBeGreaterThan(0);

    // jsdom has no layout width, so the table renders as cards: the inline action is a labelled button.
    await user.click(screen.getByRole('button', { name: /^Mark packed/ }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/o1/pack'))).toBe(true));
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Mark packed/ })).toBeNull());
    expect(screen.getAllByText('Awaiting pickup')).toHaveLength(2);
  });

  it('bookkeepers see the list without the action', async () => {
    role = 'bookkeeper';
    mockFetch({ 'GET /api/v1/merchants/m1/orders': () => board() });
    renderWithProviders(<OrdersScreen />);
    expect(await screen.findByText('To pack · 1')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Mark packed/ })).toBeNull();
  });

  it('shows an error with retry', async () => {
    mockFetch({});
    renderWithProviders(<OrdersScreen />);
    expect(await screen.findByText("We couldn't load your orders.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('the order shows its pack-by time and the courier’s pickup at the shop (S-86)', async () => {
    const pickup = { courierAssigned: true, runLabel: 'R-611', eta: inHours(3), arrivedAt: null, pickedUpAt: null };
    mockFetch({ 'GET /api/v1/merchants/m1/orders': () => ({ ...board(), items: [order('o1', 'NL-48213', 'awaiting_pickup', { courierPickup: pickup })] }) });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<OrdersScreen />);
    await user.click(await screen.findByText('NL-48213'));
    expect(await screen.findByText('Pack by')).toBeTruthy();
    expect(screen.getByText('Courier pickup')).toBeTruthy();
    expect(screen.getByText(/^R-611 · Courier due /)).toBeTruthy();
  });

  it('words the courier’s pickup in en and fr', () => {
    const p = { courierAssigned: false, runLabel: null, eta: '2026-10-01T23:50:00Z', arrivedAt: null, pickedUpAt: null };
    const en = renderHook(() => useOrdersT(), { wrapper: ({ children }) => <>{children}</> }).result.current;
    expect(courierText(p, en, 'en')).toMatch(/^Finding a courier · pickup about /);
    expect(courierText({ ...p, courierAssigned: true, arrivedAt: '2026-10-01T23:49:00Z' }, en, 'en')).toBe('Courier is here');
    expect(courierText({ ...p, pickedUpAt: '2026-10-01T23:55:00Z' }, en, 'en')).toMatch(/^Picked up /);
  });
});
