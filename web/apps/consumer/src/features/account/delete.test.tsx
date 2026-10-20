import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AccountDeletionPage, DeleteAccountScreen } from './DeleteAccountScreen';

vi.mock('../cart/stepUp', () => ({
  stepUpWithPasskey: vi.fn(async () => 'proof-passkey'),
  stepUpWithCode: vi.fn(async () => 'proof-totp'),
  enrolPasskey: vi.fn(async () => 'proof-enrol'),
  StepUpFailed: class extends Error {},
}));

const NADIA = { id: 'C9', firstName: 'Nadia', lastName: 'Roy', email: 'nadia@example.ca', phone: null, initials: 'NR', locale: 'en-CA', memberSince: '2026-03-02' };
const LAW = { code: 'ab_pipa', name: 'Personal Information Protection Act', shortName: 'PIPA', authority: 'the privacy commissioner', authorityUrl: 'https://example.ca/oipc' };
const erasure = (over: Record<string, unknown> = {}) => ({
  id: 'R7', reference: 'PR-1007', type: 'erasure', state: 'awaiting_verification', law: LAW, receivedAt: '2026-10-01T12:00:00Z',
  dueAt: '2026-11-15T23:59:59Z', extendedTo: null, scheduledFor: null, completedAt: null, codeSentTo: 'n•••@example.ca', codeChannel: 'email',
  decision: null, export: null, corrections: [], holdsOpen: 0, ...over,
});

let user: typeof NADIA | null;
let items: unknown[];
let preview: unknown[];
let calls: Call[];
const server = (c: Call) => {
  if (c.url === '/bff/session') return { body: { user, guestId: 'g' } };
  if (c.url === '/api/v1/me/privacy-requests' && c.method === 'GET') return { body: { items } };
  if (c.url === '/api/v1/me/privacy-requests' && c.method === 'POST') return { status: 201, body: erasure() };
  if (c.url === '/api/v1/me/privacy-requests/erasure-preview') return { body: { items: preview } };
  if (c.url === '/api/v1/me/privacy-requests/R7/verify') return { body: erasure({ state: 'verified', codeSentTo: null, scheduledFor: '2026-10-27T12:00:00Z' }) };
  return undefined;
};
const open = (path = '/account/delete', locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp(path, { locale, routes: { accountDelete: () => <DeleteAccountScreen />, accountDeletion: () => <AccountDeletionPage /> } });
};
const posts = (url: string) => calls.filter(c => c.method === 'POST' && c.url === url);

beforeEach(() => {
  user = NADIA;
  items = [];
  preview = [];
});
afterEach(() => vi.unstubAllGlobals());

describe('Account › Delete account (S-159)', () => {
  it('says what deleting affects now, needs "I understand", then confirms with the emailed code', async () => {
    preview = [
      { code: 'open_orders', count: 1, amountCents: 0, until: null },
      { code: 'held_payments', count: 2, amountCents: 4520, until: null },
      { code: 'points', count: 340, amountCents: 0, until: null },
      { code: 'plus', count: 1, amountCents: 0, until: '2026-11-30T00:00:00Z' },
    ];
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Delete your account' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    const list = screen.getByTestId('consequences');
    expect(within(list).getByText('1 order is on the way. Deletion waits until it’s delivered.')).toBeInTheDocument();
    expect(within(list).getByText(/\$45\.20 is held for 2 purchases under way/)).toBeInTheDocument();
    expect(within(list).getByText(/Your 340 points are lost/)).toBeInTheDocument();
    expect(within(list).getByText(/Your Plus membership won’t renew after/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
    expect(screen.getByText('Tick the box to confirm you understand.')).toBeInTheDocument();
    expect(posts('/api/v1/me/privacy-requests')).toHaveLength(0);
    await userEvent.click(screen.getByRole('checkbox', { name: /I understand/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
    expect(posts('/api/v1/me/privacy-requests')[0]?.body).toEqual({ type: 'erasure' });

    const dialog = await screen.findByRole('dialog', { name: 'Confirm it’s you' });
    expect(within(dialog).getByText('We emailed a code to n•••@example.ca.')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(within(dialog).getByText('Enter the 6-digit code we emailed you.')).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText('6-digit code'), '318604');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(posts('/api/v1/me/privacy-requests/R7/verify')[0]?.body).toEqual({ code: '318604' });
  });

  it('with nothing under way, says so', async () => {
    open();
    expect(await screen.findByText('Nothing is under way: deleting your account affects nothing else.')).toBeInTheDocument();
  });

  it('a deletion already asked for shows its date and can be cancelled', async () => {
    items = [erasure({ state: 'verified', scheduledFor: '2026-10-27T12:00:00Z', codeSentTo: null })];
    open();
    expect(await screen.findByText('You asked to delete your account (PR-1007).')).toBeInTheDocument();
    expect(screen.getByText(/Your account will be deleted on/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel deletion' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete my account' })).not.toBeInTheDocument();
  });

  it('signed out, offers the sign-in that comes back here', async () => {
    user = null;
    open();
    expect(await screen.findByRole('link', { name: 'Sign in to delete your account' })).toHaveAttribute('href', '/sign-in?next=%2Faccount%2Fdelete');
  });

  it('the public page explains the steps (Google Play), in French too', async () => {
    user = null;
    open('/account-deletion', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: /^Supprimer votre compte / })).toBeInTheDocument();
    expect(screen.getByText(/^Ouvrez l’application, allez dans Compte › Supprimer le compte/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Supprimer mon compte' })).toHaveAttribute('href', '/account/delete');
    await expectNoAxeViolations(document.body);
  });
});
