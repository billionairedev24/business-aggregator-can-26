import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const step = (key: string, state: string) => ({ key, state, action: null, owner: null, params: {} });
const ROW = {
  id: 'P9', label: 'Bow Bakery', businessName: 'Bow Bakery', businessType: 'seller', marketId: 'mkt-pilotville', merchantId: 'M9', city: 'Pilotville', stage: 'approved',
  next: step('live', 'todo'), checklist: [step('invited', 'done'), step('live', 'todo')], blocked: false, blocker: null, blockerOwner: null, blockerSince: null, ownerId: null,
  ownerName: null, inviteState: null, listings: 2, listingsLive: 2, createdAt: '2026-10-01T12:00:00Z',
};
const BOARD = { markets: [{ id: 'mkt-pilotville', city: 'Pilotville', province: 'XA', stage: 'pilot' }], marketId: null, stages: { approved: 0 }, live: 0, blocked: 0, items: [] };
const SELLER = { id: 'M9', name: 'Bow Bakery', type: 'seller', province: 'XA', city: 'Pilotville', tier: 'registered', status: 'active', quality: null, gmv90Cents: 0, disputeRate: null, flags: [], category: null, searchHidden: null };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && /\/api\/v1\/console\/pilot(\?|$)/.test(c.url)) return { body: BOARD };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/sellers?q=')) return { body: { asOf: '2026-10-20T00:00:00Z', active: 1, atRisk: 0, items: [SELLER], truncated: false } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/pilot/P9')) return { body: { row: ROW, notes: [], invites: [], visits: [], visitItems: [], kitchenVisitRequired: false } };
    return undefined;
  });
}

describe('pilot › enrol an existing business (S-158, S-120)', () => {
  it('finds the business by name, picks the market and enrols it', async () => {
    const calls = api(['merchant_success'], c => (c.method === 'POST' && c.url.endsWith('/api/v1/console/pilot/enrolments')
      ? { status: 201, body: { row: ROW, notes: [], invites: [], visits: [], visitItems: [], kitchenVisitRequired: false } } : undefined));
    renderConsole('/pilot');
    const u = userEvent.setup({ delay: null });
    await u.click(await screen.findByRole('button', { name: 'Enrol a business' }));
    const dialog = await screen.findByRole('dialog', { name: 'Enrol an existing business' });
    await u.type(within(dialog).getByLabelText('Find the business'), 'Bow');
    await u.click(await within(dialog).findByRole('button', { name: 'Choose Bow Bakery' }));
    expect(within(dialog).getByText(/Chosen: Bow Bakery/)).toBeTruthy();
    await u.click(within(dialog).getByRole('button', { name: 'Enrol' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/enrolments'))?.body).toEqual({ businessId: 'M9', marketId: 'mkt-pilotville' }));
    expect(await screen.findByText('Bow Bakery joined the pilot.')).toBeTruthy();
  });

  it('trust & safety read the board without enrolling', async () => {
    api(['trust_safety']);
    renderConsole('/pilot');
    await screen.findByRole('link', { name: /CSV/ });
    expect(screen.queryByRole('button', { name: 'Enrol a business' })).toBeNull();
  });
});
