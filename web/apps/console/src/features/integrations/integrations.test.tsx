import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi } from '../../test/render';
import type { Key } from './api';

const key = (o: Partial<Key>): Key => ({
  id: 'k1', merchantId: 'm1', businessName: 'Prairie Wrench', name: 'embed', scopes: ['storefront:read', 'booking:write'], prefix: 'nl_live_',
  rateLimit: 600, createdAt: '2026-09-01T00:00:00Z', lastUsedAt: '2026-09-08T17:00:00Z', revokedAt: null, ...o,
});
const KEYS = [key({}), key({ id: 'k2', merchantId: 'm2', businessName: 'Bridgeland Butcher', name: 'POS', scopes: ['orders:read'], lastUsedAt: null, revokedAt: '2026-09-05T00:00:00Z' })];

describe('API & webhooks (S-96, design 03)', () => {
  it('lists every business’s keys, issues one (shown once) and revokes one', async () => {
    const calls = staffApi(['admin'], c => {
      if (c.method === 'GET' && c.url.endsWith('/api/v1/console/api-keys')) return { body: { items: KEYS } };
      if (c.method === 'POST' && c.url.endsWith('/api/v1/console/api-keys')) return { status: 201, body: { key: key({ id: 'k3' }), secret: 'nl_live_FAKE_SECRET_FOR_TESTS' } };
      if (c.url.endsWith('/revoke')) return { body: key({ revokedAt: '2026-09-08T18:00:00Z' }) };
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/integrations');
    expect(await screen.findByRole('heading', { level: 1, name: "Every screen you've seen runs on this API." })).toBeTruthy();
    const row = screen.getByText('Prairie Wrench · embed').closest('tr, .nl-dt-card') as HTMLElement;
    expect(within(row).getByText('storefront:read booking:write')).toBeTruthy();
    expect(within(screen.getByText('Bridgeland Butcher · POS').closest('tr, .nl-dt-card') as HTMLElement).getByText('Revoked')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Issue key' }));
    let dialog = await screen.findByRole('dialog', { name: 'Issue a key for a business' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Business id' }), 'm1');
    await user.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'POS sync');
    await user.click(within(dialog).getByRole('checkbox', { name: 'orders:read' }));
    await user.click(within(dialog).getByRole('button', { name: 'Issue' }));
    dialog = await screen.findByRole('dialog', { name: 'Copy the key now' });
    expect((within(dialog).getByRole('textbox') as HTMLInputElement).value).toBe('nl_live_FAKE_SECRET_FOR_TESTS');
    expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/api-keys'))?.body).toEqual({ merchantId: 'm1', name: 'POS sync', scopes: ['orders:read'] });
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));

    await user.click(within(row).getByRole('button', { name: 'Revoke' }));
    dialog = await screen.findByRole('dialog', { name: 'Revoke embed?' });
    await user.click(within(dialog).getByRole('button', { name: 'Revoke key' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api-keys/k1/revoke'))).toBe(true));
  });

  it('is admin only', async () => {
    staffApi(['finance'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/integrations');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
  });
});
