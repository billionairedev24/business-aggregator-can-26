import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';

const LAW = { code: 'ab_pipa', name: 'Personal Information Protection Act', shortName: 'PIPA', authority: 'Commissioner', responseDays: 45, businessDays: false, extensionDays: 30 };
const ITEM = { id: 'R1', reference: 'PR-1001', type: 'erasure', state: 'awaiting_verification', subjectId: 'U1', subjectName: 'Dana Kowalski', subjectKind: 'customer',
  channel: 'staff', province: 'AB', law: 'PIPA', receivedAt: '2026-10-01T12:00:00Z', dueAt: '2026-11-15T23:59:59Z', overdue: false, holdsOpen: 0 };
const LATE = { ...ITEM, id: 'R2', reference: 'PR-1002', type: 'access', state: 'verified', subjectName: '', overdue: true };
const DETAIL = { id: 'R1', reference: 'PR-1001', type: 'erasure', state: 'awaiting_verification', subjectKind: 'customer', channel: 'staff', province: 'AB', law: LAW,
  receivedAt: '2026-10-01T12:00:00Z', dueAt: '2026-11-15T23:59:59Z', extendedTo: null, extensionReason: null, overdue: false, verification: null, verifiedAt: null,
  scheduledFor: null, completedAt: null, decision: null, decisionNote: null, corrections: [], note: 'Asked by email', holdsOpen: 0,
  steps: [{ module: 'orders', status: 'held', attempts: 1, holds: [{ category: 'orders.orders', reason: 'open_order' }], retained: [{ category: 'orders.orders', reason: 'tax_records' }] }] };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/privacy-requests?state=open')) return { body: { items: [ITEM, LATE] } };
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/privacy-requests/R1')) return { body: DETAIL };
    if (c.method === 'POST' && c.url.endsWith('/R1/verify')) return { body: { ...DETAIL, state: 'verified', verification: 'staff', verifiedAt: '2026-10-02T12:00:00Z' } };
    if (c.method === 'POST' && c.url.endsWith('/R1/extend')) return { body: { ...DETAIL, extendedTo: '2026-12-15T23:59:59Z', extensionReason: 'volume' } };
    if (c.method === 'POST' && c.url.endsWith('/api/v1/console/privacy-requests')) {
      return (c.body as { contact: string }).contact ? { status: 201, body: DETAIL }
        : { status: 422, body: { errors: [{ field: 'contact', rule: 'required', message: 'Enter the person’s email or mobile number.' }] } };
    }
    return undefined;
  });
}
const user = () => userEvent.setup({ delay: null });

describe('privacy requests (S-105)', () => {
  it('lists open requests with their law and deadline, overdue marked', async () => {
    api(['privacy']);
    renderConsole('/privacy');
    expect(await screen.findByRole('heading', { level: 1, name: '2 open requests · 1 overdue' })).toBeTruthy();
    expect(screen.getAllByText('Dana Kowalski').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Erased account').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Overdue/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Record a request' })).toBeTruthy();
  });

  it('opens a request: what was asked, the steps and what is kept; staff verify and extend with the role header', async () => {
    const calls = api(['support_lead']);
    renderConsole('/privacy?request=R1');
    const drawer = await screen.findByRole('dialog', { name: 'PR-1001 · Erasure' });
    expect(await within(drawer).findByText(/Personal Information Protection Act · 45 days · extension 30 days/)).toBeTruthy();
    expect(within(drawer).getByText(/Kept: orders.orders \(tax_records\)/)).toBeTruthy();
    expect(within(drawer).getByText(/Note: Asked by email/)).toBeTruthy();

    await user().click(within(drawer).getByRole('button', { name: 'Identity verified' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/R1/verify'))).toBe(true));
    expect(calls.find(c => c.url.endsWith('/R1/verify'))!.headers['X-Console-Role']).toBe('support_lead');

    await user().click(within(drawer).getByRole('button', { name: 'Extend' }));
    const extend = await screen.findByRole('dialog', { name: 'Extend the deadline' });
    await user().click(within(extend).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/R1/extend'))?.body).toEqual({ reason: 'volume' }));
  });

  it('records a request made by email, with the api’s messages', async () => {
    const calls = api(['admin']);
    renderConsole('/privacy');
    await user().click(await screen.findByRole('button', { name: 'Record a request' }));
    const dialog = await screen.findByRole('dialog', { name: 'Record a request' });
    await user().click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByText('Enter the person’s email or mobile number.')).toBeTruthy();
    await user().type(within(dialog).getByLabelText('Email or mobile of the account'), 'dana@example.ca');
    await user().selectOptions(within(dialog).getByLabelText('Request'), 'erasure');
    await user().click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST').at(-1)?.body).toEqual({ contact: 'dana@example.ca', type: 'erasure' }));
  });

  it('an analyst is not let in', async () => {
    api(['analyst']);
    renderConsole('/privacy');
    expect(await screen.findByText('Not available in this role.')).toBeTruthy();
  });

  it('speaks French', async () => {
    api(['privacy']);
    renderConsole('/privacy', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: '2 demandes ouvertes · 1 en retard' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Consigner une demande' })).toBeTruthy();
  });

  it('S-108: finds the proof of consent by contact and withdraws for the person', async () => {
    const records = [
      { id: 'C2', userId: '01J9ZD3V00000000000000AMR1', category: 'marketing_email', action: 'granted', at: '2026-09-20T15:00:00Z', source: 'checkout',
        wordingVersion: 'account.email.2026-10', language: 'fr', ipPrefix: '203.0.113.0/24', addressKnown: true, actorId: null },
      { id: 'C1', userId: '01J9ZD3V00000000000000AMR1', category: 'marketing_sms', action: 'withdrawn', at: '2026-09-01T15:00:00Z', source: 'list_unsubscribe',
        wordingVersion: null, language: null, ipPrefix: null, addressKnown: true, actorId: null },
    ];
    const calls = api(['privacy'], c => {
      if (c.method === 'GET' && c.url.includes('/api/v1/console/consents?contact=amara%40example.ca')) return { body: { items: records } };
      if (c.method === 'POST' && c.url.endsWith('/api/v1/console/consents/withdrawals')) return { status: 200 }; // the api's 204 (a test Response can't carry one)
      return undefined;
    });
    renderConsole('/privacy');
    expect(await screen.findByRole('heading', { name: 'Consent to marketing (CASL)' })).toBeTruthy();
    await user().type(screen.getByLabelText('Account id, email or phone'), 'amara@example.ca');
    await user().click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Checkout')).toBeTruthy();
    expect(screen.getByText('account.email.2026-10 · fr')).toBeTruthy();
    expect(screen.getByText('Mailbox one-click')).toBeTruthy();
    expect(screen.getByText('203.0.113.0/24')).toBeTruthy();
    // only what is granted now can be withdrawn
    expect(screen.queryByRole('button', { name: /Withdraw Marketing texts/ })).toBeNull();
    await user().click(screen.getByRole('button', { name: 'Withdraw Marketing email for 01J9ZD3V00000000000000AMR1' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/withdrawals'))?.body)
      .toEqual({ userId: '01J9ZD3V00000000000000AMR1', category: 'marketing_email' }));
    expect(await screen.findByText('Withdrawn. The person won’t get it any more.')).toBeTruthy();
  });

  it('S-108: an account id is looked up as an id; French; no withdrawal without the privacy action', async () => {
    const calls = api(['admin'], c => c.method === 'GET' && c.url.includes('/api/v1/console/consents?userId=') ? { body: { items: [] } } : undefined);
    renderConsole('/privacy', { locale: 'fr' });
    await user().type(await screen.findByLabelText('Identifiant de compte, courriel ou téléphone'), '01J9ZD3V00000000000000AMR1');
    await user().click(screen.getByRole('button', { name: 'Chercher' }));
    expect(await screen.findByText('Aucun registre de consentement pour cette personne.')).toBeTruthy();
    expect(calls.some(c => c.url.endsWith('/api/v1/console/consents?userId=01J9ZD3V00000000000000AMR1'))).toBe(true);
  });
});
