import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { BusinessTab } from './BusinessTab';
import { NotificationsTab, eventsFor } from './NotificationsTab';
import { SecurityTab, deviceName } from './SecurityTab';
import { TeamTab } from './TeamTab';
import { ApiTab, ago } from './ApiTab';
import { businessErrors, dollarsToCents, inviteErrors, webhookErrors } from './validation';

let role = 'owner';
let type = 'provider';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role, useMerchant: () => ({ id: 'm1', type, role }) }));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
  useNavigate: () => vi.fn(),
}));
vi.mock('../../lib/session', async orig => ({ ...(await orig<typeof import('../../lib/session')>()), useSession: () => ({ data: { user: { email: 'ravi@prairiewrench.ca' }, sid: 'sess-bff' } }) }));

const S = '/api/v1/merchants/m1/settings';
const business = { type: 'provider', structure: 'corp_ab', displayName: 'Prairie Wrench', legalName: 'Prairie Wrench Automotive Ltd.', gstNumber: '781234567 RT0001', gstRequired: true, serviceArea: 'Calgary + 40 km', cancellationPolicy: '12h', autoAcceptQuoteCents: 15000, languages: ['en', 'pa'], storeSlug: 'prairie-wrench' };
const compliance = {
  business: { type: 'provider', displayName: 'Prairie Wrench', legalName: 'PW Ltd.' },
  stripe: { status: 'not_connected', chargesEnabled: false, payoutsEnabled: false, requirements: [], instantPayouts: false },
  taxPeriod: '2026-Q3', tax: [], obligations: { currentVersion: '2.3', upToDate: false }, dueCount: 0, documents: [
  { id: 'a', checkType: 'licence', registry: 'AMVIC', reference: '44812', label: 'AMVIC 44812', status: 'verified', expiresAt: '2027-01-31T07:00:00Z', due: false, dueSoon: false },
  { id: 'r', checkType: 'licence', registry: 'Red Seal', reference: 'x', label: 'Red Seal certificate', status: 'verified', due: false, dueSoon: false },
] };
const team = {
  members: [
    { userId: 'u1', name: 'Ravi Sandhu', role: 'owner', secondFactor: 'passkey', you: true, joinedAt: '2026-01-05T17:00:00Z' },
    { userId: 'u2', name: 'Jas Gill', role: 'technician', secondFactor: 'totp', you: false, joinedAt: '2026-01-12T17:00:00Z' },
    { userId: 'u3', name: 'Priya Sandhu', role: 'bookkeeper', secondFactor: 'sms', you: false, joinedAt: '2026-01-12T17:00:00Z' },
  ],
  invitations: [], roles: ['owner', 'technician', 'bookkeeper'],
};

afterEach(() => { vi.unstubAllGlobals(); role = 'owner'; type = 'provider'; });

describe('Settings › Business', () => {
  it('validates on submit with the exact messages and the attention summary', async () => {
    mockFetch({ [`GET ${S}/business`]: () => business, 'GET /api/v1/merchants/m1/compliance': () => compliance });
    const user = userEvent.setup();
    renderWithProviders(<BusinessTab />);
    const name = await screen.findByLabelText('Business name');
    await user.clear(name);
    await user.clear(screen.getByLabelText('GST number'));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(screen.getByText('2 things need attention.')).toBeTruthy();
    expect(screen.getByText('Enter the name customers will see.')).toBeTruthy();
    expect(screen.getByText('Required for corporations, co-ops and non-profits.')).toBeTruthy();
  });

  it('saves, sends cents, and maps a server 422 onto the field', async () => {
    let first = true;
    const calls = mockFetch({
      [`GET ${S}/business`]: () => business,
      'GET /api/v1/merchants/m1/compliance': () => compliance,
      [`PUT ${S}/business`]: () => {
        if (first) { first = false; throw { status: 422, body: { errors: [{ field: 'gstNumber', rule: 'format', message: 'Format is 9 digits + RT0001 (e.g. 123456789 RT0001).' }] } }; }
        return business;
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<BusinessTab />);
    await screen.findByLabelText('Business name');
    expect(await screen.findByText('Verified · renews 2027-01')).toBeTruthy();
    expect(screen.getByText('Verified')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '+ Français' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Format is 9 digits + RT0001 (e.g. 123456789 RT0001).')).toBeTruthy();
    const put = calls.find(c => c.method === 'PUT')!.body as Record<string, unknown>;
    expect(put.autoAcceptQuoteCents).toBe(15000);
    expect(put.languages).toEqual(['en', 'pa', 'fr']);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Saved')).toBeTruthy();
  });

  it('is read-only for technicians', async () => {
    role = 'technician';
    mockFetch({ [`GET ${S}/business`]: () => business, 'GET /api/v1/merchants/m1/compliance': () => compliance });
    renderWithProviders(<BusinessTab />);
    expect(await screen.findByText('View only · Technician')).toBeTruthy();
    expect(screen.getByLabelText('Business name').matches(':disabled')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Save changes' })).toBeNull();
  });
});

describe('Settings › Team & roles', () => {
  it('lists the team with roles and 2FA, and the owner invites by email', async () => {
    const calls = mockFetch({
      [`GET ${S}/team`]: () => team,
      [`POST ${S}/team/invitations`]: () => ({ invitation: { id: 'i1', role: 'technician', email: 'sam@example.com', state: 'pending', createdAt: new Date().toISOString(), expiresAt: new Date().toISOString() }, inviteUrl: 'http://localhost:3100/invite/tok', sent: true }),
    });
    const user = userEvent.setup();
    renderWithProviders(<TeamTab />);
    expect((await screen.findAllByText('Jas Gill')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('SMS only').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Own jobs, messages, complete & photo').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Invite member' }));
    await user.click(screen.getByRole('button', { name: 'Send invite' }));
    expect(screen.getByText('Enter an email or a mobile number.')).toBeTruthy();
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'sam@');
    expect(screen.getByText('That doesn’t look like an email address.')).toBeTruthy();
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'example.com');
    await user.click(screen.getByRole('button', { name: 'Send invite' }));
    expect(await screen.findByText('http://localhost:3100/invite/tok')).toBeTruthy();
    expect(calls.find(c => c.method === 'POST')?.body).toEqual({ email: 'sam@example.com', role: 'technician' });
  });

  it('shows the server message when the contact was already invited', async () => {
    mockFetch({
      [`GET ${S}/team`]: () => team,
      [`POST ${S}/team/invitations`]: () => { throw { status: 422, body: { errors: [{ field: 'email', rule: 'unique', message: 'An invitation is already pending for this contact.' }] } }; },
    });
    const user = userEvent.setup();
    renderWithProviders(<TeamTab />);
    await user.click(await screen.findByRole('button', { name: 'Invite member' }));
    await user.type(screen.getByRole('textbox', { name: 'Email' }), 'sam@example.com');
    await user.click(screen.getByRole('button', { name: 'Send invite' }));
    expect(await screen.findByText('An invitation is already pending for this contact.')).toBeTruthy();
  });

  it('non-owners see the team without CRUD', async () => {
    role = 'bookkeeper';
    mockFetch({ [`GET ${S}/team`]: () => team });
    renderWithProviders(<TeamTab />);
    expect(await screen.findByText(/View only · Bookkeeper/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Invite member' })).toBeNull();
  });
});

describe('Settings › Notifications', () => {
  it('toggles a cell and saves only that cell', async () => {
    const matrix = { new_booking: { push: true, sms: true, email: false }, quote_request: { push: true, sms: true, email: true }, customer_message: { push: true, sms: false, email: false }, payout: { push: true, sms: false, email: true }, dispute: { push: true, sms: true, email: true }, low_stock: { push: true, sms: false, email: true }, quality: { push: false, sms: false, email: true } };
    const calls = mockFetch({
      [`GET ${S}/notifications`]: () => ({ matrix, quietFrom: '21:00:00', quietTo: '07:00:00' }),
      [`PUT ${S}/notifications`]: () => ({ matrix: { ...matrix, new_booking: { ...matrix.new_booking, email: true } }, quietFrom: '21:00:00', quietTo: '07:00:00' }),
    });
    const user = userEvent.setup();
    renderWithProviders(<NotificationsTab />);
    const cell = await screen.findByRole('button', { name: 'New booking / order by Email' });
    expect(cell.getAttribute('aria-pressed')).toBe('false');
    await user.click(cell);
    await waitFor(() => expect(cell.getAttribute('aria-pressed')).toBe('true'));
    expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ matrix: { new_booking: { email: true } } });
    expect(screen.getByText('Quiet hours 9 pm – 7 am except bookings for the next morning.')).toBeTruthy();
    expect(screen.queryByText('Low stock')).toBeNull();
  });

  it('shows the rows for each portal', () => {
    expect(eventsFor('kitchen')).not.toContain('quote_request');
    expect(eventsFor('seller')).toContain('low_stock');
    expect(eventsFor('both')).toHaveLength(7);
  });
});

describe('Settings › API & integrations', () => {
  it('issues a key after validation and shows the secret once', async () => {
    mockFetch({
      [`GET ${S}/api-keys`]: () => ({ items: [{ id: 'k1', name: 'Website embed', scopes: ['storefront:read', 'booking:write'], prefix: 'nl_live_Wb7q', rateLimit: 600, createdAt: '2026-02-02T17:00:00Z', lastUsedAt: new Date(Date.now() - 120_000).toISOString() }] }),
      [`GET ${S}/webhooks`]: () => ({ items: [{ id: 'w1', url: 'https://prairiewrench.ca/hooks/northline', events: ['booking.confirmed'], active: true, signature: 'HMAC-SHA256', createdAt: '2026-02-02T17:00:00Z', lastStatus: 200, lastDeliveryAt: new Date().toISOString() }] }),
      [`GET ${S}/developer-options`]: () => ({ scopes: ['orders:read', 'payouts:read'], events: ['booking.confirmed'] }),
      [`GET ${S}/business`]: () => business,
      [`POST ${S}/api-keys`]: () => ({ key: { id: 'k2', name: 'Zap', scopes: ['orders:read'], prefix: 'nl_live_Zzzz', rateLimit: 600, createdAt: new Date().toISOString(), lastUsedAt: null }, secret: 'nl_live_Zzzzsecret' }),
      'GET /api/v1/merchants/m1/availability/sync': () => ({ calendars: [{ provider: 'google', connected: true }], team: [] }),
      'GET /api/v1/merchants/m1/listings/integrations': () => ({ items: [] }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ApiTab />);
    expect((await screen.findAllByText('Website embed')).length).toBeGreaterThan(0);
    expect(screen.getByText(/booking.confirmed · HMAC-SHA256 · last delivery 200 OK/)).toBeTruthy();
    expect(screen.getByText(/data-store="prairie-wrench"/)).toBeTruthy();
    await user.click(screen.getAllByRole('button', { name: 'Issue key' })[0]!);
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Issue key' }));
    expect(await within(dialog).findByText('Name the key so you can tell it apart.')).toBeTruthy();
    expect(within(dialog).getByText('Pick at least one scope.')).toBeTruthy();
    await user.type(within(dialog).getByLabelText('Name'), 'Zap');
    await user.click(await within(dialog).findByRole('checkbox', { name: 'orders:read' }));
    await user.click(within(dialog).getByRole('button', { name: 'Issue key' }));
    expect(await screen.findByText('nl_live_Zzzzsecret')).toBeTruthy();
  });

  it('Integrations: a Google Calendar that needs a reconnect says so (S-32)', async () => {
    mockFetch({
      [`GET ${S}/api-keys`]: () => ({ items: [] }),
      [`GET ${S}/webhooks`]: () => ({ items: [] }),
      [`GET ${S}/developer-options`]: () => ({ scopes: [], events: [] }),
      [`GET ${S}/business`]: () => business,
      'GET /api/v1/merchants/m1/availability/sync': () => ({ calendars: [{ provider: 'google', connected: true, state: 'reconnect' }], team: [] }),
      'GET /api/v1/merchants/m1/listings/integrations': () => ({ items: [] }),
    });
    renderWithProviders(<ApiTab />);
    const row = (await screen.findByText('Google Calendar')).closest('li')!;
    expect(await within(row).findByRole('button', { name: 'Reconnect' })).toBeTruthy();
    expect(within(row).queryByText('Connected')).toBeNull();
  });

  it('formats "last used"', () => {
    const t = ((k: string, v?: Record<string, unknown>) => `${k}:${v?.n ?? ''}`) as never;
    expect(ago(null, t)).toBe('never:');
    expect(ago(new Date(Date.now() - 2 * 60_000).toISOString(), t)).toBe('minAgo:2');
    expect(ago(new Date(Date.now() - 60 * 60_000).toISOString(), t)).toBe('hAgo:1');
  });
});

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)';
const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X)';
const AUTH = 'http://localhost:9000/api/auth';
const session = (id: string, device: string, current: boolean) => ({ id, device, city: 'Calgary', ipApprox: '203.0.113.x', method: 'totp', signedInAt: new Date().toISOString(), lastSeenAt: new Date().toISOString(), apps: ['Northline Studio'], current });
const security = (over: Record<string, unknown> = {}) => ({
  email: 'ravi@prairiewrench.ca', mfaPrimary: 'passkey', passkeys: [{ id: 'p1', label: 'iPhone 16', createdAt: '2026-01-05T17:00:00Z' }, { id: 'p2', label: 'YubiKey', createdAt: '2026-02-05T17:00:00Z' }],
  authenticator: false, backupCodesRemaining: 8, signIns: [], sessions: [session('me', MAC, true), session('phone', IPHONE, false), session('tablet', IPHONE, false)], ...over,
});
const stepUpRequired = { status: 403, body: { code: 'step_up_required', detail: "Confirm it's you to make this change." } };

describe('Settings › Security', () => {
  it('asks the person to confirm it’s them when the auth server has no second-factor session', async () => {
    mockFetch({ 'GET http://localhost:9000/api/auth/security': () => { throw { status: 401, body: { code: 'unauthenticated' } }; } });
    renderWithProviders(<SecurityTab />);
    expect(await screen.findByRole('heading', { name: 'Confirm it’s you' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Use passkey' })).toBeTruthy();
  });

  it('lists factors, backup codes left and sessions; new codes are shown once', async () => {
    mockFetch({
      'GET http://localhost:9000/api/auth/security': () => ({
        email: 'ravi@prairiewrench.ca', mfaPrimary: 'passkey', passkeys: [{ id: 'p1', label: 'iPhone 16', createdAt: '2026-01-05T17:00:00Z' }],
        authenticator: true, backupCodesRemaining: 8, signIns: [{ id: 's1', device: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)', method: 'passkey', at: new Date().toISOString() }, { id: 's2', device: 'Mozilla/5.0 (Macintosh; Intel Mac OS X)', method: 'totp', at: new Date().toISOString() }],
        sessions: [session('s1', IPHONE, false), session('s2', MAC, true)],
      }),
      'POST http://localhost:9000/api/auth/backup-codes': () => ({ codes: ['abcde-fghij', 'klmno-pqrst'] }),
      [`GET ${S}/audit-log`]: () => ({ items: [] }),
    });
    const user = userEvent.setup();
    renderWithProviders(<SecurityTab />);
    expect(await screen.findByText('Passkey · iPhone 16')).toBeTruthy();
    expect(screen.getByText('Primary')).toBeTruthy();
    expect(screen.getByText('Backup codes · 8 left')).toBeTruthy();
    expect(screen.getByText('Login alerts to ravi@prairiewrench.ca')).toBeTruthy();
    expect(screen.getByText('Active sessions · 2 (iPhone, Mac)')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'New codes' }));
    expect(await screen.findByText('abcde-fghij')).toBeTruthy();
  });

  it('passes the BFF session id so this browser counts as current', async () => {
    const calls = mockFetch({ [`GET ${AUTH}/security`]: () => security() });
    renderWithProviders(<SecurityTab />);
    await screen.findByText('Passkey · iPhone 16');
    expect(calls[0]!.url).toBe(`${AUTH}/security?current=sess-bff`);
  });

  it('removes a passkey after confirming, and never offers to remove the last factor', async () => {
    let passkeys = security().passkeys;
    const calls = mockFetch({
      [`GET ${AUTH}/security`]: () => security({ passkeys }),
      [`DELETE ${AUTH}/security/passkeys/p2`]: () => { passkeys = passkeys.filter(p => p.id !== 'p2'); return { passkeys }; },
    });
    const user = userEvent.setup();
    renderWithProviders(<SecurityTab />);
    await user.click(await screen.findByRole('button', { name: 'Remove passkey YubiKey' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Remove this passkey?' });
    expect(within(dialog).getByText(/You won’t be able to sign in with “YubiKey” any more/)).toBeTruthy();
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('Passkey removed.')).toBeTruthy();
    expect(calls.some(c => c.method === 'DELETE' && c.url === `${AUTH}/security/passkeys/p2`)).toBe(true);
    // One passkey and no authenticator app left: it can't be removed.
    await waitFor(() => expect((screen.getByRole('button', { name: 'Remove passkey iPhone 16' }) as HTMLButtonElement).disabled).toBe(true));
    expect(screen.getAllByText('Add another passkey or an authenticator app before you remove this one.').length).toBeGreaterThan(0);
  });

  it('signs a session out after a step-up with the authenticator code, then retries', async () => {
    let revokeCalls = 0;
    let sessions = security().sessions;
    const calls = mockFetch({
      [`GET ${AUTH}/security`]: () => security({ sessions }),
      [`POST ${AUTH}/security/sessions/phone/revoke`]: () => {
        revokeCalls += 1;
        if (revokeCalls === 1) throw stepUpRequired;
        sessions = sessions.filter(s => s.id !== 'phone');
        return { sessions };
      },
      [`POST ${AUTH}/step-up/totp`]: () => ({ proof: 'jwt', expiresAt: new Date().toISOString() }),
    });
    const user = userEvent.setup();
    renderWithProviders(<SecurityTab />);
    await user.click(await screen.findByRole('button', { name: 'Review' }));
    const drawer = screen.getByRole('dialog', { name: 'Active sessions' });
    expect(within(drawer).getByText('This device')).toBeTruthy();
    expect(within(drawer).getAllByText(/Calgary · 203\.0\.113\.x/).length).toBe(3);
    await user.click(within(drawer).getAllByRole('button', { name: 'Sign out iPhone' })[0]!);
    await user.click(within(screen.getByRole('alertdialog', { name: 'Sign out this session?' })).getByRole('button', { name: 'Sign out' }));

    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it’s you' });
    await user.type(within(stepUp).getByLabelText('Authenticator code'), '123456');
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText('Session signed out.')).toBeTruthy();
    expect(revokeCalls).toBe(2);
    expect(calls.find(c => c.url === `${AUTH}/step-up/totp`)?.body).toEqual({ code: '123456' });
    expect(calls.filter(c => c.url.endsWith('/revoke')).map(c => c.body)).toEqual([{ current: 'sess-bff' }, { current: 'sess-bff' }]);
  });

  it('signs out all other sessions and shows how many', async () => {
    const calls = mockFetch({
      [`GET ${AUTH}/security`]: () => security(),
      [`POST ${AUTH}/security/sessions/revoke-others`]: () => ({ revoked: 2 }),
    });
    const user = userEvent.setup();
    renderWithProviders(<SecurityTab />);
    await user.click(await screen.findByRole('button', { name: 'Review' }));
    await user.click(screen.getByRole('button', { name: 'Sign out all other sessions' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Sign out all other sessions?' });
    await user.click(within(dialog).getByRole('button', { name: 'Sign out all other sessions' }));
    expect(await screen.findByText('2 sessions signed out.')).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/revoke-others'))?.body).toEqual({ current: 'sess-bff' });
  });

  it('explains a refused change (rate limited) and keeps the dialog open', async () => {
    mockFetch({
      [`GET ${AUTH}/security`]: () => security(),
      [`POST ${AUTH}/security/sessions/phone/revoke`]: () => { throw { status: 429, body: { code: 'rate_limited' } }; },
    });
    const user = userEvent.setup();
    renderWithProviders(<SecurityTab />);
    await user.click(await screen.findByRole('button', { name: 'Review' }));
    await user.click(screen.getAllByRole('button', { name: 'Sign out iPhone' })[0]!);
    const dialog = screen.getByRole('alertdialog', { name: 'Sign out this session?' });
    await user.click(within(dialog).getByRole('button', { name: 'Sign out' }));
    expect(await within(dialog).findByText('Too many security changes in a short time. Try again later.')).toBeTruthy();
  });

  it('speaks French', async () => {
    mockFetch({ [`GET ${AUTH}/security`]: () => security() });
    const user = userEvent.setup();
    renderWithProviders(<SecurityTab />, 'fr');
    await user.click(await screen.findByRole('button', { name: 'Retirer la clé d’accès YubiKey' }));
    expect(screen.getByRole('alertdialog', { name: 'Retirer cette clé d’accès?' })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Annuler' }));
    await user.click(screen.getByRole('button', { name: 'Examiner' }));
    expect(screen.getByRole('button', { name: 'Déconnecter toutes les autres sessions' })).toBeTruthy();
    expect(screen.getByText('Cet appareil')).toBeTruthy();
  });

  it('names devices from the user agent', () => {
    const t = ((k: string) => k) as never;
    expect(deviceName('Mozilla/5.0 (Linux; Android 14)', t)).toBe('device_android');
    expect(deviceName(null, t)).toBe('unknownDevice');
  });
});

describe('validation', () => {
  const t = ((k: string) => k) as never;
  it('business rules', () => {
    const ok = { displayName: 'Prairie Wrench', legalName: 'PW Ltd.', gstNumber: '123456789 RT0001', serviceArea: '', cancellationPolicy: '12h' as const, autoAccept: '$150', languages: ['en'] };
    expect(Object.values(businessErrors(ok, true, t)).filter(Boolean)).toHaveLength(0);
    expect(businessErrors({ ...ok, gstNumber: '12 RT1' }, true, t).gstNumber).toBe('err_gst_format');
    expect(businessErrors({ ...ok, gstNumber: '' }, false, t).gstNumber).toBeUndefined();
    expect(businessErrors({ ...ok, autoAccept: '-5' }, true, t).autoAcceptQuoteCents).toBe('err_amount');
    expect(businessErrors({ ...ok, displayName: 'x'.repeat(81) }, true, t).displayName).toBe('err_displayName_long');
    expect(dollarsToCents('$150')).toBe(15000);
    expect(dollarsToCents('')).toBeNull();
  });
  it('invite and webhook rules', () => {
    expect(inviteErrors({ by: 'phone', email: '', phone: '555', role: 'cook' }, t).phone).toBe('err_phone');
    expect(inviteErrors({ by: 'phone', email: '', phone: '+1 403 555 0148', role: '' }, t)).toEqual({ phone: undefined, role: 'err_role' });
    expect(webhookErrors({ url: 'http://example.com', events: [] }, t)).toEqual({ url: 'err_https', events: 'err_events' });
    expect(webhookErrors({ url: 'https://example.com/h', events: ['x'] }, t).url).toBeUndefined();
  });
});
