import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const licence = (id: string, extra: object = {}) => ({
  licence: { id, merchantId: 'M1', ageClass: 'alcohol', province: 'AB', licenceNumber: 'RLS-778812', documentId: 'D1', expiresOn: '2027-03-31', status: 'pending', submittedAt: '2026-10-01T16:00:00Z', decidedAt: null, rejectReason: null, note: null, ...extra },
  businessName: 'Prairie Cellars', businessProvince: 'AB',
});
const REPORT = {
  from: '2026-09-04T00:00:00Z', to: '2026-10-04T00:00:00Z', province: null,
  verifications: { verified: 41, failed: 3 }, failures: { document_expired: 2, selfie_face_mismatch: 1 }, handoffs: { passed: 37, refused: 2 },
  refusals: { nobody_of_age: 1, no_id: 1 }, byPlace: { door: 30, counter: 9 },
  recent: [{ checkId: 'H1', orderId: 'ORD-77', orderType: 'goods', province: 'AB', requiredAge: 18, actorRole: 'courier', place: 'door', reason: 'nobody_of_age', at: '2026-10-03T22:10:00Z' }],
};
const RULES = { items: [{ province: 'AB', ageClass: 'alcohol', minimumAge: 18, deliveryAllowed: true, pickupAllowed: true, deliveryFrom: '10:00:00', deliveryUntil: '02:00:00', source: 'Gaming, Liquor and Cannabis Act (Alberta)', confirmed: false }] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/vetting/licences')) return { body: { items: [licence('L1'), licence('L2', { ageClass: 'tobacco', licenceNumber: 'TP-1' })] } };
    if (c.method === 'POST' && c.url.includes('/decision')) return { body: licence('L1', { status: 'approved' }) };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/vetting/age-checks')) return { body: REPORT };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/vetting/age-rules')) return { body: RULES };
    return undefined;
  });
}
const row = (text: string) => screen.getAllByText(text)[0]!.closest('tr, .nl-dt-card') as HTMLElement;
const user = () => userEvent.setup({ delay: null });

describe('Listing vetting › Licences (2026-10-04)', () => {
  it('lists licences waiting for review and approves one with the role header', async () => {
    const calls = api(['trust_safety']);
    renderConsole('/vetting?view=licences');
    expect(await screen.findByRole('heading', { level: 1, name: 'Licences for age-restricted sales · 2 waiting' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(screen.getByRole('tab', { name: 'Licences' }).getAttribute('aria-selected')).toBe('true');
    expect(within(row('RLS-778812')).getByText('Alcohol')).toBeTruthy();
    expect(within(row('TP-1')).getByText('Tobacco and vape')).toBeTruthy();
    await user().click(within(row('RLS-778812')).getByRole('button', { name: /Approve/ }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/vetting/licences/L1/decision'))).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ decision: 'approve' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
    expect(await screen.findByText('Licence of Prairie Cellars approved; its listings can go live.')).toBeTruthy();
  });

  it('rejects with a reason and a note', async () => {
    const calls = api(['admin']);
    renderConsole('/vetting?view=licences');
    await screen.findByRole('heading', { level: 1 });
    await user().click(within(row('TP-1')).getByRole('button', { name: /Reject/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject the licence of Prairie Cellars' });
    await user().selectOptions(within(dialog).getByLabelText('Why'), 'wrong_class');
    await user().type(within(dialog).getByLabelText(/Note to the business/), 'This is a liquor licence.');
    await user().click(within(dialog).getByRole('button', { name: 'Reject licence' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST')?.body).toEqual({ decision: 'reject', reason: 'wrong_class', note: 'This is a liquor licence.' }));
  });

  it('is in French', async () => {
    api(['trust_safety']);
    renderConsole('/vetting?view=licences', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Permis pour les ventes soumises à un âge minimal · 2 en attente' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Vérifications d’âge' })).toBeTruthy();
  });
});

describe('Listing vetting › Age checks (2026-10-04)', () => {
  it('reports verifications, handoffs and refusals by reason, and the region model’s ages', async () => {
    api(['trust_safety']);
    renderConsole('/vetting?view=age');
    expect(await screen.findByRole('heading', { level: 1, name: 'Age checks · last 30 days' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(screen.getByText('Customers verified')).toBeTruthy();
    expect(screen.getByText('41')).toBeTruthy();
    expect(screen.getByText('Nobody of age · 1')).toBeTruthy();
    expect(within(screen.getByText('ORD-77').closest('tr')!).getByText('Door (courier) · AB')).toBeTruthy();
    expect(await screen.findByText('AB · Alcohol')).toBeTruthy();
    expect(screen.getByText('10:00–02:00')).toBeTruthy();
    expect(screen.getByText(/To confirm/)).toBeTruthy();
  });

  it('is in French', async () => {
    api(['admin']);
    renderConsole('/vetting?view=age', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Vérifications d’âge · 30 derniers jours' })).toBeTruthy();
    expect(screen.getByText('Personne ayant l’âge requis · 1')).toBeTruthy();
  });
});
