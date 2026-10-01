import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import type { Detail, Seller } from './api';

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
const DIRECTORY = { asOf: '2026-09-08T18:00:00Z', active: 1204, atRisk: 41, items: SELLERS, truncated: false };
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

describe('sellers & providers (S-82, design 03)', () => {
  it('lists every business with tier, quality, GMV, disputes, flags and status', async () => {
    api(['trust_safety']);
    renderConsole('/sellers');
    expect(await screen.findByRole('heading', { level: 1, name: '1,204 active · 41 at risk' })).toBeTruthy();
    const bow = card('Bow River Mechanics');
    expect(within(bow).getByText('Services · AB')).toBeTruthy();
    expect(within(bow).getByText('Trusted')).toBeTruthy();
    expect(within(bow).getByText('$18.2k')).toBeTruthy();
    expect(within(bow).getByText('2.4%')).toBeTruthy();
    expect(within(bow).getByText('Quality < floor · Insurance 21 d')).toBeTruthy();
    expect(within(card('Fraser Electric')).getByText('Services · BC pilot')).toBeTruthy();
    expect(within(card('Fraser Electric')).getByText('Licence TSBC pending')).toBeTruthy();
    expect(within(card('Kensington Garage on Wheels')).getByText('Off-platform payment mention')).toBeTruthy();
    expect(within(card('Kensington Garage on Wheels')).getByText('Suspended')).toBeTruthy();
  });

  it('suspends from the list with a reason, and support only views', async () => {
    const calls = api(['trust_safety']);
    const user = userEvent.setup({ delay: null });
    const first = renderConsole('/sellers');
    await screen.findByRole('heading', { level: 1 });
    await user.click(within(card('Prairie Wrench')).getByRole('button', { name: /Suspend/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Suspend · Prairie Wrench' });
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Off-platform payments');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/merchants/m1/suspend'))).toBe(true));
    const post = calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ reason: 'Off-platform payments' });
    expect(post.headers['X-Console-Role']).toBe('trust_safety');
    first.unmount();
    api(['support']);
    renderConsole('/sellers');
    await screen.findByRole('heading', { level: 1 });
    expect(screen.getByText(/View only · Support/)).toBeTruthy();
    expect(within(card('Prairie Wrench')).queryByRole('button', { name: /Suspend/ })).toBeNull();
  });

  it('filters at risk, searches and filters by province', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/sellers');
    await screen.findByRole('heading', { level: 1 });
    await user.click(screen.getByRole('button', { name: 'At risk' }));
    await waitFor(() => expect(screen.queryByText('Prairie Wrench')).toBeNull());
    expect(screen.getByText('Bow River Mechanics')).toBeTruthy();
    await user.type(screen.getByRole('searchbox', { name: 'Search a business' }), 'bow{Enter}');
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/sellers?q=bow'))).toBe(true));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Province' }), 'AB');
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/api/v1/console/sellers?q=bow&province=AB'))).toBe(true));
  });

  it('shows the seller detail: KPIs against floors, signals, timeline and verifications', async () => {
    api(['admin']);
    renderConsole('/sellers/m2');
    expect(await screen.findByRole('heading', { level: 1, name: 'Bow River Mechanics' })).toBeTruthy();
    expect(screen.getByText('Services · Mobile mechanic · Calgary · joined Mar 2026 · Stripe acct_1K…')).toBeTruthy();
    expect(screen.getByText('rating · 88 reviews').previousElementSibling?.textContent).toBe('4.7');
    expect(screen.getByText('quality · Trusted floor 80').previousElementSibling?.textContent).toBe('78');
    expect(screen.getByText('on time · floor 92').previousElementSibling?.textContent).toBe('93%');
    expect(screen.getByText('disputes · floor 1.5').previousElementSibling?.textContent).toBe('2.4%');
    expect(screen.getByText('90-day GMV').previousElementSibling?.textContent).toBe('$18.2k');
    expect(screen.getByText('On-time arrival')).toBeTruthy();
    expect(screen.getByText('Completion photos')).toBeTruthy();
    expect(screen.getByText('Registered → Trusted by Priya Natarajan · “Quality 84 for 4 weeks”')).toBeTruthy();
    expect(screen.getByText('Approved · Trusted')).toBeTruthy();
    expect(screen.getByText('KYC verified')).toBeTruthy();
    expect(screen.getByText('AMVIC 51022')).toBeTruthy();
    expect(screen.getByText('Insurance expires in 21 d')).toBeTruthy();
  });

  it('applies an oversight action: change tier and require re-verification', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/sellers/m2');
    await screen.findByRole('heading', { level: 1 });
    await user.click(screen.getByRole('radio', { name: /Change tier/ }));
    await user.click(screen.getByRole('button', { name: 'Apply: Change tier' }));
    let dialog = await screen.findByRole('dialog', { name: 'Change tier · Bow River Mechanics' });
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'New tier' }), 'registered');
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Quality below floor 3 weeks');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/merchants/m2/tier'))?.body).toEqual({ tier: 'registered', reason: 'Quality below floor 3 weeks' }));
    expect(await screen.findByRole('button', { name: 'Applied · seller notified' })).toBeTruthy();
    expect(screen.getByText('Logged in audit. Seller can appeal within 14 days.')).toBeTruthy();
    await user.click(screen.getByRole('radio', { name: /Require re-verification/ }));
    await user.click(screen.getByRole('button', { name: 'Apply: Require re-verification' }));
    dialog = await screen.findByRole('dialog', { name: 'Require re-verification · Bow River Mechanics' });
    const options = within(within(dialog).getByRole('combobox', { name: 'Check' })).getAllByRole('option').map(o => o.textContent);
    expect(options).toEqual(['Licence · AMVIC · 51022', 'Insurance']);
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Check' }), 'v3');
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Certificate looks altered');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/merchants/m2/reverification'))?.body).toEqual({ verificationId: 'v3', reason: 'Certificate looks altered' }));
  });

  it('shows the api’s validation and conflict messages in the dialog', async () => {
    api(['admin'], c => (c.method === 'POST' ? { status: 422, body: { errors: [{ field: 'reason', rule: 'required', message: 'Give the reason the business will see.' }] } } : undefined));
    const user = userEvent.setup({ delay: null });
    renderConsole('/sellers/m2');
    await screen.findByRole('heading', { level: 1 });
    await user.click(screen.getByRole('button', { name: 'Suspend…' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await within(dialog).findByText('Give the reason the business will see.')).toBeTruthy();
  });

  it('keeps oversight off for roles without the action and denies roles that don’t open sellers', async () => {
    api(['support']);
    const first = renderConsole('/sellers/m2');
    await screen.findByRole('heading', { level: 1 });
    expect((screen.getByRole('button', { name: 'Suspend…' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Apply: Suspend' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Your role can’t apply this action.')).toBeTruthy();
    first.unmount();
    staffApi(['dispatch'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    renderConsole('/sellers');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
  });

  it('speaks French', async () => {
    api(['admin']);
    renderConsole('/sellers', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: /1\s204 actifs · 41 à risque/ })).toBeTruthy();
    expect(within(card('Prairie Wrench')).getByText('Mécanicien mobile')).toBeTruthy();
  });
});
