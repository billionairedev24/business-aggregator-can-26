import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AccountScreen } from './AccountScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

vi.mock('../cart/stepUp', () => ({
  stepUpWithPasskey: vi.fn(async () => 'proof-passkey'),
  stepUpWithCode: vi.fn(async () => 'proof-totp'),
  enrolPasskey: vi.fn(async () => 'proof-enrol'),
  StepUpFailed: class extends Error {},
}));

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550148', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const PROFILE = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550148', locale: 'en-CA', memberSince: '2026-03-02',
  pronouns: null, birthday: null, reliability: null, erasureRequestedAt: null };
const LAW = { code: 'ab_pipa', name: 'Personal Information Protection Act', shortName: 'PIPA', authority: 'the privacy commissioner', authorityUrl: 'https://example.ca/oipc' };
const request = (over: Record<string, unknown>) => ({
  id: 'R1', reference: 'PR-1001', type: 'access', state: 'awaiting_verification', law: LAW, receivedAt: '2026-10-01T12:00:00Z',
  dueAt: '2026-11-15T23:59:59Z', extendedTo: null, scheduledFor: null, completedAt: null, codeSentTo: '•••• 0148', decision: null,
  export: null, corrections: [], holdsOpen: 0, ...over,
});

let calls: Call[];
let items: unknown[];
let opened: Record<string, unknown> | { status: number; body: unknown };
const server = (c: Call) => {
  if (c.url === '/bff/session') return { body: { user: AMARA, guestId: 'g', sid: 'S1' } };
  if (c.url === '/api/v1/me/account-summary') return { body: {} };
  if (c.url === '/api/v1/me/profile') return { body: PROFILE };
  if (c.url === '/api/v1/me/privacy-requests' && c.method === 'GET') return { body: { items } };
  if (c.url === '/api/v1/me/privacy-requests' && c.method === 'POST') return 'status' in opened ? opened as { status: number; body: unknown } : { status: 201, body: opened };
  if (c.url === '/api/v1/me/privacy-requests/correctable-fields') return { body: { items: ['legalName', 'phone', 'receiptName', 'reviewName'] } };
  if (c.url === '/api/v1/me/privacy-requests/R1/verify') return { body: request({ state: 'verified', codeSentTo: null }) };
  if (c.url === '/api/v1/me/privacy-requests/R1/withdraw') return { body: request({ state: 'withdrawn' }) };
  if (c.url === '/api/v1/me/privacy-requests/R1/download-link') return { body: { url: '/api/v1/public/privacy-exports/tok', summaryUrl: '/api/v1/public/privacy-exports/tok?part=summary', expiresAt: '2026-10-02T12:15:00Z' } };
  return undefined;
};
const open = (locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp('/account?tab=profile', { locale, routes: { account: () => <AccountScreen /> } });
};
const posts = (url: string) => calls.filter(c => c.method === 'POST' && c.url === url);

beforeEach(() => {
  items = [];
  opened = request({});
});
afterEach(() => vi.unstubAllGlobals());

describe('Your data (S-105 privacy requests)', () => {
  it('asks for a copy, confirms with the texted code and says when we answer', async () => {
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Download my data' }));
    expect(posts('/api/v1/me/privacy-requests')[0]?.body).toEqual({ type: 'access' });
    const dialog = await screen.findByRole('dialog', { name: 'Confirm it’s you' });
    await expectNoAxeViolations(document.body); // S-109
    expect(within(dialog).getByText('We texted a code to •••• 0148.')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(within(dialog).getByText('Enter the 6-digit code we texted you.')).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText('6-digit code'), '123456');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(posts('/api/v1/me/privacy-requests/R1/verify')[0]?.body).toEqual({ code: '123456' });
  });

  it('confirms with the passkey instead (the step-up proof goes as X-Step-Up)', async () => {
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Download my data' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Use my passkey instead' }));
    await vi.waitFor(() => expect(posts('/api/v1/me/privacy-requests/R1/verify')).toHaveLength(1));
    expect(posts('/api/v1/me/privacy-requests/R1/verify')[0]?.headers['x-step-up']).toBe('proof-passkey');
  });

  it('lists requests with their law and deadline, and downloads a ready copy through a short-lived link', async () => {
    items = [
      request({ state: 'completed', export: { ready: true, expiresAt: '2026-10-09T12:00:00Z' }, codeSentTo: null }),
      request({ id: 'R2', reference: 'PR-1002', type: 'erasure', state: 'verified', scheduledFor: '2026-10-08T12:00:00Z', codeSentTo: null }),
    ];
    open();
    expect(await screen.findByText(/Ready to download until/)).toBeInTheDocument();
    expect(screen.getByText(/Your account will be deleted on/)).toBeInTheDocument();
    expect(screen.getByText(/We answer by .* \(PIPA\)\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete my account…' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Download' }));
    expect(await screen.findByRole('link', { name: 'Data (JSON)' })).toHaveAttribute('href', '/api/v1/public/privacy-exports/tok');
    expect(screen.getByRole('link', { name: 'Summary' })).toHaveAttribute('href', '/api/v1/public/privacy-exports/tok?part=summary');
    expect(posts('/api/v1/me/privacy-requests/R1/download-link')).toHaveLength(1);
  });

  it('deletion asks first, says what is kept, and can be cancelled before it starts', async () => {
    opened = request({ type: 'erasure' });
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account…' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete your account?' });
    expect(within(dialog).getByText(/receipts, tax and payment records — stays, without your name/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue' }));
    expect(posts('/api/v1/me/privacy-requests')[0]?.body).toEqual({ type: 'erasure' });
    expect(await screen.findByRole('dialog', { name: 'Confirm it’s you' })).toBeInTheDocument();
  });

  it('a second request of the same kind says we are on it', async () => {
    opened = { status: 409, body: { code: 'request_open', detail: 'You already asked for this. We’re working on it.' } };
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Download my data' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('You already asked for this. We’re working on it.');
  });

  it('asks for a correction of a field people can’t change themselves', async () => {
    opened = request({ type: 'correction' });
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Ask for a correction' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ask for a correction' });
    await userEvent.selectOptions(await within(dialog).findByLabelText('What to correct'), 'receiptName');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));
    expect(within(dialog).getByText('Enter the correct value, up to 200 characters.')).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText('The correct value'), 'A. Osei-Mensah');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));
    expect(posts('/api/v1/me/privacy-requests')[0]?.body).toEqual({ type: 'correction', corrections: [{ field: 'receiptName', value: 'A. Osei-Mensah' }] });
  });

  it('speaks French', async () => {
    items = [request({ state: 'rejected', decision: 'identity_not_verified', codeSentTo: null })];
    open('fr');
    expect(await screen.findByRole('heading', { name: 'Vos données' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Télécharger mes données' })).toBeInTheDocument();
    expect(await screen.findByText(/nous n’avons pas pu confirmer votre identité/)).toBeInTheDocument();
  });
});
