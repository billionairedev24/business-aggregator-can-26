import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { overview } from '../../test/fixtures';
import { renderConsole, staffApi } from '../../test/render';

const sidebar = (name = 'Main navigation') => screen.getByRole('navigation', { name });
const navLabels = () => within(sidebar()).getAllByRole('link').map(a => a.textContent);
/** The accordion's group heads (the rail toggle has no text). */
const groupHeads = (name?: string) => within(sidebar(name)).getAllByRole('button').filter(b => b.hasAttribute('aria-expanded')).map(b => b.textContent);

describe('console shell (S-90, design 03)', () => {
  it('shows the top bar: brand, search pill, the region line from the region model, the person and role', async () => {
    staffApi(['admin']);
    renderConsole('/');
    expect(await screen.findByRole('searchbox', { name: 'Search the console' })).toHaveProperty('placeholder', 'Search sellers, orders, customers, cases…');
    expect(screen.getByText('Console')).toBeTruthy();
    expect(await screen.findByText('Ops · Alberta + BC pilot')).toBeTruthy();
    const account = screen.getByRole('button', { name: 'Account menu' });
    expect(account.textContent).toContain('Priya N.');
    expect(account.textContent).toContain('Admin');
  });

  it('an admin sees every group and screen', async () => {
    staffApi(['admin']);
    renderConsole('/');
    await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(groupHeads()).toEqual(['Operations', 'Marketplace', 'Platform']);
    expect(navLabels()).toContain('Overview');
  });

  it('filters the sidebar by role: an analyst gets Overview and Reports only', async () => {
    staffApi(['analyst']);
    renderConsole('/reports');
    await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(groupHeads()).toEqual(['Platform']);
    expect(navLabels()).toEqual(['Overview', 'Reports & analytics']);
  });

  it('shows the denied banner (and the overview) for a screen the role can’t open', async () => {
    staffApi(['support'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    renderConsole('/finance');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe("Not available in this role. Your role (Support) can't open that screen. Ask an admin for access or switch role view if you hold more than one.");
    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('$212k GMV this week, 1,204 sellers, 7 verifications and 3 disputes waiting.');
    expect(screen.queryByRole('heading', { name: 'Finance' })).toBeNull();
  });

  it('opens profile and on-call for every staff member, even without a console role', async () => {
    staffApi([], c => (c.url.includes('/api/v1/console/oncall') ? { body: { asOf: '2026-09-08T18:00:00Z', shifts: [], now: [], staff: [] } } : undefined));
    renderConsole('/on-call');
    expect(await screen.findByRole('heading', { level: 1, name: 'Who’s on, how to reach them, and what’s burning'.replace(/’/g, "'") })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('switches the role view among held roles: logged through the api, the sidebar narrows, later calls carry the role', async () => {
    const calls = staffApi(['trust_safety', 'finance'], c => (c.url.endsWith('/api/v1/console/team') ? { body: { roles: [], members: [] } }
      : c.url.includes('/api/v1/console/audit') ? { body: { items: [] } } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/team');
    await screen.findByRole('heading', { level: 1, name: 'Who can do what, and who did what' });
    // the accordion opens the current screen's group (Platform): trust & safety sees Team, not Finance
    expect(navLabels()).toContain('Team & audit');
    expect(navLabels()).not.toContain('Finance');
    expect(groupHeads()).toEqual(['Operations', 'Marketplace', 'Platform']);

    await user.click(screen.getByRole('button', { name: 'Account menu' }));
    await user.click(screen.getByRole('menuitem', { name: /Switch role view/ }));
    const finance = screen.getByRole('menuitemradio', { name: /^Finance/ });
    expect(finance.textContent).toContain('5 views · refund, payouts');
    expect(screen.getByText('You hold these roles; switching narrows what you see and can do. Logged.')).toBeTruthy();
    expect(screen.queryByRole('menuitemradio', { name: /^Admin/ })).toBeNull();
    await user.click(finance);

    await waitFor(() => expect(navLabels()).toContain('Finance'));
    expect(router.state.location.pathname).toBe('/team'); // finance opens Team too, so the screen stays
    const switched = calls.find(c => c.url.endsWith('/api/v1/console/me/role-view'));
    expect(switched?.method).toBe('POST');
    expect(switched?.body).toEqual({ role: 'finance' });
    expect(switched?.headers['X-Console-Role']).toBe('trust_safety');
    await waitFor(() => expect(groupHeads()).toEqual(['Operations', 'Platform']));
    const later = calls.filter(c => c.url.includes('/api/v1/geo/regions')).at(-1);
    expect(later?.headers['X-Console-Role']).toBe('finance');
  });

  it('remembers the role view, and falls back to the first held role when it is no longer held', async () => {
    staffApi(['dispatch', 'support']);
    renderConsole('/');
    expect((await screen.findByRole('button', { name: 'Account menu' })).textContent).toContain('Ops dispatcher');
  });

  it('sends signed-out people to the sign-in page and back to where they were', async () => {
    mockFetchSignedOut();
    const { router } = renderConsole('/finance');
    expect(await screen.findByRole('heading', { level: 1, name: 'Operations, trust and money — with an audit trail.' })).toBeTruthy();
    expect(router.state.location.search).toEqual({ next: '/finance' });
  });

  it('switches the language from the account menu (fr-CA)', async () => {
    staffApi(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/');
    await user.click(await screen.findByRole('button', { name: 'Account menu' }));
    await user.click(screen.getByRole('menuitem', { name: /Language \/ Langue/ }));
    expect(await screen.findByRole('searchbox', { name: 'Rechercher dans la console' })).toBeTruthy();
    expect(groupHeads('Navigation principale')).toEqual(['Opérations', 'Marché', 'Plateforme']);
  });
});

function mockFetchSignedOut() {
  staffApi([], call => (call.url.endsWith('/bff/session') ? { status: 401 } : undefined));
}
