import { FIXTURE_ACCOUNT, FIXTURE_TOTP } from './auth';
import type { FixtureArea, FixtureContext, FixtureRequest } from './context';
import { PROVINCES } from './geo';
import { FIXTURE_PROOF, type ShopFixtureState } from './shop';

/**
 * Journey D's api (S-101) on the fixture backend: the account summary, Orders & bookings, a quote to accept, the
 * profile, address book and household, wallet & Plus, saving a card (the api's stand-in or Stripe-shaped), the
 * notification matrix, preferences, favourites, refund cases, the data export and erasure request — and northline-auth's
 * security API and step-up in the auth session. Made-up people, businesses and places; the address book and the
 * cards are the shop area's (checkout and payment read the same ones).
 *
 * This area is first in the fixture server's list: northline-auth's area answers 404 for any `/api/auth/*` it
 * doesn't know, and the security API lives there.
 */
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

type Matrix = Record<string, Record<string, boolean>>;
export interface AccountFixtureState {
  shop: ShopFixtureState;
  /** Orders & bookings (the api's ActivityItem shape). */
  activity: Array<Record<string, unknown> & { id: string; kind: string; status: string; active: boolean }>;
  quotes: Map<string, { state: string; validUntil: number; booking?: string }>;
  /** What `POST /me/quotes/{id}/accept` asks: `required` wants `X-Step-Up: fixture-proof`. */
  stepUp: 'none' | 'required';
  profile: {
    firstName: string; lastName: string; email: string; phone: string; pronouns: string | null; birthday: string | null;
    memberSince: string; reliability: number | null; erasureRequestedAt: string | null;
  };
  labels: Map<string, string>;
  points: { balance: number; weekly: number[] };
  plus: { plan: 'monthly' | 'annual'; since: string } | null;
  notifications: { matrix: Matrix; quietOn: boolean; quietFrom: string; quietTo: string; language: string; marketing: string };
  prefs: { language: string; province: string | null; units: string; timeFormat: string; dietary: string[]; allergies: string | null; accessibility: string[]; accessNotes: string | null; display: string[] };
  favourites: Array<{ merchantId: string; name: string; type: string; tier: string; slug: string; visits: number; lastAt: string | null; addedAt: string }>;
  cases: Array<{ id: string; number: string; state: string; open: boolean; what: string; amountCents: number; taxCents: number; merchantName: string; openedAt: number; respondBy: number | null; notes: Array<{ at: number; by: string; body: string }> }>;
  /** northline-auth: whether the auth session has a recent second factor (else 401 → confirm it's you). */
  security: { confirmed: boolean; authenticator: boolean; passkeys: Array<{ id: string; label: string }>; sessions: Array<{ id: string; device: string; city: string; current: boolean; lastSeenAt: number }> };
  setups: number;
  idempotent: Map<string, unknown>;
}

/** The account with a week of activity (`seedAccount`); tests empty what they need empty. */
export function newAccountState(shop: ShopFixtureState, now = Date.now()): AccountFixtureState {
  const state: AccountFixtureState = {
    shop,
    activity: [],
    quotes: new Map(),
    stepUp: 'none',
    profile: {
      firstName: FIXTURE_ACCOUNT.firstName, lastName: FIXTURE_ACCOUNT.lastName, email: FIXTURE_ACCOUNT.email, phone: FIXTURE_ACCOUNT.phone,
      pronouns: null, birthday: null, memberSince: '2026-05-12', reliability: 4.9, erasureRequestedAt: null,
    },
    labels: new Map(),
    points: { balance: 1240, weekly: [0, 120, 80, 0, 260, 140, 320, 180] },
    plus: null,
    notifications: {
      matrix: {
        booking_reminders: { push: true, sms: true, email: false },
        order_updates: { push: true, sms: false, email: true },
        sign_off: { push: true, sms: true, email: true },
        quotes_messages: { push: true, sms: false, email: false },
        refunds_cases: { push: true, sms: false, email: true },
        offers: { push: true, sms: false, email: false },
        security: { push: true, sms: true, email: true },
      },
      quietOn: true, quietFrom: '22:00:00', quietTo: '07:00:00', language: 'app', marketing: 'weekly',
    },
    prefs: { language: 'en', province: null, units: 'metric', timeFormat: '12h', dietary: [], allergies: null, accessibility: [], accessNotes: null, display: [] },
    favourites: [{ merchantId: 'm-cleaners', name: 'Tidy Nook Cleaners', type: 'provider', tier: 'master', slug: 'tidy-nook-cleaners', visits: 2, lastAt: new Date(now - 4 * DAY).toISOString(), addedAt: new Date(now - 30 * DAY).toISOString() }],
    cases: [],
    security: {
      confirmed: false,
      authenticator: true,
      passkeys: [],
      sessions: [
        { id: 'ses-phone', device: 'Phone · Northline app', city: 'Sampleville', current: true, lastSeenAt: now },
        { id: 'ses-laptop', device: 'Safari · Laptop', city: 'Sampleville', current: false, lastSeenAt: now - 2 * DAY },
      ],
    },
    setups: 0,
    idempotent: new Map(),
  };
  seedAccount(state, now);
  return state;
}

/** Tests and demos: a week of orders, bookings, a quote and an open refund case (`seedAccount`). */
export function seedAccount(state: AccountFixtureState, now: number) {
  const iso = (t: number) => new Date(t).toISOString();
  state.activity = [
    { id: 'ord-1001', kind: 'order', ref: 'NL-1001', title: '', with: ['Maple Lane Bakery', 'Leafy Lane Greens'], delivery: 'pooled', shops: 2, items: 3, when: iso(now + 2 * HOUR), whenEnd: iso(now + 5 * HOUR), amountCents: 2349, status: 'on_the_way', tone: 'accent', active: true, caseRef: null, action: 'track', href: '/orders/ord-1001' },
    { id: 'bk-7712', kind: 'booking', ref: 'BK-7712', title: 'Deep clean', with: ['Tidy Nook Cleaners · Robin'], delivery: null, shops: 1, items: 1, when: iso(now + 2 * DAY), whenEnd: null, amountCents: 22050, status: 'escrow', tone: 'neutral', active: true, caseRef: null, action: 'details', href: '/providers/tidy-nook-cleaners/book?step=done&booking=bk-7712' },
    { id: 'req-2988', kind: 'quote', ref: 'QR-2988', title: 'Mocktail bar · 40 guests', with: ['Copper & Soda'], delivery: null, shops: 1, items: 1, when: iso(now + 16 * DAY), whenEnd: null, amountCents: 64000, status: 'quote_ready', tone: 'accent-2', active: true, caseRef: null, action: 'view_quote', href: '/quotes/q-2988' },
    { id: 'bk-7001', kind: 'booking', ref: 'BK-7001', title: 'Window cleaning', with: ['Tidy Nook Cleaners'], delivery: null, shops: 1, items: 1, when: iso(now - 9 * DAY), whenEnd: null, amountCents: 12000, status: 'done', tone: 'neutral', active: false, caseRef: null, action: 'rebook', href: '/providers/tidy-nook-cleaners' },
    { id: 'ord-0990', kind: 'order', ref: 'NL-0990', title: '', with: ['Riverside Butcher'], delivery: 'direct', shops: 1, items: 2, when: iso(now - 3 * DAY), whenEnd: null, amountCents: 5995, status: 'case', tone: 'accent-2', active: false, caseRef: { id: 'rf-2201', number: 'RF-2201', kind: 'refund', open: true }, action: 'view_case', href: '/account?tab=help&case=rf-2201' },
  ];
  state.quotes.set('q-2988', { state: 'sent', validUntil: now + 71 * HOUR });
  state.cases = [
    {
      id: 'rf-2201', number: 'RF-2201', state: 'seller_review', open: true, what: 'Ribeye, AAA × 1', amountCents: 1850, taxCents: 93, merchantName: 'Riverside Butcher',
      openedAt: now - 10 * HOUR, respondBy: now + 14 * HOUR,
      notes: [{ at: now - 10 * HOUR, by: 'you', body: 'The steak arrived warm.' }, { at: now - 9 * HOUR, by: 'northline', body: 'Thanks — we asked the shop to respond.' }],
    },
  ];
}

export function accountFixtures(ctx: FixtureContext, state: AccountFixtureState): FixtureArea {
  const signedIn = (req: FixtureRequest) => (req.headers.authorization ?? '').startsWith('DPoP ');
  const iso = (t: number) => new Date(t).toISOString();
  const invalid = (field: string, message: string) => ctx.answer(422, { errors: [{ field, rule: 'invalid', message }] });
  const notFound = () => ctx.answer(404, { code: 'not_found', detail: 'Not found.' });
  const shop = state.shop;

  const quotePage = (id: string) => {
    const q = state.quotes.get(id)!;
    const lines = [
      { kind: 'labour', description: 'Bartender × 2 · 4 h', note: 'Certified · $45/h each', qty: 1, unitCents: 36000, amountCents: 36000, taxable: true },
      { kind: 'labour', description: 'Setup & teardown', note: '1 h', qty: 1, unitCents: 6000, amountCents: 6000, taxable: true },
      { kind: 'part', description: 'Mocktail ingredients · 40 guests', note: '4 signature drinks, unlimited', qty: 1, unitCents: 14000, amountCents: 14000, taxable: true },
      { kind: 'fee', description: 'Glassware & ice', note: 'Rental', qty: 1, unitCents: 4000, amountCents: 4000, taxable: true },
      { kind: 'travel', description: 'Travel', note: null, qty: 1, unitCents: 1500, amountCents: 1500, taxable: true },
      { kind: 'discount', description: 'Returning-customer discount', note: null, qty: 1, unitCents: -548, amountCents: -548, taxable: true },
    ];
    const subtotal = lines.reduce((n, l) => n + l.amountCents, 0);
    const tax = Math.round(subtotal * 0.05);
    const expired = q.validUntil < ctx.now() && q.state !== 'accepted';
    return {
      quote: {
        id, requestId: 'req-2988', ref: 'QT-2988', merchantId: 'm-bar', version: 1, state: q.state, expired,
        scope: 'Two bartenders, 4 hours on site plus setup and teardown. Four signature mocktails from the menu you picked, unlimited for 40 guests, glassware and ice included.',
        exclusions: 'Guest count over 45 adds $8/guest. A venue without a sink adds a $40 water-station fee.',
        proposedAt: iso(ctx.now() + 16 * DAY), durationMin: 240, warranty: 'none', depositKind: 'none', depositBps: null, lines,
        subtotalCents: subtotal, taxBps: 500, taxCents: tax, totalCents: subtotal + tax, depositCents: 0,
        sentAt: iso(ctx.now() - HOUR), validUntil: iso(q.validUntil), versions: [{ quoteId: id, version: 1, state: q.state, totalCents: subtotal + tax, sentAt: iso(ctx.now() - HOUR) }], currentQuoteId: id,
      },
      provider: { merchantId: 'm-bar', slug: 'copper-and-soda', name: 'Copper & Soda', tier: 'master', rating: 4.9, reviewCount: 44, onTimePct: 0.98, disputePct: 0.01, verifiedFacts: ['Licensed server'] },
      title: 'Mocktail bar · 40 guests', area: 'Sampleville', bookingId: q.booking ?? null, others: [],
    };
  };
  const caseRow = (c: AccountFixtureState['cases'][number]) => ({
    id: c.id, number: c.number, kind: 'refund', state: c.state, open: c.open, what: c.what, amountCents: c.amountCents, taxCents: c.taxCents, merchantName: c.merchantName,
    openedAt: iso(c.openedAt), respondBy: c.respondBy ? iso(c.respondBy) : null, outcome: null, settledCents: null, subject: null,
  });
  const caseDetail = (c: AccountFixtureState['cases'][number]) => ({
    row: caseRow(c),
    steps: [
      { key: 'submitted', state: 'done', at: iso(c.openedAt) },
      { key: 'seller', state: c.state === 'seller_review' ? 'current' : 'done', at: c.respondBy ? iso(c.respondBy) : null },
      { key: 'northline', state: 'todo', at: null },
      { key: 'refund', state: 'todo', at: null },
    ],
    card: { brand: 'Visa', last4: '4471' },
    thread: { id: `th-${c.id}`, code: 'HD-1001', state: c.open ? 'in_progress' : 'resolved', notes: c.notes.map((n) => ({ at: iso(n.at), by: n.by, body: n.body, attachments: [] })) },
  });
  const profile = () => ({ id: 'acct-ada', ...state.profile, locale: state.prefs.language === 'fr' ? 'fr-CA' : 'en-CA' });
  const addresses = () => ({ items: [...shop.addresses].sort((a, b) => Number(b.isDefault) - Number(a.isDefault)).map((a) => ({ ...a, label: state.labels.get(a.id) ?? null })) });
  const cards = () => ({ provider: shop.provider, publishableKey: shop.provider === 'stripe' ? 'pk_test_fixture' : null, items: shop.cards });
  const household = () => ({
    id: 'hh-1',
    members: [{ userId: 'acct-ada', name: `${state.profile.firstName} ${state.profile.lastName}`, role: 'owner', you: true }, { userId: 'acct-kofi', name: 'Kofi Example', role: 'member', you: false }],
    plan: state.plus?.plan ?? 'none', plusSince: state.plus?.since ?? null, renewsAt: state.plus ? iso(Date.parse(state.plus.since) + 30 * DAY) : null,
  });
  const wallet = () => ({
    points: { balance: state.points.balance, valueCents: state.points.balance, weekly: state.points.weekly },
    plus: state.plus ? { plan: state.plus.plan, since: state.plus.since, renewsAt: iso(Date.parse(state.plus.since) + 30 * DAY), members: 2 } : null,
  });
  const summary = () => {
    const def = shop.cards.find((c) => c.isDefault) ?? shop.cards[0];
    const n = state.notifications;
    return {
      reliability: state.profile.reliability,
      points: { balance: state.points.balance, valueCents: state.points.balance },
      plus: !!state.plus,
      activeOrders: state.activity.filter((i) => i.active).length,
      favourites: state.favourites.length,
      openCases: state.cases.filter((c) => c.open).length,
      paymentMethod: def ? { brand: def.brand, last4: def.last4 } : null,
      addresses: { count: shop.addresses.length, members: 2 },
      signIn: state.security.passkeys.length ? 'passkey' : state.security.authenticator ? 'totp' : 'sms',
      quietHours: n.quietOn ? { from: n.quietFrom.slice(0, 5), to: n.quietTo.slice(0, 5) } : null,
      dietary: state.prefs.dietary,
      province: state.prefs.province,
    };
  };
  const notifications = () => ({ events: Object.keys(state.notifications.matrix), channels: ['push', 'sms', 'email'], ...state.notifications });

  return (req) => {
    const { method, path } = req;

    // ── northline-auth: step-up and the security API (the auth session's cookie; the fixture keeps one) ──────────────
    if (path === '/api/auth/step-up/totp' && method === 'POST') {
      if (String(req.body.code ?? '') !== FIXTURE_TOTP) return ctx.answer(422, { code: 'invalid_code', detail: "That code didn't work." });
      state.security.confirmed = true;
      return ctx.answer(200, { proof: FIXTURE_PROOF, expiresAt: iso(ctx.now() + 5 * 60_000) });
    }
    if (path.startsWith('/api/auth/security')) {
      if (!state.security.confirmed) return ctx.answer(401, { code: 'unauthenticated' });
      const sec = state.security;
      if (method === 'GET' && path === '/api/auth/security') {
        return ctx.answer(200, {
          email: state.profile.email, mfaPrimary: sec.passkeys.length ? 'passkey' : 'totp', passkeys: sec.passkeys, authenticator: sec.authenticator, authenticatorSince: null,
          backupCodesRemaining: 8, backupCodesIssuedAt: null, signIns: [],
          sessions: sec.sessions.map((s) => ({ id: s.id, device: s.device, city: s.city, ipApprox: null, method: 'otp', signedInAt: iso(s.lastSeenAt - DAY), lastSeenAt: iso(s.lastSeenAt), apps: [], current: s.current })),
        });
      }
      const m = /^\/api\/auth\/security\/sessions\/([^/]+)\/revoke$/.exec(path);
      if (method === 'POST' && m) {
        const s = sec.sessions.find((x) => x.id === decodeURIComponent(m[1]!));
        if (!s) return ctx.answer(404, { code: 'not_found' });
        if (s.current) return ctx.answer(409, { code: 'current_session', detail: 'That is this session.' });
        sec.sessions = sec.sessions.filter((x) => x !== s);
        return ctx.answer(200, { sessions: sec.sessions.map((x) => ({ id: x.id, device: x.device, city: x.city, signedInAt: iso(x.lastSeenAt), lastSeenAt: iso(x.lastSeenAt), apps: [], current: x.current })) });
      }
      if (method === 'POST' && path === '/api/auth/security/sessions/revoke-others') {
        const n = sec.sessions.filter((x) => !x.current).length;
        sec.sessions = sec.sessions.filter((x) => x.current);
        return ctx.answer(200, { revoked: n });
      }
      return notFound();
    }

    // ── the api: personal ─────────────────────────────────────────────────────────────────────────────────────────
    const mine =
      ['/me/account-summary', '/me/activity', '/me/profile', '/me/erasure-request', '/me/addresses', '/me/household', '/me/plus', '/me/wallet', '/me/notifications', '/me/preferences', '/me/export', '/me/favourites', '/me/cases'].includes(path) ||
      /^\/me\/(quotes|addresses|favourites|cases)\//.test(path) ||
      (path.startsWith('/me/payment-methods') && !(method === 'GET' && path === '/me/payment-methods'));
    if (!mine) return undefined;
    if (!signedIn(req)) return ctx.answer(401, { error: 'invalid_token' });
    let m: RegExpExecArray | null;

    if (method === 'GET' && path === '/me/account-summary') return ctx.answer(200, summary());
    if (method === 'GET' && path === '/me/activity') return ctx.answer(200, { items: state.activity });

    // quotes
    if ((m = /^\/me\/quotes\/([^/]+)(\/decline|\/accept|\/accept\/confirm)?$/.exec(path))) {
      const id = decodeURIComponent(m[1]!);
      const q = state.quotes.get(id);
      if (!q) return ctx.answer(404, { code: 'not_found', detail: 'We couldn’t find this quote.' });
      if (method === 'GET' && !m[2]) {
        if (q.state === 'sent') q.state = 'viewed';
        return ctx.answer(200, quotePage(id));
      }
      const page = quotePage(id);
      if (method === 'POST' && m[2] === '/decline') {
        q.state = 'declined';
        return ctx.answer(204);
      }
      if (page.quote.expired) return ctx.answer(409, { code: 'quote_expired', detail: 'This quote has expired.' });
      if (method === 'POST' && m[2] === '/accept') {
        const key = req.headers['idempotency-key'];
        if (key && state.idempotent.has(`accept:${key}`)) return ctx.answer(200, state.idempotent.get(`accept:${key}`), { 'Idempotent-Replayed': 'true' });
        if (state.stepUp === 'required' && req.headers['x-step-up'] !== FIXTURE_PROOF) return ctx.answer(403, { code: 'step_up_required', detail: "Confirm it's you to accept." });
        if (!String(req.body.addressLine ?? '').trim()) return invalid('addressLine', 'Enter the street address.');
        const stripe = shop.provider === 'stripe';
        const answer = {
          quoteId: id, bookingId: 'bk-8001', amountCents: page.quote.subtotalCents, taxCents: page.quote.taxCents, totalCents: page.quote.totalCents,
          status: stripe ? 'requires_payment_method' : 'authorized', paymentIntent: 'pi_fixture_quote', clientSecret: stripe ? 'pi_fixture_quote_secret_x' : null,
          provider: shop.provider, publishableKey: stripe ? 'pk_test_fixture' : null,
        };
        if (key) state.idempotent.set(`accept:${key}`, answer);
        return ctx.answer(200, answer);
      }
      if (method === 'POST' && m[2] === '/accept/confirm') {
        q.state = 'accepted';
        q.booking = 'bk-8001';
        const item = state.activity.find((i) => i.id === 'req-2988');
        if (item) Object.assign(item, { id: 'bk-8001', kind: 'booking', ref: 'BK-8001', status: 'escrow', tone: 'neutral', action: 'details', href: null });
        return ctx.answer(201, { bookingId: 'bk-8001', ref: 'BK-8001', providerName: 'Copper & Soda', providerSlug: 'copper-and-soda', title: page.title, type: 'quote', startsAt: page.quote.proposedAt, endsAt: page.quote.proposedAt, priceCents: page.quote.subtotalCents, taxCents: page.quote.taxCents, heldCents: page.quote.totalCents, freeCancelUntil: null });
      }
      return notFound();
    }

    // profile
    if (path === '/me/profile') {
      if (method === 'GET') return ctx.answer(200, profile());
      const b = req.body as Record<string, string | null>;
      if (!String(b.firstName ?? '').trim()) return invalid('firstName', 'First name is required.');
      if (!String(b.lastName ?? '').trim()) return invalid('lastName', 'Last name is required.');
      if (String(b.email ?? '').toLowerCase() === 'taken@example.com') return invalid('email', 'That email is already used by another account.');
      const bd = b.birthday ?? null;
      Object.assign(state.profile, { firstName: String(b.firstName).trim(), lastName: String(b.lastName).trim(), email: String(b.email).trim(), pronouns: b.pronouns ?? null, birthday: bd });
      return ctx.answer(200, profile());
    }
    if (method === 'POST' && path === '/me/erasure-request') {
      state.profile.erasureRequestedAt ??= iso(ctx.now());
      return ctx.answer(200, profile());
    }

    // addresses & household & Plus
    if (path === '/me/addresses') {
      if (method === 'GET') return ctx.answer(200, addresses());
      const b = req.body as Record<string, string | undefined>;
      if (!b.street?.trim()) return invalid('street', 'Enter the street address.');
      if (!/^[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d$/.test(b.postal ?? '')) return invalid('postal', 'Enter a Canadian postal code, like T2P 1B5.');
      if (!PROVINCES.some((p) => p.code === b.province)) return invalid('province', 'Choose a Canadian province or territory.');
      const a = { id: `addr-${shop.addresses.length + 1}`, street: b.street.trim(), unit: b.unit, city: String(b.city), province: String(b.province), postal: String(b.postal), note: b.note, isDefault: shop.addresses.length === 0 };
      shop.addresses.push(a);
      if (b.label) state.labels.set(a.id, b.label);
      return ctx.answer(201, { ...a, label: b.label ?? null });
    }
    if ((m = /^\/me\/addresses\/([^/]+)(\/default)?$/.exec(path))) {
      const id = decodeURIComponent(m[1]!);
      const a = shop.addresses.find((x) => x.id === id);
      if (!a) return notFound();
      if (method === 'POST' && m[2]) shop.addresses.forEach((x) => (x.isDefault = x === a));
      else if (method === 'DELETE') {
        shop.addresses.splice(shop.addresses.indexOf(a), 1);
        if (a.isDefault && shop.addresses[0]) shop.addresses[0].isDefault = true;
      } else return notFound();
      return ctx.answer(200, addresses());
    }
    if (method === 'GET' && path === '/me/household') return ctx.answer(200, household());
    if (path === '/me/plus') {
      if (method === 'POST') {
        if (req.body.plan !== 'monthly' && req.body.plan !== 'annual') return invalid('plan', 'Choose monthly or annual.');
        state.plus = { plan: req.body.plan, since: iso(ctx.now()) };
      } else if (method === 'DELETE') state.plus = null;
      return ctx.answer(200, household());
    }
    if (method === 'GET' && path === '/me/wallet') return ctx.answer(200, wallet());

    // payment methods (the list itself is the shop area's GET)
    if (method === 'POST' && path === '/me/payment-methods/setup-intents') {
      state.setups++;
      const stripe = shop.provider === 'stripe';
      return ctx.answer(200, { setupIntentId: `seti_fixture_${state.setups}`, clientSecret: stripe ? `seti_fixture_${state.setups}_secret_x` : null, provider: shop.provider, publishableKey: stripe ? 'pk_test_fixture' : null });
    }
    if (method === 'POST' && path === '/me/payment-methods') {
      if (!String(req.body.setupIntentId ?? '').startsWith('seti_fixture_')) return ctx.answer(409, { code: 'setup_not_confirmed', detail: 'The card was not confirmed.' });
      shop.cards.forEach((c) => (c.isDefault = false));
      shop.cards.push({ id: `pm_fixture_${state.setups}`, brand: 'Mastercard', last4: '5454', expMonth: 12, expYear: 2029, isDefault: true, addedAt: iso(ctx.now()) });
      return ctx.answer(200, cards());
    }
    if ((m = /^\/me\/payment-methods\/([^/]+)(\/default)?$/.exec(path))) {
      const c = shop.cards.find((x) => x.id === decodeURIComponent(m![1]!));
      if (!c) return notFound();
      if (method === 'POST' && m[2]) shop.cards.forEach((x) => (x.isDefault = x === c));
      else if (method === 'DELETE') {
        shop.cards.splice(shop.cards.indexOf(c), 1);
        if (c.isDefault && shop.cards[0]) shop.cards[0].isDefault = true;
      } else return notFound();
      return ctx.answer(200, cards());
    }

    // notifications & preferences & export
    if (path === '/me/notifications') {
      if (method === 'GET') return ctx.answer(200, notifications());
      const b = req.body as Partial<AccountFixtureState['notifications']>;
      if (b.matrix?.security && Object.values(b.matrix.security).some((v) => v === false)) return invalid('matrix', 'Security alerts stay on.');
      for (const [e, row] of Object.entries(b.matrix ?? {})) Object.assign((state.notifications.matrix[e] ??= {}), row);
      for (const k of ['quietOn', 'quietFrom', 'quietTo', 'language', 'marketing'] as const) if (b[k] !== undefined) (state.notifications as Record<string, unknown>)[k] = k === 'quietFrom' || k === 'quietTo' ? `${String(b[k]).slice(0, 5)}:00` : b[k];
      return ctx.answer(200, notifications());
    }
    if (path === '/me/preferences') {
      if (method === 'GET') return ctx.answer(200, state.prefs);
      const b = req.body as Partial<AccountFixtureState['prefs']>;
      if (b.province !== undefined && !PROVINCES.some((p) => p.code === b.province)) return invalid('province', 'Choose from the list.');
      if (b.language !== undefined && !['en', 'fr'].includes(b.language)) return invalid('language', 'Choose from the list.');
      Object.assign(state.prefs, Object.fromEntries(Object.entries(b).filter(([, v]) => v !== undefined)));
      return ctx.answer(200, state.prefs);
    }
    if (method === 'GET' && path === '/me/export') return ctx.answer(200, { profile: profile(), addresses: addresses().items, preferences: state.prefs, notifications: state.notifications });

    // favourites
    if (method === 'GET' && path === '/me/favourites') return ctx.answer(200, { items: state.favourites });
    if ((m = /^\/me\/favourites\/([^/]+)$/.exec(path)) && method === 'DELETE') {
      state.favourites = state.favourites.filter((f) => f.merchantId !== decodeURIComponent(m![1]!));
      return ctx.answer(204);
    }

    // cases
    if (method === 'GET' && path === '/me/cases') return ctx.answer(200, { items: state.cases.map(caseRow) });
    if ((m = /^\/me\/cases\/([^/]+)(\/notes)?$/.exec(path))) {
      const c = state.cases.find((x) => x.id === decodeURIComponent(m![1]!));
      if (!c) return notFound();
      if (method === 'POST' && m[2]) {
        if (!c.open) return ctx.answer(409, { code: 'case_closed', detail: 'This case is closed.' });
        c.notes.push({ at: ctx.now(), by: 'you', body: String(req.body.body ?? '') });
      }
      return ctx.answer(200, caseDetail(c));
    }
    return undefined;
  };
}
