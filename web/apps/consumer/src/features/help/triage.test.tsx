import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { HelpScreen } from './HelpScreen';
import { articleFor } from './HelpTriage';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: '01J9ZD3V0000000000000C0001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
let viewer: typeof AMARA | null = AMARA;
let triage: (call: Call) => { status?: number; body?: unknown } = () => ({ body: { category: 'missing_item', route: 'refund_request', urgent: false, summary: 'Bread missing.' } });
const server = (call: Call) => {
  if (call.url === '/bff/session') return { body: { user: viewer, guestId: 'g_x' } };
  if (call.url === '/api/v1/ai/status') return { body: { available: true, provider: 'fake' } };
  if (call.url === '/api/v1/me/help/triage') return triage(call);
  return undefined;
};
const open = (locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  renderApp('/help', { locale, routes: { help: () => <HelpScreen /> } });
  return calls;
};

beforeEach(() => { viewer = AMARA; localStorage.clear(); sessionStorage.clear(); });
afterEach(() => vi.unstubAllGlobals());

describe('help triage in the help centre (S-158 on S-132)', () => {
  it('suggests the case type and the article before anything is opened', async () => {
    const calls = open();
    const section = (await screen.findByLabelText('What happened?')).closest('section')!;
    const u = userEvent.setup({ delay: null });
    await u.click(within(section).getByRole('button', { name: /Suggest where to start/ }));
    expect(within(section).getByRole('alert').textContent).toBe('Tell us a little more (at least 10 characters).');
    await u.type(within(section).getByLabelText('What happened?'), 'My order came without the bread I paid for');
    await u.click(within(section).getByRole('button', { name: /Suggest where to start/ }));
    expect(await within(section).findByText('This looks like: Something missing')).toBeTruthy();
    expect(within(section).getByText(/Start a refund request from the order/)).toBeTruthy();
    expect(within(section).getByRole('link', { name: 'Go to Orders & bookings' }).getAttribute('href')).toBe('/account/orders');
    expect(within(section).getByRole('link', { name: /Read: A problem with an order/ }).getAttribute('href')).toBe('#help-problem');
    expect(calls.find(c => c.url === '/api/v1/me/help/triage')?.body).toEqual({ text: 'My order came without the bread I paid for' });
    await expectNoAxeViolations(document.body);
  });

  it('puts safety first and sends account matters to Help & cases', async () => {
    triage = () => ({ body: { category: 'safety', route: 'support', urgent: true, summary: 'Courier threatened me.' } });
    open();
    const section = (await screen.findByLabelText('What happened?')).closest('section')!;
    const u = userEvent.setup({ delay: null });
    await u.type(within(section).getByLabelText('What happened?'), 'The courier shouted at me and threatened me');
    await u.click(within(section).getByRole('button', { name: /Suggest where to start/ }));
    expect(await within(section).findByText(/call emergency services first/)).toBeTruthy();
    expect(within(section).getByRole('link', { name: 'Open Help & cases' }).getAttribute('href')).toBe('/account?tab=help');
  });

  it('says so when AI is unavailable (503), in French; guests are asked to sign in', async () => {
    triage = () => ({ status: 503, body: { code: 'ai_unavailable' } });
    open('fr');
    const section = (await screen.findByLabelText('Que s’est-il passé?')).closest('section')!;
    const u = userEvent.setup({ delay: null });
    await u.type(within(section).getByLabelText('Que s’est-il passé?'), 'Ma commande est arrivée sans le pain');
    await u.click(within(section).getByRole('button', { name: /Suggérer par où commencer/ }));
    await waitFor(() => expect(within(section).getByText(/Les suggestions ne sont pas disponibles pour l’instant/)).toBeTruthy());
    vi.unstubAllGlobals();
    viewer = null;
    open();
    expect(await screen.findByText(/Sign in to get a suggestion/)).toBeTruthy();
  });

  it('maps categories to this page’s sections', () => {
    expect(articleFor('billing')).toBe('help-pay');
    expect(articleFor('safety')).toBe('help-contact');
    expect(articleFor('late')).toBe('help-problem');
  });
});
