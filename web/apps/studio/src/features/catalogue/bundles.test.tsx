import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { M, renderScreen, stubFetch } from './testing';
import type { ProductDetail } from './api';

vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWP1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWP1', displayName: 'Prairie Wrench Parts', type: 'seller', tier: 'trusted', status: 'active', role: 'owner' }),
  useRole: () => 'owner',
}));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  useNavigate: () => vi.fn(),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}));
vi.mock('./imageFile', () => ({ imageProblem: vi.fn(async () => undefined), readImageSize: vi.fn() }));

import { ProductEditor } from './ProductEditor';

const user = () => userEvent.setup({ delay: null });
const base = `/api/v1/merchants/${M}`;
const IMAGE = { id: 'M1', url: `${base}/media/M1`, width: 1200, height: 1200, onWhite: true };
const product = (over: Partial<ProductDetail> = {}): ProductDetail => ({
  id: 'p1', kind: 'product', vetting: 'draft', status: 'hidden', vettingFlags: [], revetReasons: [], submittedAt: null, updatedAt: '2026-10-01T15:00:00Z',
  completeness: { percent: 50, done: 3, total: 6, missing: [] }, catalogRef: 'NL-P-88300', catalogTitle: 'Wiper blades', sharedRecord: false, contentShared: false,
  contentLocked: false, sellerCount: 1, identifierType: 'none', gtin: null, title: 'Wiper blades', brand: null, mpn: null, categoryId: null, attributes: {},
  description: null, bullets: [], variantTheme: 'none', variants: [], imageSource: 'own', images: [], catalogueImages: [], sku: 'WB', priceCents: 1900,
  compareAtCents: null, costCents: null, condition: 'new', stock: 12, lowStockAt: 5, fulfilment: ['pooled'], handlingTime: 'same_day', returnsPolicy: 'standard_14',
  countryOfOrigin: null, restrictedOk: false, bilingualOk: false, warranty: false, searchKeywords: null, type: 'product', bundleItems: [], ...over,
});
const listing = (id: string, name: string, over: Record<string, unknown> = {}) => ({
  id, kind: 'product', name, sku: id, meta: '', priceCents: 1900, stock: 9, sales30d: 0, vetting: 'approved', status: 'live', vettingFlags: [], revetReasons: [],
  submittedAt: null, categoryId: null, pricingMode: null, updatedAt: '2026-10-01T15:00:00Z', bundle: false, ...over,
});

afterEach(() => vi.unstubAllGlobals());

describe('S-65 bundles', () => {
  it('builds a bundle from own products: quantity, stock that follows the items, and the saving', async () => {
    const calls = stubFetch({
      [`GET ${base}/catalogue/categories`]: { items: [] },
      [`GET ${base}/listings`]: { items: [listing('p1', 'Wiper blades'), listing('p2', 'Washer fluid', { priceCents: 600, stock: 4 }), listing('b9', 'Old bundle', { bundle: true })] },
      [`GET ${base}/listings/p1`]: product(),
      [`PUT ${base}/products/b1`]: () => ({ json: product({ id: 'b1', type: 'bundle', title: 'Wiper kit' }) }),
    });
    renderScreen(<ProductEditor portal="seller" detail={product({ id: 'b1', type: 'bundle', title: 'Wiper kit', categoryId: 'shop.auto.parts', stock: 0, lowStockAt: null, priceCents: 3000 })} />);
    expect(screen.queryByRole('textbox', { name: /Product identifier/ })).toBeNull();
    await user().click(screen.getByRole('tab', { name: /Bundle contents/ }));
    const picker = await screen.findByRole('combobox', { name: 'Choose a product…' });
    await waitFor(() => expect(within(picker).queryByText('Washer fluid')).toBeTruthy());
    expect(within(picker).queryByText('Old bundle')).toBeNull(); // a bundle can't contain another bundle
    await user().selectOptions(picker, 'p1');
    await user().click(screen.getByRole('button', { name: 'Add to bundle' }));
    const qty = await screen.findByLabelText('Quantity of Wiper blades');
    await user().clear(qty);
    await user().type(qty, '2');
    await user().click(screen.getByRole('tab', { name: /Price, stock/ }));
    expect((screen.getByLabelText(/Stock on hand/) as HTMLInputElement).value).toBe('4'); // 9 in stock / 2 per bundle
    expect(screen.getByText(/Bought separately: \$38\.00/)).toBeTruthy();
    expect(screen.getByText(/Customers save \$8\.00/)).toBeTruthy();
    await user().click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(calls.find(c => c.key === `PUT ${base}/products/b1`)).toBeTruthy());
    expect(calls.find(c => c.key === `PUT ${base}/products/b1`)!.body).toMatchObject({
      type: 'bundle', identifierType: 'none', gtin: null, stock: 0, variants: [], bundleItems: [{ offerId: 'p1', variantId: null, qty: 2 }],
    });
  });

  it("shows the server's bundle message on the contents tab", async () => {
    stubFetch({
      [`GET ${base}/catalogue/categories`]: { items: [] },
      [`GET ${base}/listings`]: { items: [] },
    });
    renderScreen(<ProductEditor portal="seller" detail={product({ id: 'b1', type: 'bundle', title: 'Wiper kit', bundleItems: [{ offerId: 'p1', variantId: null, qty: 1, name: 'Wiper blades', option: null, unitPriceCents: 1900, stock: 9 }] })} />);
    await user().click(screen.getByRole('tab', { name: /Bundle contents/ }));
    expect(screen.getByText('Wiper blades')).toBeTruthy();
    expect(screen.getByText('Add a product listing first — a bundle is made of your own products.')).toBeTruthy();
  });
});

describe('S-65 variant images', () => {
  it('uploads photos for one variant; the others keep inheriting', async () => {
    const calls = stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] }, [`POST ${base}/media`]: IMAGE });
    const variants = [
      { id: 'v1', value: '20 in', sku: 'WB-20', gtin: null, priceCents: 1700, stock: 3, images: [] },
      { id: 'v2', value: '22 in', sku: 'WB-22', gtin: null, priceCents: 1900, stock: 4, images: [] },
    ];
    renderScreen(<ProductEditor portal="seller" detail={product({ variantTheme: 'length', variants })} />);
    await user().click(screen.getByRole('tab', { name: /Variants/ }));
    expect(screen.getAllByText('inherits')).toHaveLength(2);
    await user().upload(screen.getByTestId('variant-images-22 in'), new File([new Uint8Array(10)], 'blade.png', { type: 'image/png' }));
    expect(await screen.findByText('main')).toBeTruthy();
    expect(screen.getAllByText('inherits')).toHaveLength(1);
    expect(screen.getByRole('img', { name: 'Photo of 22 in' })).toBeTruthy();
    expect(calls.some(c => c.key === `POST ${base}/media`)).toBe(true);
  });
});

describe('S-65 compliance documents', () => {
  it('asks to save first, then uploads a spec sheet and lists it', async () => {
    const doc = { id: 'd1', purpose: 'spec_sheet', fileName: 'spec.pdf', contentType: 'application/pdf', byteSize: 1200, url: `${base}/listings/p1/documents/d1`, createdAt: '2026-10-01T15:00:00Z' };
    let stored: unknown[] = [];
    const calls = stubFetch({
      [`GET ${base}/catalogue/categories`]: { items: [] },
      [`GET ${base}/listings/p1/documents`]: () => ({ json: { items: stored } }),
      [`POST ${base}/listings/p1/documents`]: () => { stored = [doc]; return { status: 201, json: doc }; },
    });
    renderScreen(<ProductEditor portal="seller" detail={product()} />);
    await user().click(screen.getByRole('tab', { name: /Compliance/ }));
    await user().upload(screen.getByTestId('doc-input-spec_sheet'), new File(['%PDF-1.7'], 'spec.pdf', { type: 'application/pdf' }));
    expect(await screen.findByRole('link', { name: 'spec.pdf' })).toBeTruthy();
    expect(screen.getByText('Spec sheet')).toBeTruthy();
    expect(calls.some(c => c.key === `POST ${base}/listings/p1/documents`)).toBe(true);
  });

  it('refuses a file that is not a PDF, PNG or JPEG before uploading', async () => {
    const calls = stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] }, [`GET ${base}/listings/p1/documents`]: { items: [] } });
    renderScreen(<ProductEditor portal="seller" detail={product()} />);
    await user().click(screen.getByRole('tab', { name: /Compliance/ }));
    await userEvent.setup({ delay: null, applyAccept: false }).upload(screen.getByTestId('doc-input-invoice'), new File(['x'], 'invoice.docx', { type: 'application/msword' }));
    expect(await screen.findByText('Upload a PDF, PNG or JPEG under 10 MB.')).toBeTruthy();
    expect(calls.some(c => c.key.startsWith('POST'))).toBe(false);
  });

  it('a new listing has no documents yet', () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] } });
    renderScreen(<ProductEditor portal="seller" />);
    return user().click(screen.getByRole('tab', { name: /Compliance/ })).then(() => {
      expect(screen.getByText('Save the draft to attach documents.')).toBeTruthy();
    });
  });
});
