import { beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AccountScreen } from './AccountScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550148', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const PROFILE = { id: 'C1', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550148', locale: 'en-CA', memberSince: '2026-03-02',
  pronouns: 'she', birthday: null, reliability: 4.9, erasureRequestedAt: null };
const CARDS = { provider: 'fake', publishableKey: null, items: [
  { id: 'pm_1', brand: 'visa', last4: '4471', expMonth: 9, expYear: 2028, isDefault: true, addedAt: '2026-05-02T00:00:00Z' },
  { id: 'pm_2', brand: 'mastercard', last4: '0912', expMonth: 2, expYear: 2027, isDefault: false, addedAt: '2026-06-02T00:00:00Z' },
] };
const ADDRESSES = { items: [
  { id: 'A1', label: null, street: '1204 17 Ave SW', unit: 'Apt 804', city: 'Calgary', province: 'AB', postal: 'T2T 0B8', note: 'buzz 0804 · leave at door', isDefault: true },
  { id: 'A2', label: 'Mum', street: '44 Varsity Estates Cir NW', unit: null, city: 'Calgary', province: 'AB', postal: 'T3B 3B8', note: null, isDefault: false },
] };
const HOUSEHOLD = { id: 'H1', plan: 'none', plusSince: null, renewsAt: null, members: [
  { userId: 'C1', name: 'Amara Osei', role: 'owner', you: true }, { userId: 'K1', name: 'Kofi Osei', role: 'member', you: false },
] };
const MATRIX = {
  booking_reminders: { push: true, sms: true, email: false }, order_updates: { push: true, sms: false, email: true },
  sign_off: { push: true, sms: true, email: true }, quotes_messages: { push: true, sms: false, email: false },
  refunds_cases: { push: true, sms: false, email: true }, offers: { push: true, sms: false, email: false },
  security: { push: true, sms: true, email: true },
};
const NOTIF = { events: Object.keys(MATRIX), channels: ['push', 'sms', 'email'], matrix: MATRIX, quietOn: true, quietFrom: '22:00:00', quietTo: '07:00:00', language: 'app', marketing: 'weekly' };
const CONSENTS = {
  categories: [
    { category: 'marketing_email', channel: 'email', granted: true, since: '2026-09-20T15:00:00Z', source: 'web_settings', wordingVersion: 'account.email.2026-10',
      wording: 'Yes, Northline Marketplace Inc. may email me Northline’s offers, rewards and news.' },
    { category: 'marketing_sms', channel: 'sms', granted: false, since: null, source: null, wordingVersion: 'account.sms.2026-10', wording: 'Yes, Northline Marketplace Inc. may text me.' },
    { category: 'marketing_push', channel: 'push', granted: true, since: '2026-09-20T15:00:00Z', source: 'app_settings', wordingVersion: 'account.push.2026-10', wording: 'Yes, as app notifications.' },
  ],
  history: [
    { id: 'R2', category: 'marketing_push', action: 'granted', at: '2026-09-20T15:00:00Z', source: 'app_settings', wordingVersion: 'account.push.2026-10', language: 'en' },
    { id: 'R1', category: 'marketing_email', action: 'withdrawn', at: '2026-09-01T15:00:00Z', source: 'list_unsubscribe', wordingVersion: null, language: 'en' },
  ],
  requester: 'Northline Marketplace Inc. · 1 Test Street, Testville · support@northline.ca',
};
const PREFS = { language: 'en', province: 'AB', units: 'metric', timeFormat: '12h', dietary: ['halal'], allergies: null, accessibility: ['step_free'], accessNotes: null, display: [] };

type Reply = { status?: number; body?: unknown } | undefined;
let calls: Call[];
let cards: typeof CARDS;
let security: Reply;
let profilePatch: Reply;
const server = (c: Call): Reply => {
  const u = c.url;
  if (u === '/bff/session') return { body: { user: AMARA, guestId: 'g', sid: 'S1' } };
  if (u === '/api/v1/me/account-summary') return { body: { signIn: 'passkey' } };
  if (u === '/api/v1/me/wallet') return { body: { points: { balance: 0, valueCents: 0, weekly: [0, 0, 0, 0, 0, 0, 0, 0] }, plus: null } };
  if (u === '/api/v1/me/profile' && c.method === 'GET') return { body: PROFILE };
  if (u === '/api/v1/me/profile' && c.method === 'PATCH') return profilePatch ?? { body: { ...PROFILE, ...(c.body as object) } };
  if (u === '/api/v1/me/privacy-requests') return { body: { items: [] } };
  if (u === '/api/v1/me/payment-methods' && c.method === 'GET') return { body: cards };
  if (u === '/api/v1/me/payment-methods/setup-intents') return { status: 201, body: { setupIntentId: 'seti_1', clientSecret: 'seti_1_secret', provider: 'fake', publishableKey: null } };
  if (u === '/api/v1/me/payment-methods' && c.method === 'POST') {
    cards = { ...cards, items: [{ id: 'pm_3', brand: 'visa', last4: '4242', expMonth: 12, expYear: 2029, isDefault: false, addedAt: '2026-10-01T00:00:00Z' }, ...cards.items] };
    return { body: cards };
  }
  if (u === '/api/v1/me/payment-methods/pm_2' && c.method === 'DELETE') { cards = { ...cards, items: cards.items.filter(x => x.id !== 'pm_2') }; return { body: cards }; }
  if (u === '/api/v1/me/payment-methods/pm_2/default') { cards = { ...cards, items: cards.items.map(x => ({ ...x, isDefault: x.id === 'pm_2' })) }; return { body: cards }; }
  if (u === '/api/v1/me/billing-history') return { body: { items: [
    { id: 'E1', at: '2026-09-06T18:00:00Z', what: 'Grocery run', ref: 'NL-48190', amountCents: 3800, cardBrand: 'visa', cardLast4: '4471', status: 'held' },
  ] } };
  if (u === '/api/v1/me/addresses' && c.method === 'GET') return { body: ADDRESSES };
  if (u === '/api/v1/me/addresses' && c.method === 'POST') return { status: 201, body: { id: 'A3', ...(c.body as object), isDefault: false } };
  if (u === '/api/v1/me/household') return { body: HOUSEHOLD };
  if (u === '/api/v1/me/plus' && c.method === 'POST') return { body: { ...HOUSEHOLD, plan: 'monthly', plusSince: '2026-10-01T00:00:00Z', renewsAt: '2026-10-31T00:00:00Z' } };
  if (u === '/api/v1/me/notifications' && c.method === 'GET') return { body: NOTIF };
  if (u === '/api/v1/me/consents') return { body: CONSENTS };
  if (u === '/api/v1/me/notifications' && c.method === 'PUT') return { body: { ...NOTIF, ...(c.body as object), matrix: MATRIX } };
  if (u === '/api/v1/me/preferences' && c.method === 'GET') return { body: PREFS };
  if (u === '/api/v1/me/preferences' && c.method === 'PATCH') return { body: { ...PREFS, ...(c.body as object) } };
  if (u.startsWith('http://auth.test/api/auth/security')) return security;
  return undefined;
};
const open = (tab: string, locale: 'en' | 'fr' = 'en') => {
  calls = mockFetch(server);
  return renderApp(`/account?tab=${tab}`, { locale, routes: { account: () => <AccountScreen /> } });
};
const body = (method: string, url: string) => calls.filter(c => c.method === method && c.url === url).map(c => c.body);

beforeEach(() => {
  cards = structuredClone(CARDS);
  profilePatch = undefined;
  security = { body: {
    email: 'amara@example.ca', mfaPrimary: 'passkey', passkeys: [{ id: 'K1', label: 'iPhone 16 (Face ID)' }], authenticator: false, backupCodesRemaining: 0, signIns: [],
    sessions: [
      { id: 'S1', device: 'Safari · MacBook', city: 'Calgary', signedInAt: '2026-10-01T10:00:00Z', lastSeenAt: '2026-10-01T10:00:00Z', apps: [], current: true },
      { id: 'S2', device: 'iPhone 16', city: 'Calgary', signedInAt: '2026-09-30T10:00:00Z', lastSeenAt: new Date(Date.now() - 2 * 3_600_000).toISOString(), apps: [], current: false },
    ],
  } };
});

describe('Payment methods (design 06 payments)', () => {
  it('lists the cards, makes one default and removes one after confirming', async () => {
    open('payments');
    expect(await screen.findByRole('heading', { level: 1, name: 'Payment methods' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText(/Stored with Stripe as tokens — Northline never sees card numbers\./)).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Payment methods' });
    expect(within(list).getByText('Visa ··4471')).toBeInTheDocument();
    expect(within(list).getByText('Default')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Make Mastercard ··0912 the default' }));
    await waitFor(() => expect(calls.some(c => c.url === '/api/v1/me/payment-methods/pm_2/default')).toBe(true));
    await userEvent.click(screen.getByRole('button', { name: 'Remove Mastercard ··0912' }));
    const dialog = await screen.findByRole('alertdialog');
    await expectNoAxeViolations(document.body); // S-109
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(screen.queryByText('Mastercard ··0912')).not.toBeInTheDocument());
  });

  it('adds a card with a SetupIntent (test cards without Stripe)', async () => {
    open('payments');
    await userEvent.click(await screen.findByRole('button', { name: 'Add payment method' }));
    expect(await screen.findByText('Test cards · nothing is charged')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Save card' }));
    expect(await screen.findByText('Visa ··4242')).toBeInTheDocument();
    expect(body('POST', '/api/v1/me/payment-methods')).toEqual([{ setupIntentId: 'seti_1' }]);
  });

  it('billing history', async () => {
    open('payments');
    expect(await screen.findByText('Grocery run · NL-48190 · in escrow')).toBeInTheDocument();
    expect(screen.getByText('$38.00')).toBeInTheDocument();
  });

  it('no cards yet', async () => {
    cards = { ...cards, items: [] };
    open('payments');
    expect(await screen.findByText('No saved cards yet. Add one to pay faster at checkout.')).toBeInTheDocument();
  });

  it('the wallet lists the cards too', async () => {
    open('wallet');
    expect(await screen.findByText('Visa ··4471')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add card' })).toBeInTheDocument();
  });
});

describe('Profile (design 06 profile)', () => {
  it('shows the profile and saves changes', async () => {
    open('profile');
    expect(await screen.findByLabelText('First name')).toHaveValue('Amara');
    expect(screen.getByLabelText('Mobile (verified)')).toHaveAttribute('readonly');
    expect(screen.getByRole('heading', { name: 'Reliability score · 4.9' })).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Last name'));
    await userEvent.type(screen.getByLabelText('Last name'), 'Osei-Mensah');
    await userEvent.type(screen.getByLabelText('Birthday (optional · birthday points)'), '3/14');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(body('PATCH', '/api/v1/me/profile')).toEqual([{ firstName: 'Amara', lastName: 'Osei-Mensah', email: 'amara@example.ca', pronouns: 'she', birthday: '03-14' }]);
  });

  it('validation messages, and the server’s', async () => {
    open('profile');
    await userEvent.clear(await screen.findByLabelText('First name'));
    await userEvent.clear(screen.getByLabelText('Email (receipts)'));
    await userEvent.type(screen.getByLabelText('Email (receipts)'), 'nope');
    await userEvent.type(screen.getByLabelText('Birthday (optional · birthday points)'), '14/45');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('First name is required.')).toBeInTheDocument();
    expect(screen.getByText('That doesn’t look like an email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter a birthday like 03/14 (month / day).')).toBeInTheDocument();
    expect(body('PATCH', '/api/v1/me/profile')).toEqual([]);
    await userEvent.type(screen.getByLabelText('First name'), 'Amara');
    await userEvent.clear(screen.getByLabelText('Email (receipts)'));
    await userEvent.type(screen.getByLabelText('Email (receipts)'), 'taken@example.ca');
    await userEvent.clear(screen.getByLabelText('Birthday (optional · birthday points)'));
    profilePatch = { status: 422, body: { errors: [{ field: 'email', rule: 'unique', message: 'That email is already used by another account.' }] } };
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('That email is already used by another account.')).toBeInTheDocument();
  });
});

describe('Addresses & household (design 06 addresses)', () => {
  it('lists the book and the household', async () => {
    open('addresses');
    expect(await screen.findByText('1204 17 Ave SW, Apt 804 · buzz 0804 · leave at door')).toBeInTheDocument();
    expect(screen.getByText('Mum · 44 Varsity Estates Cir NW')).toBeInTheDocument();
    expect(await screen.findByText('Household members · Kofi (own login)')).toBeInTheDocument();
    expect(screen.getAllByText('Default')).toHaveLength(1);
  });

  it('adds an address, with checkout’s messages', async () => {
    open('addresses');
    await userEvent.click(await screen.findByRole('button', { name: 'Add address' }));
    const dialog = await screen.findByRole('dialog', { name: 'New address' });
    await userEvent.type(within(dialog).getByLabelText('Postal code'), '12345');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save address' }));
    expect(await within(dialog).findByText('Enter the street address.')).toBeInTheDocument();
    expect(within(dialog).getByText('Enter a Canadian postal code, like T2P 1B5.')).toBeInTheDocument();
    expect(within(dialog).getByText('Choose a Canadian province or territory.')).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText('Street address'), '1 Main St');
    await userEvent.type(within(dialog).getByLabelText('City'), 'Calgary');
    await userEvent.selectOptions(within(dialog).getByLabelText('Province'), 'AB');
    await userEvent.clear(within(dialog).getByLabelText('Postal code'));
    await userEvent.type(within(dialog).getByLabelText('Postal code'), 'T2P 1B5');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save address' }));
    await waitFor(() => expect(body('POST', '/api/v1/me/addresses')).toEqual([{ street: '1 Main St', city: 'Calgary', province: 'AB', postal: 'T2P 1B5' }]));
  });

  it('in French', async () => {
    open('addresses', 'fr');
    expect(await screen.findByRole('heading', { level: 1, name: 'Adresses et foyer' })).toBeInTheDocument();
    expect(await screen.findByText('Membres du foyer · Kofi (son propre compte)')).toBeInTheDocument();
  });
});

describe('Security & sign-in (design 06 security)', () => {
  it('passkeys, backup, sessions and the account’s actions', async () => {
    open('security');
    expect(await screen.findByText('Passkey · iPhone 16 (Face ID)')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Well protected. Passkey + SMS backup · 2 trusted devices.');
    expect(screen.getByText('SMS backup · ··0148')).toBeInTheDocument();
    expect(screen.getByText('Safari · MacBook · Calgary · this session')).toBeInTheDocument();
    expect(screen.getByText('iPhone 16 · Calgary · 2 h ago')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Download my data' })).toHaveAttribute('href', '/account?tab=profile#your-data');
    expect(screen.getByRole('button', { name: 'Sign out everywhere' })).toBeInTheDocument();
  });

  it('a session sign-out that needs a fresh second factor asks to confirm', async () => {
    calls = mockFetch(c => (c.url.endsWith('/sessions/S2/revoke') ? { status: 403, body: { code: 'step_up_required' } } : server(c)));
    renderApp('/account?tab=security', { routes: { account: () => <AccountScreen /> } });
    const row = (await screen.findByText('iPhone 16 · Calgary · 2 h ago')).closest('li')!;
    await userEvent.click(within(row).getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('dialog', { name: 'Confirm it’s you' })).toBeInTheDocument();
  });

  it('without a second-factor session: confirm it’s you first', async () => {
    security = { status: 401, body: {} };
    open('security');
    expect(await screen.findByText('Confirm it’s you')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Use passkey' })).toBeInTheDocument();
  });
});

describe('Notifications (design 06 notifications)', () => {
  it('the matrix, security locked, quiet hours and saving', async () => {
    open('notifications');
    const offersPush = await screen.findByRole('switch', { name: 'Push for Offers & rewards: on' });
    expect(screen.getByRole('switch', { name: 'SMS for Security alerts: on' })).toBeDisabled();
    await userEvent.click(offersPush);
    expect(screen.getByRole('switch', { name: 'Push for Offers & rewards: off' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Quiet from' }), '23:00');
    await userEvent.selectOptions(screen.getByLabelText('Marketing emails'), 'none');
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByText('Saved · applies to all your devices')).toBeInTheDocument();
    expect(body('PUT', '/api/v1/me/notifications')).toEqual([{
      matrix: { offers: { push: false } }, quietOn: true, quietFrom: '23:00', quietTo: '07:00', language: 'app', marketing: 'none',
      consentSource: 'web_settings', consentWordings: { email: 'account.email.2026-10', sms: 'account.sms.2026-10', push: 'account.push.2026-10' },
    }]);
  });

  it('S-108: marketing consents — the wording, who asks, the history; saving other settings sends no consent change', async () => {
    open('notifications');
    expect(await screen.findByRole('heading', { name: 'Marketing messages' })).toBeInTheDocument();
    expect(screen.getByText('Yes, Northline Marketplace Inc. may email me Northline’s offers, rewards and news.')).toBeInTheDocument();
    expect(screen.getByText('Asked by Northline Marketplace Inc. · 1 Test Street, Testville · support@northline.ca.')).toBeInTheDocument();
    const history = within(screen.getByRole('heading', { name: 'Your consent history' }).parentElement!).getAllByRole('row');
    expect(history[1]).toHaveTextContent(/Promotional push.*Given.*Account settings \(app\)/);
    expect(history[2]).toHaveTextContent(/Marketing email.*Withdrawn.*Your mailbox’s unsubscribe button/);
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Quiet from' }), '21:00');
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await screen.findByText('Saved · applies to all your devices');
    expect(body('PUT', '/api/v1/me/notifications')).toEqual([{ matrix: {}, quietOn: true, quietFrom: '21:00', quietTo: '07:00', language: 'app' }]);
  });

  it('S-108: the offers email cell and Marketing emails are one consent', async () => {
    open('notifications');
    await userEvent.selectOptions(await screen.findByLabelText('Marketing emails'), 'rewards');
    expect(screen.getByRole('switch', { name: 'Email for Offers & rewards: on' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('switch', { name: 'Email for Offers & rewards: on' }));
    expect(screen.getByLabelText('Marketing emails')).toHaveValue('none');
  });

  it('S-108: in French', async () => {
    open('notifications', 'fr');
    expect(await screen.findByRole('heading', { name: 'Messages publicitaires' })).toBeInTheDocument();
    expect(screen.getByText('Historique de vos consentements')).toBeInTheDocument();
    expect(screen.getAllByText('Bouton de désabonnement de votre messagerie')).toHaveLength(1);
  });
});

describe('Language & region, Dietary & accessibility, Plus', () => {
  it('switching to Français saves it and changes the page', async () => {
    open('language');
    await userEvent.click(await screen.findByRole('button', { name: /requis au Québec/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Langue / Language et région' })).toBeInTheDocument();
    expect(body('PATCH', '/api/v1/me/preferences')).toEqual([{ language: 'fr', province: 'AB', units: 'metric', timeFormat: '12h' }]);
  });

  it('a chosen province goes back to Follow my location (province "")', async () => {
    open('language');
    const province = await screen.findByRole('combobox', { name: 'Province' });
    await waitFor(() => expect(province).toHaveValue(PREFS.province));
    expect(screen.queryByText(/follow your delivery address/)).not.toBeInTheDocument();
    await userEvent.selectOptions(province, 'Follow my location');
    expect(screen.getByText('Taxes, the catalogue and your notification times follow your delivery address, or where you are.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body('PATCH', '/api/v1/me/preferences')).toEqual([{ language: 'en', province: '', units: 'metric', timeFormat: '12h' }]));
  });

  it('says what following the location means, in French', async () => {
    open('language', 'fr');
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'Province' }), 'Selon ma position');
    expect(screen.getByText('Les taxes, le catalogue et l’heure de vos notifications suivent votre adresse de livraison ou votre position.')).toBeInTheDocument();
  });

  it('dietary chips and notes', async () => {
    open('dietary');
    expect(await screen.findByRole('button', { name: 'Halal' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Vegan' }));
    await userEvent.type(screen.getByLabelText('Notes for providers visiting your home'), 'Service dog on site');
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await waitFor(() => expect(body('PATCH', '/api/v1/me/preferences')).toEqual([{ dietary: ['halal', 'vegan'], allergies: '', accessibility: ['step_free'], accessNotes: 'Service dog on site', display: [] }]));
  });

  it('Plus: start the free trial', async () => {
    open('plus');
    expect(await screen.findByText('Free pooled delivery on every run (saves ~$18/month for a weekly shopper)')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /^Annual · \$69/ }));
    expect(screen.getByText(/^Then \$69\/year · cancel any time/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Start 30-day free trial' }));
    expect(await screen.findByRole('button', { name: 'Cancel Plus' })).toBeInTheDocument();
    expect(body('POST', '/api/v1/me/plus')).toEqual([{ plan: 'annual' }]);
  });
});
