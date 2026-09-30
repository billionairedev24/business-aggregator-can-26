import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { M, renderScreen, stubFetch } from './testing';

const shell = vi.hoisted(() => ({ type: 'seller' as string, role: 'owner', navigate: vi.fn() }));
vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWP1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWP1', displayName: 'Prairie Wrench Parts', type: shell.type, tier: 'trusted', status: 'active', role: shell.role }),
  useRole: () => shell.role,
}));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  useNavigate: () => shell.navigate,
  Link: ({ children, className }: { children: React.ReactNode; className?: string }) => <a className={className} href="#">{children}</a>,
}));
vi.mock('./imageFile', () => ({ imageProblem: vi.fn(async () => undefined), readImageSize: vi.fn() }));

import { ListingsScreen } from './ListingsScreen';
import { ServiceEditor } from './ServiceEditor';
import { ProductEditor } from './ProductEditor';
import { BulkUploadScreen } from './BulkUploadScreen';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

const base = `/api/v1/merchants/${M}`;
const item = (over: Record<string, unknown>) => ({ id: 'x', kind: 'product', name: 'X', sku: 'X-1', meta: '', priceCents: 1000, stock: 5, sales30d: 0, vetting: 'approved', status: 'live', vettingFlags: [], submittedAt: null, categoryId: null, pricingMode: null, updatedAt: '2026-09-29T15:00:00Z', ...over });
const LISTINGS = [
  item({ id: 's1', kind: 'service', name: 'Brake inspection', sku: 'SVC-BI', priceCents: 8900, stock: null, sales30d: 31, pricingMode: 'fixed' }),
  item({ id: 'p1', name: 'Brake pads · ceramic (front)', sku: 'BP-CER-F', priceCents: 6800, stock: 14 }),
  item({ id: 'p2', name: 'Wiper blades · 22"', sku: 'WB-22', status: 'hidden', vetting: 'pending', submittedAt: new Date(Date.now() - 120_000).toISOString() }),
  item({ id: 'p3', name: 'Cabin air filter', sku: 'CAF-01', status: 'hidden', vetting: 'draft' }),
];
const CATEGORIES = { items: [
  { id: 'service.automotive', parentId: null, name: 'Automotive', leaf: false, regulatedRegistry: null, banned: false, perishable: false, attributes: [], variantThemes: [], medianPriceCents: null },
  { id: 'service.automotive.mobile-mechanic', parentId: 'service.automotive', name: 'Mobile mechanic', leaf: true, regulatedRegistry: 'AMVIC', banned: false, perishable: false, attributes: [], variantThemes: [], medianPriceCents: 9000 },
  { id: 'shop.hardware-and-auto', parentId: null, name: 'Hardware & auto', leaf: false, regulatedRegistry: null, banned: false, perishable: false, attributes: [], variantThemes: [], medianPriceCents: null },
  { id: 'shop.hardware-and-auto.auto-parts', parentId: 'shop.hardware-and-auto', name: 'Auto parts', leaf: true, regulatedRegistry: null, banned: false, perishable: false,
    attributes: [{ key: 'partType', label: 'Part type', options: ['Wiper blades'], required: true }], variantThemes: ['length'], medianPriceCents: 2100 },
] };

beforeEach(() => { shell.type = 'seller'; shell.role = 'owner'; shell.navigate.mockReset(); });
afterEach(() => vi.unstubAllGlobals());

describe('ListingsScreen', () => {
  it('seller portal: products only, design copy, no Type column', async () => {
    stubFetch({ [`GET ${base}/listings`]: { items: LISTINGS } });
    renderScreen(<ListingsScreen />);
    expect(screen.getByRole('heading', { name: 'Products & variants' })).toBeTruthy();
    expect(await screen.findAllByText('Brake pads · ceramic (front)')).not.toHaveLength(0);
    expect(screen.queryByText('Brake inspection')).toBeNull();
    expect(screen.queryByRole('columnheader', { name: /Type/ })).toBeNull();
    expect(screen.getAllByText('Pending · 2 min').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Bulk upload' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /New product/ }).length).toBeGreaterThan(0);
  });

  it('both portal: services and products with a Type column', async () => {
    shell.type = 'both';
    stubFetch({ [`GET ${base}/listings`]: { items: LISTINGS } });
    renderScreen(<ListingsScreen />);
    expect(screen.getByRole('heading', { name: 'Services & products' })).toBeTruthy();
    expect(await screen.findAllByText('Brake inspection')).not.toHaveLength(0);
    expect(screen.getAllByText('Service').length).toBeGreaterThan(0); // Type column values (card layout in jsdom)
    expect(screen.getAllByText('Product').length).toBeGreaterThan(0);
  });

  it('hides a live listing through the inline action', async () => {
    const calls = stubFetch({ [`GET ${base}/listings`]: { items: LISTINGS }, [`POST ${base}/listings/p1/hide`]: { status: 204 } });
    renderScreen(<ListingsScreen />);
    await screen.findAllByText('Brake pads · ceramic (front)');
    // first live product in design order is the brake pads (p1)
    await user().click(screen.getAllByRole('button', { name: /^Hide/ })[0]!);
    await waitFor(() => expect(calls.some(c => c.key === `POST ${base}/listings/p1/hide`)).toBe(true));
  });

  it('bookkeeper is view-only', async () => {
    shell.role = 'bookkeeper';
    stubFetch({ [`GET ${base}/listings`]: { items: LISTINGS } });
    renderScreen(<ListingsScreen />);
    expect(await screen.findByText(/View only/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /New product/ })).toBeNull();
  });

  it('shows an error with retry', async () => {
    stubFetch({ [`GET ${base}/listings`]: () => ({ status: 500, json: {} }) });
    renderScreen(<ListingsScreen />);
    expect(await screen.findByText("We couldn't load your listings.")).toBeTruthy();
  });
});

describe('ServiceEditor', () => {
  it('shows the attention summary and field messages on an empty save', async () => {
    shell.type = 'provider';
    const calls = stubFetch({ [`GET ${base}/catalogue/categories`]: CATEGORIES });
    renderScreen(<ServiceEditor portal="provider" />);
    await user().click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('2 things need attention.')).toBeTruthy();
    expect(screen.getAllByText('Enter a service name.').length).toBeGreaterThan(0);
    expect(calls.some(c => c.key.startsWith('POST'))).toBe(false);
    expect((screen.getByRole('button', { name: 'Submit for vetting' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('saves a draft with the contract body and maps a 422 onto the field', async () => {
    shell.type = 'provider';
    const calls = stubFetch({
      [`GET ${base}/catalogue/categories`]: CATEGORIES,
      [`POST ${base}/services`]: () => ({ status: 422, json: { errors: [{ field: 'sku', rule: 'taken', message: 'That SKU is already used by another listing.' }] } }),
    });
    renderScreen(<ServiceEditor portal="provider" />);
    await user().type(screen.getByLabelText('Service name'), 'Brake inspection');
    await user().type(screen.getByLabelText('Price (incl. travel)'), '89');
    await user().click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(calls.find(c => c.key === `POST ${base}/services`)).toBeTruthy());
    expect(calls.find(c => c.key === `POST ${base}/services`)!.body).toMatchObject({ name: 'Brake inspection', pricingMode: 'fixed', priceCents: 8900, durationMin: 60, bufferMin: 0, instantBook: true });
    expect(await screen.findAllByText('That SKU is already used by another listing.')).not.toHaveLength(0);
  });
});

describe('ProductEditor', () => {
  it('GTIN lookup pre-fills from the shared record and shows the match', async () => {
    stubFetch({
      [`GET ${base}/catalogue/categories`]: CATEGORIES,
      [`GET ${base}/catalogue/products/lookup`]: { id: 'r', ref: 'NL-P-88120', gtin: '028851200226', brand: 'Bosch', title: 'Bosch Icon 22" beam blade', mpn: '22A', categoryId: 'shop.hardware-and-auto.auto-parts', attributes: { partType: 'Wiper blades' }, description: null, bullets: [], images: [], sellerCount: 14, locked: true },
    });
    renderScreen(<ProductEditor portal="seller" />);
    await user().type(screen.getByRole('textbox', { name: /Product identifier/ }), '028851200226');
    await user().click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByText('Matched · Northline catalogue NL-P-88120')).toBeTruthy();
    expect(screen.getByText(/14 sellers/)).toBeTruthy();
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe('Bosch Icon 22" beam blade');
    expect((screen.getByLabelText('Brand') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the check-digit message and keeps Submit disabled while incomplete', async () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: CATEGORIES });
    renderScreen(<ProductEditor portal="seller" />);
    const gtin = screen.getByRole('textbox', { name: /Product identifier/ });
    await user().type(gtin, '028851200220');
    fireEvent.blur(gtin);
    expect(await screen.findByText('GTIN check digit invalid')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Submit for vetting' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Completeness · 33%')).toBeTruthy();
  });
});

describe('BulkUploadScreen', () => {
  it('uploads, shows the validation report and imports the valid rows', async () => {
    const batch = { id: 'b1', fileName: 'wipers-sept.xlsx', template: 'auto_parts', rowCount: 256, createCount: 212, updateCount: 38, errorCount: 1, errors: [{ row: 14, sku: 'WB-26', error: 'GTIN check digit invalid' }], status: 'validated', createdAt: '2026-09-29T15:00:00Z', importedAt: null };
    const calls = stubFetch({
      [`GET ${base}/listings/imports`]: { items: [] },
      [`GET ${base}/listings/integrations`]: { items: [{ provider: 'shopify', connected: false }, { provider: 'square', connected: false }, { provider: 'lightspeed', connected: false }] },
      [`POST ${base}/listings/imports`]: batch,
      [`POST ${base}/listings/imports/b1/commit`]: { ...batch, status: 'imported', importedAt: '2026-09-29T15:01:00Z' },
    });
    renderScreen(<BulkUploadScreen />);
    expect(screen.getByRole('heading', { name: 'Add or update hundreds of products at once' })).toBeTruthy();
    await user().upload(screen.getByTestId('bulk-input'), new File(['sku\n'], 'wipers-sept.xlsx'));
    expect(await screen.findByText('Validation · wipers-sept.xlsx')).toBeTruthy();
    expect(screen.getByText('212')).toBeTruthy();
    expect(screen.getAllByText('GTIN check digit invalid').length).toBeGreaterThan(0);
    await user().click(screen.getByRole('button', { name: 'Import 250 valid rows' }));
    expect(await screen.findByText(/Imported · 212 created as drafts, 38 updated/)).toBeTruthy();
    expect(calls.some(c => c.key === `POST ${base}/listings/imports/b1/commit`)).toBe(true);
  });
});
