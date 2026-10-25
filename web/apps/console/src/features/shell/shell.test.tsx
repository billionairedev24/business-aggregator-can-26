import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { overview } from '../../test/fixtures';
import { renderConsole, staffApi, screenReady } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const sidebar = (name = 'Main navigation') => screen.getByRole('navigation', { name });
const navLabels = () => within(sidebar()).getAllByRole('link').map(a => a.textContent);
/** The accordion's group heads (the rail toggle has no text). */
// E-15 (S-174): a group is a list named by its caps heading (no collapsing)
const groupHeads = (name?: string) => [...sidebar(name).querySelectorAll('ul[aria-labelledby]')].map(ul => document.getElementById(ul.getAttribute('aria-labelledby')!)?.textContent);

describe('console shell (S-90, design 03)', () => {
  it('shows the top bar: brand, the market scope from the region model, the search, the person and role (S-177)', async () => {
    staffApi(['admin']);
    renderConsole('/');
    expect(await screen.findByRole('combobox', { name: 'Search the console' })).toHaveProperty('placeholder', 'Search sellers, orders, people');
    expect(screen.getByRole('search', { name: 'Search the console' })).toBeTruthy(); // S-109: a named landmark (landmark-unique)
    expect(screen.getByText('Console')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Change the market: All markets, Alberta + BC pilot' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    const account = screen.getByRole('button', { name: 'Account menu' });
    expect(account.textContent).toContain('Priya Natarajan');
    expect(account.textContent).toContain('Admin');
  });

  it('rescopes the screens to a market from the top bar, and remembers it (S-177)', async () => {
    const calls = staffApi(['admin'], c => (c.url.includes('/api/v1/console/overview') ? { body: overview() } : undefined));
    renderConsole('/');
    await screenReady();
    await userEvent.click(await screen.findByRole('button', { name: /^Change the market/ }));
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Calgary' }));
    await waitFor(() => expect(calls.some(c => c.url.includes('/api/v1/console/overview') && c.url.includes('market=calgary') && c.url.includes('province=AB'))).toBe(true));
    expect(screen.getByRole('button', { name: 'Change the market: Calgary, Alberta' })).toBeTruthy();
    expect(JSON.parse(localStorage.getItem('nl.console.scope') ?? '{}')).toEqual({ province: 'AB', market: 'calgary' });
  });

  it('searches sellers or orders from the top bar, as the role allows (S-177)', async () => {
    staffApi(['admin']);
    const { router } = renderConsole('/');
    const box = await screen.findByRole('combobox', { name: 'Search the console' });
    await userEvent.type(box, 'NL-48');
    await userEvent.click(screen.getByRole('option', { name: 'Orders and bookings with reference “NL-48”' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders'));
    expect(router.state.location.search).toMatchObject({ view: 'all', q: 'NL-48' });
  });

  it('an admin sees every group and screen', async () => {
    staffApi(['admin']);
    renderConsole('/');
    await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(groupHeads()).toEqual(['Operations', 'Marketplace', 'Platform']);
    expect(navLabels()).toContain('Overview');
  });

  it('shows verification and vetting as one Applications entry, lit on either screen (redesign § 3.12)', async () => {
    staffApi(['trust_safety']);
    renderConsole('/vetting');
    await screen.findByRole('navigation', { name: 'Main navigation' });
    const labels = navLabels();
    expect(labels).toContain('Applications');
    expect(labels).not.toContain('Listing vetting');
    expect(within(sidebar()).getByRole('link', { name: 'Applications' }).getAttribute('href')).toBe('/verification');
    expect(within(sidebar()).getByRole('link', { name: 'Applications' }).getAttribute('aria-current')).toBe('page');
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
    expect((await screenReady()).textContent).toBe('Overview');
    expect(screen.getByText('$212k')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Finance' })).toBeNull();
  });

  it('opens profile and on-call for every staff member, even without a console role', async () => {
    staffApi([], c => (c.url.includes('/api/v1/console/oncall') ? { body: { asOf: '2026-09-08T18:00:00Z', shifts: [], now: [], staff: [] } } : undefined));
    renderConsole('/on-call');
    expect(await screen.findByRole('heading', { level: 1, name: 'On-call & escalations' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('switches the role view among held roles: logged through the api, the sidebar narrows, later calls carry the role', async () => {
    const calls = staffApi(['trust_safety', 'finance'], c => (c.url.endsWith('/api/v1/console/team') ? { body: { roles: [], members: [] } }
      : c.url.includes('/api/v1/console/audit') ? { body: { items: [] } } : undefined));
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/team');
    await screen.findByRole('heading', { level: 1, name: 'Team, roles & audit' });
    // the accordion opens the current screen's group (Platform): trust & safety sees Team, not Finance
    expect(navLabels()).toContain('Team & audit');
    expect(navLabels()).not.toContain('Finance');
    expect(groupHeads()).toEqual(['Operations', 'Marketplace', 'Platform']);

    await user.click(screen.getByRole('button', { name: 'Account menu' }));
    await user.click(screen.getByRole('menuitem', { name: /Switch role view/ }));
    const finance = screen.getByRole('menuitemradio', { name: /^Finance/ });
    expect(finance.textContent).toContain('7 views · refund, payouts, record go-live gates'); // S-157: + offers
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
    expect(await screen.findByRole('combobox', { name: 'Rechercher dans la console' })).toBeTruthy();
    expect(groupHeads('Navigation principale')).toEqual(['Opérations', 'Marché', 'Plateforme']);
  });
});

function mockFetchSignedOut() {
  staffApi([], call => (call.url.endsWith('/bff/session') ? { status: 401 } : undefined));
}
