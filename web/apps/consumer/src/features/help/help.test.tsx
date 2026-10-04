import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { HelpScreen } from './HelpScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: '01J9ZD3V0000000000000C0001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: null, initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
let viewer: typeof AMARA | null = null;
const server = (call: Call) => (call.url === '/bff/session' ? { body: { user: viewer, guestId: 'g_x' } } : undefined);
const open = (locale: 'en' | 'fr' = 'en') => {
  mockFetch(server);
  return renderApp('/help', { locale, routes: { help: () => <HelpScreen /> } });
};

beforeEach(() => { viewer = null; localStorage.clear(); sessionStorage.clear(); });
afterEach(() => vi.unstubAllGlobals());

describe('Help for guests and signed-in people (S-145, WCAG 3.2.6)', () => {
  it('reaches a guest from the footer and sends them to sign in for an order problem', async () => {
    open();
    expect(await screen.findByRole('heading', { level: 1, name: 'Help' })).toBeInTheDocument();
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Help' })).toHaveAttribute('href', '/help');
    expect(screen.getByText(/Orders, bookings and quotes belong to an account/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in for help with an order' }).getAttribute('href')).toContain('/sign-in?next=%2Faccount%3Ftab%3Dhelp');
    for (const name of ['Shopping as a guest', 'Payments', 'Accessibility', 'Contact us', 'Selling or offering a service?']) {
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument();
    }
    expect(screen.queryByRole('link', { name: /@/ })).toBeNull(); // no mailbox configured
    await expectNoAxeViolations(document.body);
  });

  it('links a signed-in person to their orders and Help & cases', async () => {
    viewer = AMARA;
    open();
    expect(await screen.findByRole('link', { name: 'Orders & bookings', description: undefined })).toBeInTheDocument();
    const cases = screen.getAllByRole('link', { name: 'Help & cases' });
    expect(cases.map(a => a.getAttribute('href'))).toContain('/account?tab=help');
  });

  it('is in French', async () => {
    open('fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Aide' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Accessibilité' })).toBeInTheDocument();
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Aide' })).toHaveAttribute('href', '/help');
  });
});
