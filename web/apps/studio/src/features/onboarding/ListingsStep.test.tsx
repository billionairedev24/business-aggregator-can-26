import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { onboarding } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { ListingsStep } from './ListingsStep';
import { validGtin } from './ListingForms';

vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children, className }: { children: React.ReactNode; className?: string }) => <a className={className} href="#">{children}</a>,
}));

beforeEach(() => vi.unstubAllGlobals());

const submitted = { status: 'pending' as const, step: 'listings' as const };

describe('ListingsStep (catalogue contract, mocked)', () => {
  it('provider: validates, posts a service and shows the returned list', async () => {
    let listings: unknown[] = [];
    const calls = mockFetch(c => {
      if (c.url.startsWith('/api/v1/merchants/01J9ZD3V00000000000000TST1/listings')) return { body: { items: listings } };
      if (c.url.endsWith('/services') && c.method === 'POST') {
        listings = [{ id: 'L1', kind: 'service', name: 'Brake inspection', priceCents: 8900, vetting: 'pending', status: 'hidden' }];
        return { status: 201, body: { id: 'L1' } };
      }
      return undefined;
    });
    renderWithProviders(<ListingsStep onboarding={onboarding(submitted)} onBack={() => {}} onDone={() => {}} />);
    expect(await screen.findByText('Nothing yet — add your first one on the left.')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Add service' }));
    expect(screen.getByText('Enter a name.')).toBeTruthy();
    expect(screen.getByText('Enter a price.')).toBeTruthy();

    await userEvent.type(screen.getByRole('textbox', { name: 'Service name' }), 'Brake inspection');
    await userEvent.type(screen.getByRole('textbox', { name: 'Price (incl. travel)' }), '$89');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Duration' }), '45');
    await userEvent.click(screen.getByRole('button', { name: 'Add service' }));

    await waitFor(() => expect(calls.some(c => c.url.endsWith('/services'))).toBe(true));
    expect(calls.find(c => c.url.endsWith('/services'))!.body).toEqual({ name: 'Brake inspection', pricingMode: 'fixed', priceCents: 8900, durationMin: 45, bufferMin: 0, included: '', instantBook: true });
    expect(await screen.findByText('Brake inspection')).toBeTruthy();
    expect(screen.getByText('Vetting · ~2 min')).toBeTruthy();
    expect(screen.getByText('Listings · 1')).toBeTruthy();
  });

  it('quote pricing sends no price', async () => {
    const calls = mockFetch(c => (c.url.endsWith('/services') ? { status: 201, body: {} } : c.url.includes('/listings') ? { body: [] } : undefined));
    renderWithProviders(<ListingsStep onboarding={onboarding(submitted)} onBack={() => {}} onDone={() => {}} />);
    await userEvent.type(screen.getByRole('textbox', { name: 'Service name' }), 'Diagnostic');
    await userEvent.click(screen.getByRole('radio', { name: 'Quote' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add service' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/services'))?.body).toMatchObject({ pricingMode: 'quote' }));
    expect('priceCents' in (calls.find(c => c.url.endsWith('/services'))!.body as object)).toBe(false);
  });

  it('seller: product form checks the GTIN and posts a draft product', async () => {
    const o = onboarding({ ...submitted, type: 'seller', business: { displayName: 'Glenmore Bakery', legalName: 'A', structure: 'sole', gstNumber: null, legalDetails: {}, principals: [], categories: [{ id: 'shop.food-and-grocery.bakery', name: 'Bakery', regulator: 'AHS', suggested: false }], profile: {}, documents: [] } });
    const calls = mockFetch(c => (c.url.endsWith('/products') ? { status: 201, body: {} } : c.url.includes('/listings') ? { body: [] } : undefined));
    renderWithProviders(<ListingsStep onboarding={o} onBack={() => {}} onDone={() => {}} />);
    await userEvent.type(screen.getByRole('textbox', { name: /GTIN/ }), '036000291452');
    await userEvent.click(screen.getByRole('button', { name: 'Look up' }));
    expect(screen.getByText("Valid GTIN — we'll match it to the shared catalogue when you save.")).toBeTruthy();
    await userEvent.type(screen.getByRole('textbox', { name: 'Product title' }), 'Sourdough loaf');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Department › category' }), 'shop.food-and-grocery.bakery');
    await userEvent.type(screen.getByRole('textbox', { name: 'Price' }), '8.50');
    await userEvent.type(screen.getByRole('textbox', { name: 'Stock on hand' }), '24');
    await userEvent.click(screen.getByRole('button', { name: 'Save product as draft' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/products'))?.body).toEqual({ gtin: '036000291452', title: 'Sourdough loaf', categoryId: 'shop.food-and-grocery.bakery', priceCents: 850, stock: 24, variantTheme: 'none' }));
  });

  it('GTIN check digits', () => {
    expect(validGtin('036000291452')).toBe(true);
    expect(validGtin('036000291453')).toBe(false);
    expect(validGtin('4006381333931')).toBe(true);
  });
});
