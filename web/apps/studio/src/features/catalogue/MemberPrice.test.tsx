import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MERCHANT } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { MemberPriceField } from './MemberPrice';

vi.mock('../shell/api', async importOriginal => ({
  ...(await importOriginal<typeof import('../shell/api')>()),
  useMerchantId: () => MERCHANT,
}));

beforeEach(() => { vi.unstubAllGlobals(); });

const path = `/api/v1/merchants/${MERCHANT}/products/L1/member-price`;

describe('Plus member price (S-152)', () => {
  it('shows what members pay and saves the percentage', async () => {
    const calls = mockFetch(c => (c.url === path && c.method === 'PUT' ? { body: c.body } : c.url === path ? { body: { memberDiscountPct: null } } : undefined));
    renderWithProviders(<MemberPriceField listingId="L1" priceCents={2000} canEdit />);
    const input = await screen.findByLabelText('Northline Plus member price (% off)');
    await waitFor(() => expect((input as HTMLInputElement).disabled).toBe(false));
    await userEvent.type(input, '15');
    expect(screen.getByText('Plus members pay $17.00. You fund the difference.')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Save member price' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ memberDiscountPct: 15 }));
    expect(await screen.findByText('Saved')).toBeTruthy();
  });

  it('shows the server message under the field, in French', async () => {
    mockFetch(c => (c.url === path && c.method === 'PUT'
      ? { status: 422, body: { errors: [{ field: 'memberDiscountPct', message: 'Choisissez un prix membre de 1 % à 50 % de rabais, ou aucun.' }] } }
      : c.url === path ? { body: { memberDiscountPct: 10 } } : undefined));
    renderWithProviders(<MemberPriceField listingId="L1" priceCents={1000} canEdit />, { locale: 'fr' });
    const input = await screen.findByLabelText('Prix membre Northline Plus (% de rabais)');
    await waitFor(() => expect((input as HTMLInputElement).value).toBe('10'));
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer le prix membre' }));
    expect(await screen.findByText('Choisissez un prix membre de 1 % à 50 % de rabais, ou aucun.')).toBeTruthy();
  });

  it('is read-only without edit rights, and waits for the first save', async () => {
    mockFetch(c => (c.url === path ? { body: { memberDiscountPct: 5 } } : undefined));
    const { unmount } = renderWithProviders(<MemberPriceField listingId="L1" priceCents={1000} canEdit={false} />);
    expect(await screen.findByLabelText('Northline Plus member price (% off)')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Save member price' })).toBeNull();
    unmount();
    renderWithProviders(<MemberPriceField priceCents={1000} canEdit />);
    expect(screen.getByText('Save the listing first.')).toBeTruthy();
  });
});
