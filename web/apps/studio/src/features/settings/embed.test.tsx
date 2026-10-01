import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { EmbedSnippet } from './EmbedSnippet';

let role = 'owner';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role }));

const K = '/api/v1/merchants/m1/settings/publishable-key';
const key = { key: 'pk_live_Q2hlY2tZb3VyU2NyaXB0', allowedOrigins: ['https://www.prairiewrench.ca'], createdAt: '2026-09-30T17:00:00Z', scriptUrl: 'https://northline.ca/embed.js' };
const user = () => userEvent.setup({ delay: null });

afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });

describe('website embed snippet (S-76)', () => {
  it('shows the snippet with the real publishable key', async () => {
    mockFetch({ [`GET ${K}`]: () => key });
    renderWithProviders(<EmbedSnippet slug="prairie-wrench" />);
    const code = await screen.findByText(/data-key=/);
    expect(code.textContent).toBe('<script src="https://northline.ca/embed.js"\n  data-store="prairie-wrench"\n  data-key="pk_live_Q2hlY2tZb3VyU2NyaXB0" async></script>');
    expect(screen.queryByText(/pk_live_…/)).toBeNull();
  });

  it('lets the owner create the first key', async () => {
    let current: unknown = undefined; // 204 until created
    const calls = mockFetch({ [`GET ${K}`]: () => current, [`POST ${K}`]: () => (current = key) });
    renderWithProviders(<EmbedSnippet slug="prairie-wrench" />);
    await user().click(await screen.findByRole('button', { name: 'Create embed key' }));
    expect(await screen.findByText(/pk_live_Q2hlY2tZb3VyU2NyaXB0/)).toBeTruthy();
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(1);
  });

  it('replaces the key only after the owner confirms', async () => {
    const calls = mockFetch({ [`GET ${K}`]: () => key, [`POST ${K}`]: () => ({ ...key, key: 'pk_live_TmV3S2V5Rm9yVGhlU2l0ZQ' }) });
    renderWithProviders(<EmbedSnippet slug="prairie-wrench" />);
    await user().click(await screen.findByRole('button', { name: 'New key' }));
    expect(screen.getByText('The current key stops working at once. Paste the new snippet on your website right after.')).toBeTruthy();
    expect(calls.some(c => c.method === 'POST')).toBe(false);
    await user().click(screen.getByRole('button', { name: 'Replace key' }));
    expect(await screen.findByText(/pk_live_TmV3S2V5Rm9yVGhlU2l0ZQ/)).toBeTruthy();
  });

  it('saves the allowed websites and shows the server message next to them', async () => {
    let fail = true;
    const calls = mockFetch({
      [`GET ${K}`]: () => key,
      [`PUT ${K}/origins`]: (_url, init) => {
        if (fail) { fail = false; throw { status: 422, body: { errors: [{ field: 'allowedOrigins', rule: 'format', message: 'Enter a site address like https://www.example.com.' }] } }; }
        return { ...key, allowedOrigins: JSON.parse(String(init.body)).allowedOrigins };
      },
    });
    renderWithProviders(<EmbedSnippet slug="prairie-wrench" manageSites />);
    const sites = await screen.findByLabelText('Allowed websites');
    expect((sites as HTMLTextAreaElement).value).toBe('https://www.prairiewrench.ca');
    await user().clear(sites);
    await user().type(sites, 'ftp://nope');
    await user().click(screen.getByRole('button', { name: 'Save websites' }));
    expect(await screen.findByText('Enter a site address like https://www.example.com.')).toBeTruthy();
    await user().clear(sites);
    await user().type(sites, 'https://www.prairiewrench.ca{enter}https://book.prairiewrench.ca');
    await user().click(screen.getByRole('button', { name: 'Save websites' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'PUT').at(-1)?.body).toEqual({ allowedOrigins: ['https://www.prairiewrench.ca', 'https://book.prairiewrench.ca'] }));
    expect(await screen.findByText('Saved.')).toBeTruthy();
  });

  it('other roles copy the snippet but cannot change the key; in French', async () => {
    role = 'technician';
    mockFetch({ [`GET ${K}`]: () => undefined });
    renderWithProviders(<EmbedSnippet slug="prairie-wrench" manageSites />, 'fr');
    expect(await screen.findByText('Demandez au propriétaire de créer une clé d’intégration.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
