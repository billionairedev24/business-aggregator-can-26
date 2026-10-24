import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call, screenReady } from '../../test/render';
import type { Detail, Seller } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const seller = (o: Partial<Seller>): Seller => ({
  id: 'm1', name: 'Prairie Wrench', category: { id: 'c1', names: { en: 'Mobile mechanic', fr: 'Mécanicien mobile' } }, type: 'provider', province: 'AB', city: 'Calgary',
  tier: 'master', status: 'active', quality: 91, gmv90Cents: 1_940_000, disputeRate: 0.003, flags: [], ...o,
});
const SELLERS = [
  seller({}),
  seller({ id: 'm2', name: 'Bow River Mechanics', tier: 'trusted', quality: 78, gmv90Cents: 1_820_000, disputeRate: 0.024,
    flags: [{ kind: 'quality_below', value: 78, floor: 80 }, { kind: 'check_expiring', checkType: 'insurance', days: 21 }] }),
  seller({ id: 'm3', name: 'Fraser Electric', province: 'BC', tier: 'registered', quality: 85, gmv90Cents: 340_000, disputeRate: 0,
    flags: [{ kind: 'check_pending', checkType: 'licence', registry: 'TSBC' }], category: { id: 'c2', names: { en: 'Electrician' } } }),
  seller({ id: 'm4', name: 'Kensington Garage on Wheels', tier: 'registered', status: 'suspended', quality: 71, gmv90Cents: 190_000, disputeRate: 0.041,
    flags: [{ kind: 'trust_flag', rule: 'off_platform_payment' }] }),
];
// the api counts both over the rows it returns (SellersService: the live ones, and those of them with a flag)
const DIRECTORY = { asOf: '2026-09-08T18:00:00Z', active: 3, atRisk: 2, items: SELLERS, truncated: false };
const DETAIL: Detail = {
  asOf: '2026-09-08T18:00:00Z', seller: SELLERS[1]!, joinedAt: '2026-03-14T18:00:00Z', approvedAt: '2026-03-14T20:00:00Z', stripeAccount: 'acct_1K…',
  ratingAverage: 4.7, ratingCount: 88, quality: { value: 78, floor: 80 }, onTime: { value: 93, floor: 92 }, disputes: { value: 2.4, floor: 1.5 },
  signals: [{ key: 'on_time', value: 93, bar: 93, barFloor: 92, inverted: false }, { key: 'photos', value: 74, bar: 74, barFloor: 90, inverted: false }],
  checks: [
    { id: 'v1', checkType: 'kyc', status: 'verified' }, { id: 'v2', checkType: 'licence', registry: 'AMVIC', reference: '51022', status: 'verified' },
    { id: 'v3', checkType: 'insurance', status: 'verified', expiresAt: '2026-09-29T18:00:00Z' },
  ],
  trail: [{ id: 't1', action: 'tier_changed', reason: 'Quality 84 for 4 weeks', detail: { from: 'registered', to: 'trusted' }, actorName: 'Priya Natarajan', actorRole: 'admin', at: '2026-08-12T18:00:00Z' }],
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.includes('/api/v1/console/sellers/m2')) return { body: DETAIL };
    if (c.method === 'GET' && c.url.includes('/api/v1/console/sellers')) return { body: DIRECTORY };
    if (c.method === 'POST' && c.url.includes('/api/v1/console/merchants/')) return { body: { id: 'o1' } };
    return undefined;
  });
}
const card = (text: string) => screen.getAllByText(text).map(e => e.closest('tr, .nl-dt-card') as HTMLElement | null).find(Boolean) as HTMLElement;

describe('sellers & providers (S-82; E-15 S-177)', () => {
  it('lists every business: KPIs, status views with counts, dense rows with status tags', async () => {
    api(['trust_safety']);
    renderConsole('/sellers');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sellers & providers' })).toBeTruthy();
    expect(await screen.findByText('All markets · 4 businesses across Services, Shop and Food')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    const kpis = within(screen.getByRole('list', { name: 'Against the floors' }));
    expect(kpis.getByText('Live sellers').nextElementSibling?.textContent).toBe('3');
    expect(kpis.getByText('At risk').nextElementSibling?.textContent).toBe('2');
    expect(kpis.getByText('Sales, 90 days').nextElementSibling?.textContent).toBe('$42.9k');
    const tabs = screen.getByRole('tablist', { name: 'Businesses by status' });
    expect(within(tabs).getAllByRole('tab').map(x => x.textContent)).toEqual(['All4', 'Live3', 'Under review0', 'Paused0', 'Suspended1']);
    const bow = card('Bow River Mechanics');
    expect(within(bow).getByText(/Calgary · AB/)).toBeTruthy();
    expect(within(bow).getByText('Trusted')).toBeTruthy();
    expect(within(bow).getByText('$18,200.00')).toBeTruthy();
    expect(within(bow).getByText('2.4%')).toBeTruthy();
    expect(within(bow).getByText('Active').className).toContain('nl-status-success');
    expect(within(card('Fraser Electric')).getByText(/Calgary · BC pilot/)).toBeTruthy();
    expect(within(card('Kensington Garage on Wheels')).getByText('Suspended').className).toContain('nl-status-danger');
  });

  it('filters by view, department and quick filters, and keeps them in the address', async () => {
    api(['admin']);
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/sellers');
    await screenReady();
    await user.click(screen.getByRole('tab', { name: /^Suspended/ }));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench')).toBeNull());
    expect(screen.getByText('Kensington Garage on Wheels')).toBeTruthy();
    expect(router.state.location.search).toEqual({ view: 'suspended' });
    await user.click(screen.getByRole('tab', { name: /^All/ }));
    await user.click(screen.getByRole('radio', { name: 'Shop' }));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench')).toBeNull());
    await user.click(screen.getByRole('radio', { name: 'All' }));
    await user.click(screen.getByRole('button', { name: 'Licence expiring' }));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench')).toBeNull());
    expect(screen.getByText('Bow River Mechanics')).toBeTruthy();
    expect(router.state.location.search).toEqual({ expiring: true });
  });

  it('saves a view on this device and opens it again', async () => {
    api(['admin']);
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/sellers?view=suspended&risk=true');
    await screenReady();
    await user.click(screen.getByRole('button', { name: 'Saved views' }));
    await user.click(screen.getByRole('menuitem', { name: 'Save this view…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Save this view' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Name of the view' }), 'Suspended at risk');
    await user.click(within(dialog).getByRole('button', { name: 'Save view' }));
    expect(JSON.parse(localStorage.getItem('nl.console.views.sellers') ?? '[]')).toEqual([{ name: 'Suspended at risk', state: { view: 'suspended', risk: true } }]);
    await user.click(screen.getByRole('tab', { name: /^All/ }));
    await user.click(screen.getByRole('button', { name: 'Saved views' }));
    await user.click(screen.getByRole('menuitemradio', { name: 'Suspended at risk' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ view: 'suspended', risk: true }));
  });

  it('suspends selected businesses from the red bulk action, one request each with the reason; support only views', async () => {
    const calls = api(['trust_safety']);
    const user = userEvent.setup({ delay: null });
    const first = renderConsole('/sellers');
    await screenReady();
    await user.click(within(card('Prairie Wrench')).getByRole('checkbox'));
    await user.click(within(card('Bow River Mechanics')).getByRole('checkbox'));
    const bulk = screen.getByRole('region', { name: '2 selected' });
    const suspend = within(bulk).getByRole('button', { name: 'Suspend…' });
    expect(suspend.className).toContain('nl-dt-bulk-danger');
    await user.click(suspend);
    const dialog = await screen.findByRole('dialog', { name: 'Suspend · 2 businesses' });
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Off-platform payments');
    const confirm = within(dialog).getByRole('button', { name: 'Suspend 2' });
    expect(confirm.className).toContain('btn-destructive');
    await user.click(confirm);
    await waitFor(() => expect(calls.filter(c => c.method === 'POST').map(c => c.url.replace(/.*merchants\//, ''))).toEqual(['m1/suspend', 'm2/suspend']));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ reason: 'Off-platform payments' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
    first.unmount();
    api(['support']);
    renderConsole('/sellers');
    await screenReady();
    expect(screen.getByText(/View only · Support/)).toBeTruthy();
    await user.click(within(card('Prairie Wrench')).getByRole('checkbox'));
    expect(screen.queryByRole('button', { name: 'Suspend…' })).toBeNull();
  });

  it('follows the top bar’s scope and the global search', async () => {
    const calls = api(['admin']);
    renderConsole('/sellers?q=bow&province=AB');
    await screenReady();
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/sellers?q=bow&province=AB'))).toBe(true));
    expect((screen.getByRole('searchbox', { name: /Search/ }) as HTMLInputElement).value).toBe('bow');
  });

  it('opens a business’s page from the list', async () => {
    api(['trust_safety']);
    const user = userEvent.setup({ delay: null });
    const { router } = renderConsole('/sellers');
    await screenReady();
    await user.click(within(card('Bow River Mechanics')).getByRole('button', { name: /Open/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/sellers/m2'));
  });

  it('shows the seller page: status, key facts, KPIs against floors, tabs and the action panel', async () => {
    api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/sellers/m2');
    expect(await screen.findByRole('heading', { level: 1, name: 'Bow River Mechanics' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    expect(screen.getByText('Active').className).toContain('nl-status-success');
    const fact = (label: string) => screen.getByText(label, { selector: 'dt' }).nextElementSibling?.textContent;
    expect(fact('Type')).toBe('Services · Mobile mechanic');
    expect(fact('Location')).toBe('Calgary · AB');
    expect(fact('Joined')).toBe('Mar 2026');
    expect(fact('Stripe account')).toBe('acct_1K…');
    const kpi = (label: string) => screen.getByText(label).parentElement!;
    expect(kpi('Rating · 88 reviews').querySelector('.bo-kpi-value')?.textContent).toBe('4.7');
    expect(kpi('Quality · Trusted floor 80').querySelector('.bo-kpi-delta')?.textContent).toBe('Below the floor');
    expect(kpi('On time · floor 92').querySelector('.bo-kpi-delta')).toBeNull();
    expect(kpi('Disputes · floor 1.5').querySelector('.bo-kpi-delta')?.getAttribute('data-trend')).toBe('down');
    expect(kpi('90-day GMV').querySelector('.bo-kpi-value')?.textContent).toBe('$18.2k');
    expect(screen.getByText('On-time arrival')).toBeTruthy();
    await user.click(screen.getByRole('tab', { name: /Verification on file/ }));
    expect(screen.getByText('KYC verified')).toBeTruthy();
    expect(screen.getByText('AMVIC 51022')).toBeTruthy();
    expect(screen.getByText('Insurance expires in 21 d')).toBeTruthy();
    await user.click(screen.getByRole('tab', { name: /Timeline & audit/ }));
    expect(screen.getByRole('tabpanel').textContent).toContain('Registered → Trusted by Priya Natarajan · “Quality 84 for 4 weeks”');
    expect(screen.getByText('Approved · Trusted')).toBeTruthy();
    const panel = within(screen.getByRole('complementary', { name: 'Oversight actions' }));
    expect(panel.getByRole('button', { name: 'Suspend…' }).className).toContain('btn-destructive');
  });

  it('applies an oversight action: change tier and require re-verification', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/sellers/m2');
    await screenReady();
    await user.click(screen.getByRole('button', { name: 'Change tier…' }));
    let dialog = await screen.findByRole('dialog', { name: 'Change tier · Bow River Mechanics' });
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'New tier' }), 'registered');
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Quality below floor 3 weeks');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/merchants/m2/tier'))?.body).toEqual({ tier: 'registered', reason: 'Quality below floor 3 weeks' }));
    expect(await screen.findByText('Logged in audit. Seller can appeal within 14 days.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Require re-verification…' }));
    dialog = await screen.findByRole('dialog', { name: 'Require re-verification · Bow River Mechanics' });
    const options = within(within(dialog).getByRole('combobox', { name: 'Check' })).getAllByRole('option').map(o => o.textContent);
    expect(options).toEqual(['Licence · AMVIC · 51022', 'Insurance']);
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Check' }), 'v3');
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Certificate looks altered');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/merchants/m2/reverification'))?.body).toEqual({ verificationId: 'v3', reason: 'Certificate looks altered' }));
  });

  it('hides from search, and shows a business the rating floor hid again', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    const first = renderConsole('/sellers/m2');
    await screenReady();
    expect(screen.getByText('Existing customers can still book')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Hide from search…' }));
    let dialog = await screen.findByRole('dialog', { name: 'Hide from search · Bow River Mechanics' });
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Repeated late arrivals');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/merchants/m2/search'))?.body).toEqual({ hidden: true, reason: 'Repeated late arrivals' }));
    first.unmount();
    const hidden = { ...SELLERS[1]!, searchHidden: 'rating_floor' as const };
    const calls2 = api(['admin'], c => {
      if (c.method === 'GET' && c.url.includes('/api/v1/console/sellers/m2')) return { body: { ...DETAIL, seller: hidden,
        trail: [{ id: 't2', action: 'search_hidden', reason: 'Average rating 3.90 over 90 days is below the floor of 4.2', detail: {}, actorRole: 'system', at: '2026-09-01T05:23:00Z' }] } };
      if (c.method === 'GET' && c.url.includes('/api/v1/console/sellers')) return { body: { ...DIRECTORY, items: [hidden] } };
      return undefined;
    });
    const second = renderConsole('/sellers');
    expect(await screen.findByText('Hidden from search · rating floor · Quality < floor · Insurance 21 d')).toBeTruthy();
    second.unmount();
    renderConsole('/sellers/m2');
    await screenReady();
    expect(screen.getAllByText('Hidden from search · rating floor').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('tab', { name: /Timeline & audit/ }));
    expect(screen.getByText('Hidden from search by the trust rules · “Average rating 3.90 over 90 days is below the floor of 4.2”')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Show in search…' }));
    dialog = await screen.findByRole('dialog', { name: 'Show in search · Bow River Mechanics' });
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Reviews checked by hand');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls2.find(c => c.url.endsWith('/merchants/m2/search'))?.body).toEqual({ hidden: false, reason: 'Reviews checked by hand' }));
  });

  it('shows the api’s validation and conflict messages in the dialog', async () => {
    api(['admin'], c => (c.method === 'POST' ? { status: 422, body: { errors: [{ field: 'reason', rule: 'required', message: 'Give the reason the business will see.' }] } } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/sellers/m2');
    await screenReady();
    await user.click(screen.getByRole('button', { name: 'Suspend…' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));
    expect(await within(dialog).findByText('Give the reason the business will see.')).toBeTruthy();
  });

  it('keeps oversight off for roles without the action and denies roles that don’t open sellers', async () => {
    api(['support']);
    const first = renderConsole('/sellers/m2');
    await screenReady();
    expect((screen.getByRole('button', { name: 'Suspend…' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Your role can’t apply this action.')).toBeTruthy();
    first.unmount();
    staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/sellers');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
  });

  it('speaks French', async () => {
    api(['admin']);
    renderConsole('/sellers', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Vendeurs et fournisseurs' })).toBeTruthy();
    expect(await screen.findByText('Tous les marchés · 4 entreprises en Services, Boutique et Repas')).toBeTruthy();
    expect(within(card('Prairie Wrench')).getByText('Mécanicien mobile')).toBeTruthy();
    expect(within(card('Bow River Mechanics')).getByText('18 200,00 $')).toBeTruthy();
  });
});
