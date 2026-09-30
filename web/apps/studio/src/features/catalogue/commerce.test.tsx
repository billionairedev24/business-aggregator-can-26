import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider } from '@northline/ui';
import { M, renderScreen, stubFetch } from './testing';

vi.mock('../shell/api', () => ({ useMerchantId: () => '01J9ZD3V00000000000000PWP1' }));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children, className }: { children: React.ReactNode; className?: string }) => <a className={className} href="#">{children}</a>,
}));

import { goTo, type Connection } from './api';
import { CommerceIntegrations, normalizeShop } from './CommerceIntegrations';

const I = `/api/v1/merchants/${M}/listings/integrations`;
const conn = (c: Partial<Connection> & Pick<Connection, 'provider'>): Connection => ({ connected: false, available: true, state: 'disconnected', ...c });
const none = [conn({ provider: 'shopify' }), conn({ provider: 'square' }), conn({ provider: 'lightspeed', available: false })];

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('Commerce sync (S-35)', () => {
  it('Square: Connect sends the browser to the consent page; unconfigured platforms are not offered', async () => {
    const assign = vi.spyOn(goTo, 'assign').mockImplementation(() => undefined);
    const calls = stubFetch({ [`GET ${I}`]: { items: none }, [`POST ${I}/square/connect`]: { authorizationUrl: 'https://connect.squareup.com/oauth2/authorize?state=s' } });
    renderScreen(<CommerceIntegrations canManage canSync />);
    const lightspeed = (await screen.findByText('Lightspeed')).closest('li')!;
    expect(within(lightspeed).getByText('Not available yet')).toBeTruthy();
    expect((within(lightspeed).getByRole('button', { name: 'Connect' }) as HTMLButtonElement).disabled).toBe(true);
    await userEvent.click(within(screen.getByText('Square').closest('li')!).getByRole('button', { name: 'Connect' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://connect.squareup.com/oauth2/authorize?state=s'));
    expect(calls.some(c => c.key === `POST ${I}/square/connect`)).toBe(true);
  });

  it('Shopify asks for the store first, with the server’s message', async () => {
    const assign = vi.spyOn(goTo, 'assign').mockImplementation(() => undefined);
    const calls = stubFetch({ [`GET ${I}`]: { items: none }, [`POST ${I}/shopify/connect`]: { authorizationUrl: 'https://prairie-parts.myshopify.com/admin/oauth/authorize?state=s' } });
    renderScreen(<CommerceIntegrations canManage canSync />);
    await userEvent.click(within((await screen.findByText('Shopify')).closest('li')!).getByRole('button', { name: 'Connect' }));
    const dialog = await screen.findByRole('dialog', { name: 'Connect your Shopify store' });
    await userEvent.type(within(dialog).getByLabelText('Store address'), 'evil.example.com');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to Shopify' }));
    expect(within(dialog).getByRole('alert').textContent).toBe('Enter your Shopify store address (your-store.myshopify.com).');
    await userEvent.clear(within(dialog).getByLabelText('Store address'));
    await userEvent.type(within(dialog).getByLabelText('Store address'), 'Prairie-Parts');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to Shopify' }));
    await waitFor(() => expect(assign).toHaveBeenCalled());
    expect(calls.find(c => c.key === `POST ${I}/shopify/connect`)?.body).toEqual({ shop: 'prairie-parts.myshopify.com' });
  });

  it('shows what the last sync did, what couldn’t be imported, and Reconnect', async () => {
    stubFetch({ [`GET ${I}`]: { items: [
      conn({ provider: 'shopify', connected: true, state: 'connected', accountLabel: 'prairie-parts.myshopify.com', lastSyncAt: new Date().toISOString(), lastSyncCount: 4, createdCount: 3, hiddenCount: 1, syncStatus: 'ok', updates: 'webhooks',
        errors: [{ externalId: 'gid://shopify/Product/7004', title: 'Clearance brake cleaner', error: 'Leave out promo words like sale, free or best.' }] }),
      conn({ provider: 'square', connected: true, state: 'reconnect', accountLabel: 'Prairie Wrench Parts' }),
      conn({ provider: 'lightspeed', connected: true, state: 'connected', accountLabel: 'prairieparts.retail.lightspeed.app', syncStatus: 'importing' }),
    ] } });
    renderScreen(<CommerceIntegrations canManage canSync />);
    const shopify = (await screen.findByText('Connected · prairie-parts.myshopify.com')).closest('li')!;
    expect(within(shopify).getByText('3 drafts created · 4 updated · 1 hidden (gone from Shopify)')).toBeTruthy();
    expect(within(shopify).getByText('Price and stock follow Shopify as they change')).toBeTruthy();
    await userEvent.click(within(shopify).getByRole('button', { name: '1 product couldn’t be imported' }));
    expect(within(shopify).getByText(/Leave out promo words/)).toBeTruthy();
    const square = screen.getByText('Connected · Prairie Wrench Parts').closest('li')!;
    expect(within(square).getByText('Access expired or was removed · reconnect to keep syncing')).toBeTruthy();
    expect(within(square).getByRole('button', { name: 'Reconnect' })).toBeTruthy();
    const lightspeed = screen.getByText('Connected · prairieparts.retail.lightspeed.app').closest('li')!;
    expect(within(lightspeed).getByText('Importing your catalogue…')).toBeTruthy();
    expect((within(lightspeed).getByRole('button', { name: 'Sync now' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('technicians sync but can’t connect or disconnect', async () => {
    stubFetch({ [`GET ${I}`]: { items: [conn({ provider: 'shopify', connected: true, state: 'connected', accountLabel: 's.myshopify.com', syncStatus: 'ok' }), conn({ provider: 'square' }), conn({ provider: 'lightspeed' })] } });
    renderScreen(<CommerceIntegrations canManage={false} canSync />);
    const shopify = (await screen.findByText('Connected · s.myshopify.com')).closest('li')!;
    expect(within(shopify).queryByRole('button', { name: 'Disconnect' })).toBeNull();
    expect((within(screen.getByText('Square').closest('li')!).getByRole('button', { name: 'Connect' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('the callback’s outcome is shown once, in French too', async () => {
    stubFetch({ [`GET ${I}`]: { items: none } });
    const seen = vi.fn();
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={qc}><I18nProvider initial="fr"><CommerceIntegrations canManage canSync returned={{ commerce: 'square', result: 'denied' }} onReturnSeen={seen} /></I18nProvider></QueryClientProvider>);
    expect(await screen.findByText('Vous n’avez pas autorisé l’accès sur Square. Rien n’a été connecté.')).toBeTruthy();
    expect(seen).toHaveBeenCalled();
    expect(await screen.findByText('Ou connectez')).toBeTruthy();
  });

  it('normalises the Shopify store like the server', () => {
    expect(normalizeShop('Prairie-Parts')).toBe('prairie-parts.myshopify.com');
    expect(normalizeShop('https://prairie-parts.myshopify.com/admin')).toBe('prairie-parts.myshopify.com');
    expect(normalizeShop('shop.example.com')).toBeNull();
    expect(normalizeShop(' ')).toBeNull();
  });
});
