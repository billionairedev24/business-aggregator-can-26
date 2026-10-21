import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { parseIds } from './partnersApi';

const partner = (o: Record<string, unknown> = {}) => ({
  id: 'PA1', name: 'ledger', clientId: 'partner:ledger', displayName: 'Ledger Books', jwkSetUrl: 'https://ledger.example.invalid/jwks.json', scopes: ['api.read'],
  tokenTtlSeconds: 900, state: 'active', keyVersion: 1, rotatedAt: null, createdAt: '2026-10-19T15:00:00Z',
  businesses: [{ merchantId: 'M1', businessName: 'Prairie Wrench', state: 'approved', requestedAt: '2026-10-19T15:00:00Z', decidedAt: '2026-10-19T16:00:00Z' },
    { merchantId: 'M2', businessName: 'Bow Bakery', state: 'requested', requestedAt: '2026-10-19T15:00:00Z', decidedAt: null }], ...o,
});

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/api-keys')) return { body: { items: [] } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/partners')) return { body: { items: [partner()] } };
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('API & webhooks › Partners (S-158)', () => {
  it('lists partners with their businesses and keys; an admin registers one', async () => {
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && c.url.endsWith('/api/v1/console/partners')) {
        return (c.body as { name: string }).name === 'taken'
          ? { status: 409, body: { code: 'partner_exists', detail: 'A partner with this name already exists.' } }
          : { status: 201, body: partner({ id: 'PA2', name: 'stock-bot' }) };
      }
      return undefined;
    });
    renderConsole('/integrations');
    const row = (await screen.findByText('Ledger Books')).closest('tr, .nl-dt-card') as HTMLElement;
    await expectNoAxeViolations(document.body);
    expect(within(row).getByText('1 approved · 1 waiting')).toBeTruthy();
    expect(within(row).getByText('Version 1')).toBeTruthy();
    const u = user();
    await u.click(screen.getByRole('button', { name: 'Register partner' }));
    const dialog = await screen.findByRole('dialog', { name: 'Register a partner' });
    await u.type(within(dialog).getByLabelText(/Name \(client id\)/), 'taken');
    await u.type(within(dialog).getByLabelText('Display name'), 'Stock Bot');
    await u.type(within(dialog).getByLabelText('JWK Set URL'), 'https://bot.example.invalid/jwks');
    await u.click(within(dialog).getByRole('checkbox', { name: /api.write/ }));
    await u.type(within(dialog).getByLabelText('Business ids'), 'M1\nM2, M1');
    await u.click(within(dialog).getByRole('button', { name: 'Register' }));
    expect(await within(dialog).findByText('A partner with this name already exists.')).toBeTruthy();
    expect(calls.find(c => c.method === 'POST')?.body).toEqual({
      name: 'taken', displayName: 'Stock Bot', jwkSetUrl: 'https://bot.example.invalid/jwks', scopes: ['api.read', 'api.write'], tokenTtlSeconds: 900, merchantIds: ['M1', 'M2'],
    });
  });

  it('rotates keys, disables and lists the businesses', async () => {
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && c.url.endsWith('/PA1/rotate')) return { body: partner({ keyVersion: 2, rotatedAt: '2026-10-20T15:00:00Z' }) };
      if (c.method === 'POST' && c.url.endsWith('/PA1/disable')) return { body: partner({ state: 'disabled' }) };
      return undefined;
    });
    renderConsole('/integrations');
    const row = (await screen.findByText('Ledger Books')).closest('tr, .nl-dt-card') as HTMLElement;
    const u = user();
    await u.click(within(row).getByRole('button', { name: 'Rotate keys' }));
    let dialog = await screen.findByRole('dialog', { name: 'Rotate the keys of Ledger Books' });
    await u.click(within(dialog).getByRole('button', { name: 'Rotate keys' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/rotate'))?.body).toEqual({}));
    await u.click(within(row).getByRole('button', { name: 'Disable' }));
    dialog = await screen.findByRole('dialog', { name: 'Disable Ledger Books?' });
    expect(within(dialog).getByText(/end within their lifetime \(15 min\)/)).toBeTruthy();
    await u.click(within(dialog).getByRole('button', { name: 'Disable partner' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/PA1/disable'))).toBe(true));
    await u.click(within(row).getByRole('button', { name: 'Businesses' }));
    const drawer = await screen.findByRole('dialog', { name: 'Businesses of Ledger Books' });
    expect(within(drawer).getByText('Waiting for the owner')).toBeTruthy();
  });

  it('a role without keys reads only', async () => {
    api(['admin'], c => (c.url.endsWith('/api/v1/console/me') ? { body: { userId: 'u', roles: [{ role: 'admin', screens: ['api', 'profile', 'oncall', 'overview'], actions: [] }] } } : undefined));
    renderConsole('/integrations');
    await screen.findByText('Ledger Books');
    expect((screen.getByRole('button', { name: 'Register partner' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Only admins register or change partners.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Rotate keys' })).toBeNull();
  });

  it('parses business ids', () => {
    expect(parseIds(' a, b\nc  a ')).toEqual(['a', 'b', 'c']);
  });
});
