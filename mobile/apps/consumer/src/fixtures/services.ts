import { colors } from '@northline/mobile-kit';

import type { ActivityItem, Booking, BookingState, ProviderPage, ProviderService, ReviewPage } from '../api/services';
import { STEP_UP_PROOF } from './auth';
import type { FixtureArea, FixtureContext, FixtureRequest } from './context';

/**
 * Journey C's api (S-100) with made-up businesses: the services landing, one bookable category (a mobile mechanic, with
 * vehicles) and a quoted one, providers, the live calendar, holds, the escrow checkout (the api's payment stand-in, or
 * Stripe-shaped intents), bookings at every step of the job, the sign-off, quote requests, favourites, the activity
 * inbox and quiet hours. Saved cards (`GET /me/payment-methods`) are the shop area's (`server.shop.cards`, `.provider`).
 * The businesses keep their time in {@link BUSINESS_ZONE} (a fixed offset that names no place), so screens prove they
 * show the business's time, not the phone's.
 */
export const BUSINESS_ZONE = 'Etc/GMT+5';
const OFFSET_H = 5; // Etc/GMT+5 is UTC−5 all year

const names = (en: string, fr?: string): Record<string, string> => (fr ? { en, fr } : { en });

const MECHANIC_SERVICES: ProviderService[] = [
  { id: 'svc-brakes', name: 'Brake inspection', included: 'Pads, rotors, fluid — written report', pricingMode: 'fixed', priceCents: 8900, durationMin: 45, instantBook: true, categorySlug: 'mobile-mechanic', kind: 'visit' },
  { id: 'svc-oil', name: 'Oil & filter', included: 'Full synthetic, parts at cost', pricingMode: 'fixed', priceCents: 7900, durationMin: 30, instantBook: true, categorySlug: 'mobile-mechanic', kind: 'visit' },
  { id: 'svc-diag', name: 'Diagnostic scan', included: 'Check-engine and electrical', pricingMode: 'fixed', priceCents: 12000, durationMin: 60, instantBook: true, categorySlug: 'mobile-mechanic', kind: 'visit' },
  { id: 'svc-alternator', name: 'Alternator replacement', included: 'Quoted after a look', pricingMode: 'quote', priceCents: null, durationMin: 120, instantBook: false, categorySlug: 'mobile-mechanic', kind: 'visit' },
];

const REVIEWS: ReviewPage = {
  items: [
    { id: 'rv-1', rating: 5, text: 'Showed up at 7 am in the cold, fixed the alternator in the parkade, receipt in the app before he left.', author: 'Dana K.', jobLabel: 'Alternator', refType: 'booking', createdAt: '2026-09-28T15:00:00Z', reply: null },
    { id: 'rv-2', rating: 4, text: 'On time and clear about the price.', author: 'Sam P.', jobLabel: 'Oil & filter', refType: 'booking', createdAt: '2026-09-20T15:00:00Z', reply: null },
  ],
  nextOffset: 2,
};

const provider = (over: Partial<ProviderPage> & Pick<ProviderPage, 'merchantId' | 'slug' | 'name' | 'tier'>): ProviderPage => ({
  city: 'Sampleville',
  since: '2019-04-01T00:00:00Z',
  verifiedFacts: ['Red Seal journeyman', 'Licensed', '$2M insured'],
  rating: 4.9,
  reviewCount: 312,
  onTimePct: 98,
  disputePct: 0.3,
  rebookPct: 71,
  kind: 'visit',
  category: { id: 'service.automotive.mobile-mechanic', slug: 'mobile-mechanic', names: names('Mobile mechanic', 'Mécanicien mobile') },
  vehicle: true,
  quoteable: true,
  services: MECHANIC_SERVICES,
  zones: ['Old Town'],
  nextAvailable: null,
  taxBps: 500,
  reviews: REVIEWS,
  timeZone: BUSINESS_ZONE,
  ...over,
});

export const PROVIDERS: ProviderPage[] = [
  provider({ merchantId: 'm-prairie', slug: 'prairie-wrench', name: 'Prairie Wrench', tier: 'master' }),
  provider({ merchantId: 'm-chinook', slug: 'harbour-auto', name: 'Harbour Auto Mobile', tier: 'master', rating: 4.8, reviewCount: 190, onTimePct: 96, verifiedFacts: ['EV certified'] }),
  provider({ merchantId: 'm-bow', slug: 'river-mechanics', name: 'River Mechanics', tier: 'trusted', rating: 4.7, reviewCount: 88, onTimePct: 93, verifiedFacts: [] }),
  provider({ merchantId: 'm-new', slug: 'new-garage', name: 'New Garage on Wheels', tier: 'registered', rating: 0, reviewCount: 0, onTimePct: null, disputePct: null, rebookPct: null, verifiedFacts: [], services: MECHANIC_SERVICES.slice(0, 1).map((s) => ({ ...s, instantBook: false })), reviews: { items: [], nextOffset: null } }),
];
const BRANDS = [colors.accent, colors.accent800, colors.accent2_700, colors.neutral800];

export interface FixtureBooking extends Booking {
  customer: string;
}
export interface ServicesFixtureState {
  /** The landing has no live category yet. */
  empty: boolean;
  /** `fake` = the api's payment stand-in (authorized at once); `stripe` = an intent to confirm with Stripe's SDK. */
  payment: 'fake' | 'stripe';
  stepUp: 'none' | 'required' | 'enrol';
  holds: Map<string, { holdId: string; bookingId: string; slug: string; serviceId: string; startsAt: string; endsAt: string; checkout?: Record<string, unknown> }>;
  bookings: Map<string, FixtureBooking>;
  favourites: Set<string>;
  quoteRequests: Array<Record<string, unknown>>;
  quiet: { quietOn: boolean; quietFrom: string; quietTo: string };
  idempotent: Map<string, unknown>;
  /** Slot starts someone else just took (the hold answers 409). */
  taken: Set<string>;
  seq: number;
}

const fixtureBooking = (id: string, state: BookingState, at: number, extra: Partial<FixtureBooking> = {}): FixtureBooking => ({
  bookingId: id,
  ref: 'BK-7712',
  providerName: 'Prairie Wrench',
  providerSlug: 'prairie-wrench',
  memberFirstName: 'Ravi',
  title: 'Brake inspection',
  type: 'visit',
  startsAt: new Date(at).toISOString(),
  endsAt: new Date(at + 45 * 60_000).toISOString(),
  addressLine: '1204 Example Ave, Apt 804',
  priceCents: 8900,
  taxCents: 445,
  heldCents: 9345,
  freeCancelUntil: new Date(at - 12 * 3_600_000).toISOString(),
  merchantId: 'm-prairie',
  state,
  timeZone: BUSINESS_ZONE,
  steps: [],
  report: null,
  photoCount: 0,
  releasesAt: null,
  customer: 'fixture-user',
  ...extra,
});

export function newServicesState(now: () => number = Date.now): ServicesFixtureState {
  const t = now();
  const hour = 3_600_000;
  return {
    empty: false,
    payment: 'fake',
    stepUp: 'none',
    holds: new Map(),
    bookings: new Map(
      [
        fixtureBooking('01J9BOOKING', 'confirmed', t + 26 * hour),
        fixtureBooking('01J9BOOKINGROUTE', 'en_route', t + 0.5 * hour, { steps: [{ type: 'en_route', at: new Date(t - 0.2 * hour).toISOString() }] }),
        fixtureBooking('01J9BOOKINGDONE', 'completed', t - 2 * hour, {
          steps: [
            { type: 'en_route', at: new Date(t - 2.5 * hour).toISOString() },
            { type: 'on_site', at: new Date(t - 2 * hour).toISOString() },
            { type: 'completed', at: new Date(t - 1.2 * hour).toISOString() },
          ],
          report: 'Front pads 4 mm, rear 6 mm — fine for the winter. Grinding was a stone in the caliper; removed. No parts used.',
          photoCount: 2,
          releasesAt: new Date(t - 1.2 * hour + 48 * hour).toISOString(),
        }),
        fixtureBooking('01J9BOOKINGPAID', 'signed_off', t - 30 * hour, {
          steps: [
            { type: 'completed', at: new Date(t - 29 * hour).toISOString() },
            { type: 'signed_off', at: new Date(t - 28 * hour).toISOString() },
          ],
          report: 'All done.',
        }),
      ].map((b) => [b.bookingId, b]),
    ),
    favourites: new Set(),
    quoteRequests: [],
    quiet: { quietOn: true, quietFrom: '22:00', quietTo: '07:00' },
    idempotent: new Map(),
    taken: new Set(),
    seq: 0,
  };
}

/** `YYYY-MM-DD` of an instant in the business's zone. */
const localDate = (ms: number) => new Date(ms - OFFSET_H * 3_600_000).toISOString().slice(0, 10);
/** The instant of `hour`:00 on a business-local date. */
const at = (date: string, hour: number) => Date.parse(`${date}T00:00:00Z`) + (hour + OFFSET_H) * 3_600_000;
export const SLOT_HOURS = [7, 9, 11, 13, 15, 17];

/** The calendar: five days from tomorrow (business time); the fourth is full; 13:00 and 17:00 are taken every day. */
export function calendarDays(now: number, serviceId: string, taken: Set<string>) {
  const today = localDate(now);
  return [1, 2, 3, 4, 5].map((n) => {
    const date = localDate(Date.parse(`${today}T12:00:00Z`) + n * 86_400_000);
    const slots = SLOT_HOURS.map((h) => {
      const startsAt = new Date(at(date, h)).toISOString();
      return { startsAt, free: n !== 4 && h !== 13 && h !== 17 && !taken.has(startsAt) };
    });
    return { date, closed: null, free: slots.filter((s) => s.free).length, slots, serviceId };
  });
}

const unauthorized = (ctx: FixtureContext) => ctx.answer(401, { code: 'unauthorized', detail: 'Not signed in, or the token expired.' });
const signed = (req: FixtureRequest) => (req.headers.authorization ?? '').startsWith('DPoP ');

const view = ({ customer: _c, ...b }: FixtureBooking): Booking => b;

export function servicesFixtures(ctx: FixtureContext, state: ServicesFixtureState): FixtureArea {
  const bySlug = (slug: string) => PROVIDERS.find((p) => p.slug === slug);
  const card = (p: ProviderPage, i: number) => ({
    merchantId: p.merchantId, slug: p.slug, name: p.name, tier: p.tier, brandColor: BRANDS[i % BRANDS.length],
    blurb: i === 0 ? 'Parts at cost, receipts on every job.' : i === 3 ? 'New to Northline — request-only until 20 jobs.' : 'Fleet and family cars.',
    rating: p.rating, reviewCount: p.reviewCount, onTimePct: p.onTimePct, disputePct: p.disputePct, rebookPct: p.rebookPct,
    fromCents: p.services.find((s) => s.priceCents)?.priceCents ?? null, pricingMode: 'fixed', instantBook: p.services.some((s) => s.instantBook),
    nextAvailable: new Date(at(localDate(ctx.now()), 15) + (i % 2) * 86_400_000).toISOString(), zones: p.zones, timeZone: BUSINESS_ZONE,
  });
  const once = (req: FixtureRequest, scope: string, make: () => Response) => {
    const key = req.headers['idempotency-key'];
    if (!key) return ctx.answer(422, { errors: [{ field: 'Idempotency-Key', rule: 'required', message: 'Idempotency-Key header is required.' }] });
    const saved = state.idempotent.get(`${scope}:${key}`) as { status: number; body: unknown } | undefined;
    if (saved) return ctx.answer(saved.status, saved.body, { 'Idempotent-Replayed': 'true' });
    const res = make();
    if (res.ok) void res.clone().json().then((body) => state.idempotent.set(`${scope}:${key}`, { status: res.status, body }));
    return res;
  };

  return (req) => {
    const p = req.path;
    const m = (re: RegExp) => re.exec(p);
    let r: RegExpExecArray | null;

    // ── public reads ──
    if (req.method === 'GET' && p === '/public/services') {
      if (state.empty) return ctx.answer(200, { liveCategories: 0, providers: 0, provinces: [], groups: [] });
      return ctx.answer(200, {
        liveCategories: 4, providers: 7, provinces: ['XA'],
        groups: [
          { id: 'service.automotive', key: 'automotive', names: names('Auto', 'Auto'), note: null, items: [
            { slug: 'mobile-mechanic', names: names('Mobile mechanic', 'Mécanicien mobile'), kind: 'visit', providers: 4 },
            { slug: 'tire-change', names: names('Tire change', 'Changement de pneus'), kind: 'visit', providers: 0 },
          ] },
          { id: 'service.home-trades', key: 'home_trades', names: names('Home & trades', 'Maison et métiers'), note: null, items: [
            { slug: 'plumber', names: names('Plumber', 'Plombier'), kind: 'visit', providers: 2 },
            { slug: 'cleaning', names: names('Cleaning'), kind: 'home', providers: 1 },
          ] },
          { id: 'service.events', key: 'events_and_hospitality', names: names('Events & hospitality', 'Événements et réception'), note: null, items: [
            { slug: 'cocktail-bar', names: names('Cocktail & mocktail bar', 'Bar à cocktails et mocktails'), kind: 'event', providers: 1 },
          ] },
        ],
      });
    }
    if (req.method === 'GET' && (r = m(/^\/public\/services\/([^/]+)$/))) {
      if (r[1] !== 'mobile-mechanic' && r[1] !== 'plumber') return ctx.answer(404, { code: 'not_found', detail: 'No such service.' });
      const mech = r[1] === 'mobile-mechanic';
      return ctx.answer(200, { id: `service.x.${r[1]}`, slug: r[1], names: mech ? names('Mobile mechanic', 'Mécanicien mobile') : names('Plumber', 'Plombier'), kind: 'visit', vehicle: mech, providers: mech ? 4 : 0, quoteable: true });
    }
    if (req.method === 'GET' && (r = m(/^\/public\/services\/([^/]+)\/providers$/))) {
      const items = r[1] === 'mobile-mechanic' ? PROVIDERS.map(card) : [];
      return ctx.answer(200, { categorySlug: r[1], kind: 'visit', area: items.length ? 'Old Town' : null, city: req.url.searchParams.get('city') ?? 'Sampleville', items });
    }
    if (req.method === 'GET' && (r = m(/^\/public\/providers\/([^/]+)$/))) {
      const found = bySlug(decodeURIComponent(r[1]!));
      if (!found) return ctx.answer(404, { code: 'not_found', detail: 'This provider page is not available.' });
      return ctx.answer(200, { ...found, nextAvailable: card(found, 0).nextAvailable });
    }
    if (req.method === 'GET' && (r = m(/^\/public\/providers\/([^/]+)\/reviews$/))) {
      return ctx.answer(200, { items: [{ ...REVIEWS.items[0]!, id: 'rv-3', author: 'Lee M.' }], nextOffset: null });
    }
    if (req.method === 'GET' && (r = m(/^\/public\/providers\/([^/]+)\/slots$/))) {
      const serviceId = req.url.searchParams.get('serviceId') ?? '';
      return ctx.answer(200, { serviceId, durationMin: 45, days: calendarDays(ctx.now(), serviceId, state.taken).map(({ serviceId: _s, ...d }) => d), timeZone: BUSINESS_ZONE });
    }

    const mine = ['/me/bookings', '/me/quote-requests', '/me/favourites'].some((x) => p.startsWith(x)) || ['/me/activity', '/me/notifications'].includes(p);
    if (!mine) return undefined;
    if (!signed(req)) return unauthorized(ctx);

    // ── holds, checkout, confirmation ──
    if (req.method === 'POST' && p === '/me/bookings/holds') {
      const { slug, serviceId, startsAt } = req.body as Record<string, string>;
      if (!serviceId) return ctx.answer(422, { errors: [{ field: 'serviceId', rule: 'required', message: 'Pick a service.' }] });
      if (!startsAt) return ctx.answer(422, { errors: [{ field: 'startsAt', rule: 'required', message: 'Pick a time.' }] });
      if (state.taken.has(startsAt)) return ctx.answer(409, { code: 'slot_taken', detail: 'That time was just taken. Pick another slot.' });
      for (const [id, h] of state.holds) if (h.slug === slug && !h.checkout) state.holds.delete(id); // a new hold replaces the last
      const n = ++state.seq;
      const hold = { holdId: `hold-${n}`, bookingId: `01J9BKNEW${n}`, slug: slug!, serviceId, startsAt, endsAt: new Date(Date.parse(startsAt) + 45 * 60_000).toISOString() };
      state.holds.set(hold.holdId, hold);
      return ctx.answer(201, { ...hold, expiresAt: new Date(ctx.now() + 600_000).toISOString() });
    }
    if (req.method === 'DELETE' && (r = m(/^\/me\/bookings\/holds\/([^/]+)$/))) {
      state.holds.delete(r[1]!);
      return ctx.answer(204);
    }
    if (req.method === 'POST' && p === '/me/bookings/checkout') {
      return once(req, 'checkout', () => {
        const body = req.body as Record<string, unknown>;
        const hold = state.holds.get(String(body.holdId));
        if (!hold) return ctx.answer(409, { code: 'hold_expired', detail: 'Your 10-minute hold ended. Pick the time again.' });
        const errors: Array<{ field: string; rule: string; message: string }> = [];
        if (String(body.description ?? '').trim().length < 10) errors.push({ field: 'description', rule: 'length', message: 'Describe the problem in at least 10 characters.' });
        const v = (body.vehicle ?? {}) as Record<string, string>;
        if (!v.year || !v.make || !v.model) errors.push({ field: 'vehicle.make', rule: 'required', message: 'Tell us the vehicle year, make and model.' });
        if (!String(body.addressLine ?? '').trim()) errors.push({ field: 'addressLine', rule: 'required', message: 'Pick or enter the address.' });
        if (String(body.accessNote ?? '').trim().length < 3) errors.push({ field: 'accessNote', rule: 'length', message: 'Add access instructions (3+ characters).' });
        if (!body.agreePolicies) errors.push({ field: 'agreePolicies', rule: 'required', message: 'Accept the cancellation policy to continue.' });
        if (!body.agreeTerms) errors.push({ field: 'agreeTerms', rule: 'required', message: 'Agree to the Northline terms to continue.' });
        if (errors.length) return ctx.answer(422, { errors });
        if (state.stepUp === 'required' && req.headers['x-step-up'] !== STEP_UP_PROOF) return ctx.answer(403, { code: 'step_up_required', detail: "Confirm it's you with your passkey or authenticator app to pay." });
        if (state.stepUp === 'enrol') return ctx.answer(403, { code: 'second_factor_required', detail: 'Add a passkey to pay: payments sit behind a second factor.' });
        const service = MECHANIC_SERVICES.find((s) => s.id === hold.serviceId) ?? MECHANIC_SERVICES[0]!;
        const price = service.priceCents ?? 0;
        const tax = Math.round(price * 0.05);
        hold.checkout = body;
        const stripe = state.payment === 'stripe';
        return ctx.answer(200, {
          holdId: hold.holdId, bookingId: hold.bookingId, priceCents: price, taxCents: tax, totalCents: price + tax,
          status: stripe ? 'requires_payment_method' : 'authorized', paymentIntent: `pi_${hold.bookingId}`,
          clientSecret: stripe ? `pi_${hold.bookingId}_secret_fixture` : null, provider: state.payment, publishableKey: stripe ? 'pk_test_fixture' : null, booking: null,
        });
      });
    }
    if (req.method === 'POST' && (r = m(/^\/me\/bookings\/holds\/([^/]+)\/confirm$/))) {
      return once(req, 'confirm', () => {
        const hold = state.holds.get(r![1]!);
        if (!hold) return ctx.answer(409, { code: 'hold_expired', detail: 'Your 10-minute hold ended. Pick the time again.' });
        if (!hold.checkout) return ctx.answer(409, { code: 'checkout_not_started', detail: 'Start the payment first.' });
        const service = MECHANIC_SERVICES.find((s) => s.id === hold.serviceId) ?? MECHANIC_SERVICES[0]!;
        const page = bySlug(hold.slug) ?? PROVIDERS[0]!;
        const price = service.priceCents ?? 0;
        const b = fixtureBooking(hold.bookingId, 'confirmed', Date.parse(hold.startsAt), {
          ref: `BK-${7712 + state.seq}`, providerName: page.name, providerSlug: page.slug, merchantId: page.merchantId, title: service.name,
          priceCents: price, taxCents: Math.round(price * 0.05), heldCents: price + Math.round(price * 0.05), endsAt: hold.endsAt,
          addressLine: String(hold.checkout.addressLine ?? ''),
        });
        state.bookings.set(b.bookingId, b);
        state.holds.delete(hold.holdId);
        state.taken.add(hold.startsAt);
        return ctx.answer(201, view(b));
      });
    }
    if (req.method === 'GET' && (r = m(/^\/me\/bookings\/([^/]+)$/))) {
      const b = state.bookings.get(decodeURIComponent(r[1]!));
      return b ? ctx.answer(200, view(b)) : ctx.answer(404, { code: 'not_found', detail: 'No such booking.' });
    }
    if (req.method === 'POST' && (r = m(/^\/me\/bookings\/([^/]+)\/sign-off$/))) {
      const b = state.bookings.get(decodeURIComponent(r[1]!));
      if (!b) return ctx.answer(404, { code: 'not_found', detail: 'No such booking.' });
      if (b.state === 'signed_off') return ctx.answer(200, view(b));
      if (b.state !== 'completed') return ctx.answer(409, { code: 'job_state', detail: `This job is ${b.state.replace('_', ' ')} and can't move to signed off.` });
      b.state = 'signed_off';
      b.releasesAt = null;
      b.steps = [...b.steps, { type: 'signed_off', at: new Date(ctx.now()).toISOString() }];
      return ctx.answer(200, view(b));
    }

    // ── quotes, favourites ──
    if (req.method === 'POST' && p === '/me/quote-requests') {
      const body = req.body as Record<string, unknown>;
      const errors: Array<{ field: string; rule: string; message: string }> = [];
      if (String(body.description ?? '').trim().length < 10) errors.push({ field: 'description', rule: 'length', message: 'Describe the job in at least 10 characters.' });
      const v = (body.vehicle ?? {}) as Record<string, string>;
      if (body.category === 'mobile-mechanic' && (!v.year || !v.make || !v.model)) errors.push({ field: 'vehicle', rule: 'required', message: 'Tell us the vehicle year, make and model.' });
      if (errors.length) return ctx.answer(422, { errors });
      state.quoteRequests.push(body);
      return ctx.answer(201, { requestId: `qr-${state.quoteRequests.length}`, ref: 'QR-3104', respondBy: new Date(ctx.now() + 2 * 3_600_000).toISOString(), expiresAt: new Date(ctx.now() + 72 * 3_600_000).toISOString(), providers: (body.providers as string[]).length });
    }
    if (req.method === 'GET' && p === '/me/favourites') return ctx.answer(200, { items: [...state.favourites].map((merchantId) => ({ merchantId })) });
    if ((r = m(/^\/me\/favourites\/([^/]+)$/))) {
      if (req.method === 'PUT') state.favourites.add(r[1]!);
      else if (req.method === 'DELETE') state.favourites.delete(r[1]!);
      return ctx.answer(204);
    }

    // ── the inbox ──
    if (req.method === 'GET' && p === '/me/activity') {
      const t = ctx.now();
      const items: ActivityItem[] = [
        { id: '01J9BOOKINGROUTE', kind: 'booking', ref: 'BK-7712', title: 'Brake inspection', with: ['Prairie Wrench'], when: new Date(t - 5 * 60_000).toISOString(), amountCents: 9345, status: 'on_the_way', tone: 'accent-2', active: true, action: 'track' },
        { id: '01J9BOOKINGDONE', kind: 'booking', ref: 'BK-7713', title: 'Oil & filter', with: ['Prairie Wrench'], when: new Date(t - 26 * 3_600_000).toISOString(), amountCents: 8295, status: 'completed', tone: 'accent', active: true, action: 'details' },
        { id: 'NL-48213', kind: 'order', ref: 'NL-48213', title: 'Grocery run · 3 shops', with: ['Old Town Bakery'], when: new Date(t - 28 * 3_600_000).toISOString(), amountCents: 6420, status: 'delivered', tone: 'accent', active: true, action: 'track' },
        { id: 'qr-9', kind: 'quote', ref: 'QT-2988', title: 'Mocktail bar · 40 guests', with: ['Sable & Soda'], when: new Date(t - 50 * 3_600_000).toISOString(), amountCents: 64000, status: 'quote_ready', tone: 'accent-2', active: true, action: 'view_quote' },
      ];
      return ctx.answer(200, { items });
    }
    if (p === '/me/notifications') {
      if (req.method === 'PUT' && typeof req.body.quietOn === 'boolean') state.quiet.quietOn = req.body.quietOn;
      return ctx.answer(200, { events: [], channels: ['push', 'email', 'sms'], matrix: {}, ...state.quiet, language: 'en', marketing: 'off' });
    }
    return ctx.answer(404, { code: 'not_found', detail: `No fixture for ${req.method} ${p}` });
  };
}
