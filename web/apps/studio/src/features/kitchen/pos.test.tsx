import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { goTo, type PosConnection, type PosPreview } from './api';
import { PosImportPanel } from './PosImport';

vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => 'owner' }));

const B = '/api/v1/merchants/m1';
const conn = (c: Partial<PosConnection> & Pick<PosConnection, 'provider'>): PosConnection => ({ kind: c.provider === 'toast' ? 'restaurant_id' : 'oauth', available: true, state: 'disconnected', ...c });
const none = { items: [conn({ provider: 'square' }), conn({ provider: 'clover', available: false }), conn({ provider: 'toast' })] };
const preview: PosPreview = {
  id: 'imp1', provider: 'square', menuId: 'menu1', status: 'preview',
  diff: {
    sections: [{ externalId: 's1', name: 'Pho', change: 'new' }, { externalId: 's2', name: 'Drinks', change: 'matched' }],
    groups: [{ externalId: 'g1', name: 'Size', change: 'new' }],
    items: [
      { externalId: 'i1', name: 'Pho tai', section: 'Pho', change: 'new', fields: [], priceCents: 1695 },
      { externalId: 'i2', name: 'Tofu banh mi', section: 'Banh mi', change: 'changed', fields: ['price'], priceCents: 1195, previousPriceCents: 999 },
      { externalId: 'i3', name: 'Bun bo Hue', change: 'removed', fields: [] },
      { externalId: 'i4', name: 'Soup of the day', section: 'Drinks', change: 'problem', fields: [], problem: 'No price in your POS — set one there, or add this item by hand.' },
      { externalId: 'i5', name: 'Salad rolls', section: 'Starters', change: 'unchanged', fields: [], priceCents: 899 },
    ],
    counts: { newItems: 1, changedItems: 1, unchangedItems: 1, removedItems: 1, problems: 1, newSections: 1, newGroups: 1, changedGroups: 0 },
  },
};

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('POS import (S-36)', () => {
  it('Connect sends the browser to Square; an unconfigured POS is not offered', async () => {
    const assign = vi.spyOn(goTo, 'assign').mockImplementation(() => undefined);
    const calls = mockFetch({ [`GET ${B}/pos/connections`]: () => none, [`POST ${B}/pos/square/connect`]: () => ({ authorizationUrl: 'https://connect.squareup.com/oauth2/authorize?state=s' }) });
    const user = userEvent.setup();
    renderWithProviders(<PosImportPanel merchantId="m1" menuId="menu1" canManage />);
    const clover = (await screen.findByText('Clover')).closest('li')!;
    expect(within(clover).getByText('Not available yet')).toBeTruthy();
    await user.click(within(screen.getByText('Square').closest('li')!).getByRole('button', { name: 'Connect' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://connect.squareup.com/oauth2/authorize?state=s'));
    expect(calls.find(c => c.url.endsWith('/pos/square/connect'))?.body).toEqual({ menuId: 'menu1' });
  });

  it('Toast asks for the restaurant GUID and shows the server’s message', async () => {
    mockFetch({
      [`GET ${B}/pos/connections`]: () => none,
      [`POST ${B}/pos/toast/connect`]: () => { throw { status: 422, body: { errors: [{ field: 'restaurantId', rule: 'linked', message: 'Northline can\'t read this restaurant yet. Turn on the Northline integration in Toast, then try again.' }] } }; },
    });
    const user = userEvent.setup();
    renderWithProviders(<PosImportPanel merchantId="m1" menuId="menu1" canManage />);
    await user.click(within((await screen.findByText('Toast')).closest('li')!).getByRole('button', { name: 'Connect' }));
    const input = screen.getByLabelText('Toast restaurant GUID');
    await user.type(input, 'nope');
    await user.click(screen.getByRole('button', { name: 'Link restaurant' }));
    expect(screen.getByRole('alert').textContent).toBe('Enter your Toast restaurant GUID (Toast Web › Integrations).');
    await user.clear(input);
    await user.type(input, '2b8a3c4d-1111-4e2f-9a0b-123456789abc');
    await user.click(screen.getByRole('button', { name: 'Link restaurant' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Northline can\'t read this restaurant yet. Turn on the Northline integration in Toast, then try again.'));
  });

  it('reviews the diff and applies it', async () => {
    const calls = mockFetch({
      [`GET ${B}/pos/connections`]: () => ({ items: [conn({ provider: 'square', state: 'connected', accountLabel: 'Pho Dau Bo' }), conn({ provider: 'clover' }), conn({ provider: 'toast' })] }),
      [`POST ${B}/menus/menu1/pos-imports`]: () => preview,
      [`POST ${B}/pos-imports/imp1/apply`]: () => ({ itemsCreated: 1, itemsUpdated: 1, itemsHidden: 1, sectionsCreated: 1, groupsCreated: 1, groupsUpdated: 0, skipped: 1 }),
    });
    const user = userEvent.setup();
    renderWithProviders(<PosImportPanel merchantId="m1" menuId="menu1" canManage={false} />);
    const square = (await screen.findByText('Connected · Pho Dau Bo')).closest('li')!;
    expect(within(square).queryByRole('button', { name: 'Disconnect' })).toBeNull(); // cooks import, owners connect
    await user.click(within(square).getByRole('button', { name: 'Review import' }));
    expect(await screen.findByRole('heading', { name: 'Review changes from Square' })).toBeTruthy();
    expect(screen.getByText('1 new dish · 1 changed · 1 gone from Square · 1 unchanged')).toBeTruthy();
    expect(screen.getByText('New sections: Pho')).toBeTruthy();
    expect(screen.getByText('price · $9.99 → $11.95')).toBeTruthy();
    expect(screen.getByText('Hidden — gone from your POS')).toBeTruthy();
    expect(screen.getByText('No price in your POS — set one there, or add this item by hand.')).toBeTruthy();
    expect(screen.queryByText('Salad rolls')).toBeNull(); // unchanged dishes aren't listed
    await user.click(screen.getByRole('button', { name: 'Apply 5 changes' }));
    expect(await screen.findByText('Applied · 1 added as drafts, 1 updated, 1 hidden.')).toBeTruthy();
    expect(calls.some(c => c.url.endsWith('/pos-imports/imp1/apply'))).toBe(true);
  });

  it('shows the callback outcome in French', async () => {
    mockFetch({ [`GET ${B}/pos/connections`]: () => none });
    renderWithProviders(<PosImportPanel merchantId="m1" menuId="menu1" canManage returned={{ pos: 'clover', result: 'denied' }} />, 'fr');
    expect(await screen.findByText('Vous n’avez pas autorisé l’accès sur Clover. Rien n’a été connecté.')).toBeTruthy();
    expect(screen.getByText(/Les données du PDV ne sont pas fiables pour les allergènes/)).toBeTruthy();
  });
});
