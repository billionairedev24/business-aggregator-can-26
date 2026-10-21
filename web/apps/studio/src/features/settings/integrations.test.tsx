import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { PartnerAccess } from './PartnerAccess';
import { StockSync, conflictText } from './StockSync';
import type { PushView } from './integrationsApi';

let role = 'owner';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role, useMerchant: () => ({ id: 'm1', type: 'seller', role }) }));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}));

const I = '/api/v1/merchants/m1/listings/integrations';
const shopify = { provider: 'shopify', connected: true, available: true, state: 'connected', accountLabel: 'prairie-parts.myshopify.com', createdCount: 3, hiddenCount: 0, errors: [], syncStatus: 'ok', updates: 'webhooks' };
const push = (over: Partial<PushView> = {}): PushView => ({
  id: 'p1', offerId: 'o1', listingName: 'Synthetic motor oil', sku: 'OIL-5L', delta: -2, reason: 'sale', sourceRef: 'ord1', state: 'conflict',
  conflict: 'choose_location', detail: null, attempts: 1, platformAfter: null, applied: false, createdAt: '2026-10-20T15:00:00Z', ...over,
});
const status = (over: Record<string, unknown> = {}) => ({ provider: 'shopify', connected: true, locationId: null, locationName: null, canWrite: true, pending: 1, done: 4, attention: [push()], ...over });

afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });

describe('Settings › Stock sync (S-158)', () => {
  it('the owner picks the location; the waiting change shows why', async () => {
    let chosen = false;
    const after = () => status({ locationId: 'L2', locationName: 'Warehouse', attention: [], pending: 0 });
    const calls = mockFetch({
      [`GET ${I}/shopify/stock`]: () => (chosen ? after() : status()),
      [`GET ${I}/shopify/locations`]: () => ({ items: [{ id: 'L1', name: 'Main store', chosen: false }, { id: 'L2', name: 'Warehouse', chosen }] }),
      [`PUT ${I}/shopify/location`]: () => { chosen = true; return after(); },
      [`GET ${I}`]: () => ({ items: [shopify, { provider: 'square', connected: false }] }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<StockSync />);
    const section = (await screen.findByRole('heading', { name: /Stock sync with your store · Shopify/ })).closest('section')!;
    expect(await within(section).findByText('4 changes sent in the last 7 days · 1 waiting')).toBeTruthy();
    expect(within(section).getByText('1 change needs you')).toBeTruthy();
    expect(within(section).getByText(/Your store has several locations\. Choose one above\./)).toBeTruthy();
    expect(within(section).getByText(/Sale · -2 · OIL-5L/)).toBeTruthy();
    await user.selectOptions(await within(section).findByLabelText(/Location .* sells from/), 'Warehouse');
    await user.click(within(section).getByRole('button', { name: 'Use this location' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ locationId: 'L2' }));
    expect(await within(section).findByText('Every change reached Shopify.')).toBeTruthy();
    // only connected stores get a section
    expect(screen.queryByRole('heading', { name: /Square/ })).toBeNull();
  });

  it('retry and dismiss; an applied change can only be dismissed; a bookkeeper only reads', async () => {
    const calls = mockFetch({
      [`GET ${I}/shopify/stock`]: () => status({ locationId: 'L1', locationName: 'Main store', attention: [push({ id: 'p1', conflict: 'not_found' }), push({ id: 'p2', conflict: 'oversold', applied: true, platformAfter: -1, delta: -3 })] }),
      [`GET ${I}/shopify/locations`]: () => ({ items: [{ id: 'L1', name: 'Main store', chosen: true }] }),
      [`POST ${I}/stock-changes/p1/retry`]: () => push({ id: 'p1', state: 'pending', conflict: null }),
      [`GET ${I}`]: () => ({ items: [shopify] }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<StockSync />);
    expect(await screen.findByText('Your store has one location: Main store.')).toBeTruthy();
    expect(screen.getByText(/Shopify went below zero \(-1\)/)).toBeTruthy();
    expect(screen.getByText('Already fixed on Shopify? Dismiss it.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Retry Sale · -3/ })).toBeNull();
    expect(screen.getByRole('button', { name: /^Dismiss Sale · -3/ })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /^Retry Sale · -2/ }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/stock-changes/p1/retry'))).toBe(true));
  });

  it('a bookkeeper reads only; a grant without stock writes asks for a reconnect; French', async () => {
    role = 'bookkeeper';
    mockFetch({
      [`GET ${I}/shopify/stock`]: () => status({ canWrite: false, attention: [push({ conflict: 'scope_missing' })] }),
      [`GET ${I}`]: () => ({ items: [shopify] }),
    });
    renderWithProviders(<StockSync />, 'fr');
    expect(await screen.findByText(/Reconnectez Shopify pour que .* puisse y mettre votre stock à jour\./)).toBeTruthy();
    expect(screen.getByText(/n’a pas permis à .* de modifier le stock/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Réessayer/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Ignorer/ })).toBeNull();
  });

  it('words every conflict', () => {
    const t = ((k: string, v?: Record<string, unknown>) => `${k}:${JSON.stringify(v ?? {})}`) as never;
    expect(conflictText(push({ state: 'failed', attempts: 8 }), 'Square', t)).toBe('c_failed:{"platform":"Square","attempts":8}');
    expect(conflictText(push({ conflict: 'ambiguous' }), 'Square', t)).toBe('c_ambiguous:{"platform":"Square"}');
    expect(conflictText(push({ conflict: 'something_new' }), 'Square', t)).toBe('c_rejected:{"platform":"Square"}');
  });
});

describe('Settings › Partner access (S-158)', () => {
  const S = '/api/v1/merchants/m1/settings/partners';
  const consent = (over: Record<string, unknown> = {}) => ({ partnerId: 'pa1', displayName: 'Ledger Books', clientId: 'partner:ledger', scopes: ['api.read'], partnerState: 'active', state: 'requested', requestedAt: '2026-10-19T15:00:00Z', ...over });

  it('the owner approves a request and revokes an approval', async () => {
    const calls = mockFetch({
      [`POST ${S}/pa1/decision`]: (_u, init) => consent({ state: JSON.parse(String(init.body)).decision === 'approve' ? 'approved' : 'revoked', decidedAt: '2026-10-20T15:00:00Z' }),
      [`GET ${S}`]: () => ({ items: [consent()] }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PartnerAccess />);
    expect(await screen.findByText('Waiting for you')).toBeTruthy();
    expect(screen.getByText(/Can read your listings and orders/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Approve Ledger Books' }));
    expect(await screen.findByText('Approved')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Revoke Ledger Books' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST').map(c => c.body)).toEqual([{ decision: 'approve' }, { decision: 'revoke' }]));
  });

  it('a technician sees requests but can’t answer; empty and French', async () => {
    role = 'technician';
    mockFetch({ [`GET ${S}`]: () => ({ items: [consent({ partnerState: 'disabled' })] }) });
    renderWithProviders(<PartnerAccess />, 'fr');
    expect(await screen.findByText('En attente de votre réponse')).toBeTruthy();
    expect(screen.getByText('Seul le propriétaire peut répondre.')).toBeTruthy();
    expect(screen.getByText(/Désactivé par/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approuver/ })).toBeNull();
  });

  it('says when nobody asked', async () => {
    mockFetch({ [`GET ${S}`]: () => ({ items: [] }) });
    renderWithProviders(<PartnerAccess />);
    expect(await screen.findByText('No partner has asked to access your business.')).toBeTruthy();
  });
});
