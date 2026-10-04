import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { ComplianceScreen } from './ComplianceScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

let role = 'owner';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role }));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}));

const days = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString();
const doc = (id: string, extra: object) => ({ id, checkKey: null, registry: null, reference: null, label: null, expiresAt: null, verifiedAt: '2026-01-07T18:00:00Z', submittedAt: null, pausesAt: null, due: false, dueSoon: false, status: 'verified', ...extra });
const data = (over: object = {}) => ({
  business: { type: 'provider', displayName: 'Prairie Wrench', legalName: 'Prairie Wrench Automotive Ltd.', businessNumber: '781234567', province: 'AB', requiredFor: 'Mobile mechanic', ownerName: 'Ravi Sandhu', takeRateBps: 900 },
  stripe: {
    status: 'connected', accountId: 'acct_1Kx9…Q2', type: 'express', chargesEnabled: true, payoutsEnabled: true, bankLabel: 'TD ··3391', statementDescriptor: 'NORTHLINE* PRAIRIE WRENCH',
    payoutInterval: 'weekly', payoutWeekday: 'friday', instantPayouts: true,
    requirements: [{ kind: 'identity', state: 'verified' }, { kind: 'business', state: 'verified' }, { kind: 'bank', state: 'verified' }, { kind: 'owners', state: 'verified' }, { kind: 'annual_reverification', state: 'due', dueAt: days(12) }],
  },
  taxPeriod: '2026-Q3',
  tax: [{ jurisdiction: 'ab_gst', collectedCents: 189240, handling: 'remitted_by_northline' }, { jurisdiction: 'platform_fee_gst', collectedCents: 6140, handling: 'charged_on_invoice' }],
  documents: [
    doc('amvic', { checkType: 'licence', registry: 'AMVIC', reference: '44812', label: 'AMVIC business licence 44812', expiresAt: '2027-01-31T07:00:00Z' }),
    doc('wcb', { checkType: 'wcb', registry: 'WCB Alberta', label: 'WCB Alberta clearance', status: 'expired', expiresAt: '2026-08-31T18:00:00Z', due: true, pausesAt: days(7) }),
    doc('gst', { checkType: 'registry', registry: 'CRA', reference: '781234567 RT0001' }),
  ],
  obligations: { currentVersion: '2.3', acceptedVersion: '2.3', acceptedAt: '2026-03-14T17:00:00Z', upToDate: true },
  dueCount: 1,
  ...over,
});

describe('Stripe & compliance', () => {
  afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });

  it('shows the due item, Stripe requirements, payment settings, tax and the ledger', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/compliance': () => data() });
    renderWithProviders(<ComplianceScreen />);
    expect(await screen.findByRole('heading', { level: 1, name: 'One item due: WCB clearance letter' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('acct_1Kx9…Q2 · Express · payouts enabled · charges enabled')).toBeTruthy();
    expect(screen.getByText('Identity · Ravi Sandhu')).toBeTruthy();
    expect(screen.getByText('Business · Prairie Wrench Automotive Ltd. · BN 78123 4567')).toBeTruthy();
    expect(screen.getByText('Due in 12 days')).toBeTruthy();
    expect(screen.getByText('Weekly · Friday · instant available (1%)')).toBeTruthy();
    expect(screen.getByText('NORTHLINE* PRAIRIE WRENCH')).toBeTruthy();
    expect(screen.getAllByText('Platform fee GST (on your 9%)').length).toBeGreaterThan(0);
    expect(screen.getByText('Verified · renews Jan 2027')).toBeTruthy();
    expect(screen.getByText(/^Expired Aug 31 · upload new letter$/)).toBeTruthy();
    expect(screen.getByText('Active · Northline remits on your behalf')).toBeTruthy();
    expect(screen.getByText(/last accepted v2.3 on/)).toBeTruthy();
  });

  it('names every jurisdiction the Stripe Tax sync writes, in English and French', async () => {
    const tax = [
      { jurisdiction: 'on_hst', collectedCents: 1300, handling: 'remitted_by_northline' },
      { jurisdiction: 'qc_gst_qst', collectedCents: 1498, handling: 'remitted_by_northline' },
    ];
    mockFetch({ 'GET /api/v1/merchants/m1/compliance': () => data({ tax }) });
    const en = renderWithProviders(<ComplianceScreen />);
    expect((await screen.findAllByText('Ontario · HST 13%')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Québec · GST 5% + QST 9.975%').length).toBeGreaterThan(0);
    en.unmount();
    renderWithProviders(<ComplianceScreen />, 'fr');
    expect((await screen.findAllByText('Ontario · TVH 13 %')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Québec · TPS 5 % + TVQ 9,975 %').length).toBeGreaterThan(0);
  });

  it('uploads a renewal and the row turns "in review"', async () => {
    let uploaded = false;
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/compliance': () => {
        const d = data();
        return uploaded ? { ...d, documents: d.documents.map(x => (x.id === 'wcb' ? { ...x, status: 'submitted', due: false, pausesAt: null } : x)), dueCount: 0 } : d;
      },
      'POST /api/v1/merchants/m1/compliance/verifications/wcb/renewal': () => { uploaded = true; return { ...data().documents[1], status: 'submitted', due: false, pausesAt: null }; },
    });
    const user = userEvent.setup({ delay: null });
    const { container } = renderWithProviders(<ComplianceScreen />);
    await screen.findByText(/^Expired Aug 31/);
    const input = container.querySelector('input[type=file]') as HTMLInputElement;
    await user.upload(input, new File(['%PDF'], 'wcb.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(uploaded).toBe(true));
    expect(calls.find(c => c.method === 'POST')?.body).toBeInstanceOf(FormData);
    expect(await screen.findByText('Uploaded · in review')).toBeTruthy();
  });

  it('bookkeepers see the ledger without upload or Stripe buttons', async () => {
    role = 'bookkeeper';
    mockFetch({ 'GET /api/v1/merchants/m1/compliance': () => data() });
    renderWithProviders(<ComplianceScreen />);
    await screen.findByText(/^Expired Aug 31/);
    expect(screen.queryByRole('button', { name: /Upload/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Open Stripe dashboard' })).toBeNull();
    expect(screen.getByText('Only the owner can upload documents or change Stripe settings.')).toBeTruthy();
  });

  it('asks the owner to accept a new version of the obligations', async () => {
    const calls = mockFetch({
      'GET /api/v1/merchants/m1/compliance': () => data({ obligations: { currentVersion: '2.4', acceptedVersion: '2.3', acceptedAt: '2026-03-14T17:00:00Z', upToDate: false } }),
      'POST /api/v1/merchants/m1/compliance/obligations': () => ({ currentVersion: '2.4', acceptedVersion: '2.4', acceptedAt: new Date().toISOString(), upToDate: true }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<ComplianceScreen />);
    await user.click(await screen.findByRole('button', { name: 'Accept v2.4' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Accept v2.4' })).toBeNull());
    expect(calls.find(c => c.method === 'POST')?.body).toEqual({ version: '2.4' });
  });

  it('offers Stripe set-up when no account is connected, and kitchens read the food-safety copy', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/compliance': () => data({ business: { ...data().business, type: 'kitchen' }, stripe: { ...data().stripe, status: 'not_connected', requirements: [] }, documents: [], dueCount: 0 }) });
    renderWithProviders(<ComplianceScreen />, 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Tout est à jour' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Configurer les versements avec Stripe' })).toBeTruthy();
    expect(screen.getByText(/Salubrité : allergènes/)).toBeTruthy();
  });

  it('shows an error with retry', async () => {
    mockFetch({});
    renderWithProviders(<ComplianceScreen />);
    const alert = await screen.findByText('We couldn’t load Stripe & compliance.');
    expect(within(alert.parentElement!.parentElement!).getByRole('button', { name: 'Retry' })).toBeTruthy();
  });
});

describe('Age-restricted sales licences (2026-10-04)', () => {
  afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; });
  const seller = () => data({ business: { ...data().business, type: 'seller', displayName: 'Prairie Cellars' } });
  const WINE = { id: 'L1', ageClass: 'alcohol', province: 'AB', licenceNumber: 'RLS-778812', expiresOn: '2027-03-31', status: 'approved', submittedAt: '2026-09-01T16:00:00Z', decidedAt: '2026-09-02T16:00:00Z', rejectReason: null, note: null };
  const VAPE = { ...WINE, id: 'L2', ageClass: 'tobacco', licenceNumber: 'TP-1', status: 'rejected', rejectReason: 'unreadable', note: 'Blurred photo.' };

  it('lists the licences with their review state and what the business may sell', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/compliance': () => seller(),
      'GET /api/v1/merchants/m1/restricted-licences': () => ({ licensed: ['alcohol'], items: [WINE, VAPE] }),
    });
    renderWithProviders(<ComplianceScreen />);
    expect(await screen.findByRole('heading', { level: 2, name: 'Age-restricted sales' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(await screen.findByText(/Alcohol · RLS-778812 · expires/)).toBeTruthy();
    expect(screen.getByText('Approved')).toBeTruthy();
    expect(screen.getByText('Not approved')).toBeTruthy();
    expect(screen.getByText(/We couldn’t read the document — Blurred photo\./)).toBeTruthy();
    expect(screen.getByText('You can sell: Alcohol.')).toBeTruthy();
  });

  it('sends a licence for review as multipart and shows the server’s messages', async () => {
    let posted: FormData | undefined;
    mockFetch({
      'GET /api/v1/merchants/m1/compliance': () => seller(),
      'GET /api/v1/merchants/m1/restricted-licences': () => ({ licensed: [], items: [] }),
      'POST /api/v1/merchants/m1/restricted-licences': (_url, init) => {
        posted = init.body as FormData;
        if (!posted.get('file')) throw { status: 422, body: { errors: [{ field: 'file', rule: 'required', message: 'Upload the licence as a PDF, PNG or JPEG under 10 MB.' }] } };
        return { ...WINE, status: 'pending' };
      },
    });
    renderWithProviders(<ComplianceScreen />);
    const u = userEvent.setup({ delay: null });
    expect(await screen.findByText('No licence on file. Your age-restricted listings stay hidden until one is approved.')).toBeTruthy();
    await u.click(screen.getByRole('button', { name: 'Add a licence' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a licence for age-restricted sales' });
    await u.type(within(dialog).getByLabelText(/Licence or permit number/), 'RLS-778812');
    await u.click(within(dialog).getByRole('button', { name: 'Send for review' }));
    expect(await within(dialog).findByText('Upload the licence as a PDF, PNG or JPEG under 10 MB.')).toBeTruthy();
    expect(posted?.get('ageClass')).toBe('alcohol');
    expect(posted?.get('licenceNumber')).toBe('RLS-778812');
  });

  it('is in French', async () => {
    mockFetch({
      'GET /api/v1/merchants/m1/compliance': () => seller(),
      'GET /api/v1/merchants/m1/restricted-licences': () => ({ licensed: [], items: [WINE] }),
    });
    renderWithProviders(<ComplianceScreen />, 'fr');
    expect(await screen.findByRole('heading', { level: 2, name: 'Ventes soumises à un âge minimal' })).toBeTruthy();
    expect(await screen.findByText(/Alcool · RLS-778812 · expire le/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Ajouter un permis' })).toBeTruthy();
  });

  it('is not shown to service providers', async () => {
    mockFetch({ 'GET /api/v1/merchants/m1/compliance': () => data() });
    renderWithProviders(<ComplianceScreen />);
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByRole('heading', { name: 'Age-restricted sales' })).toBeNull();
  });
});
