import type { Message, RequestRow, ThreadRow } from '../api/hire';
import type { FixtureArea, FixtureContext, FixtureRequest } from './context';
import type { ServicesFixtureState } from './services';

/**
 * S-153's endpoints on the fixture backend: the customer's threads with providers (masking what reads like a phone
 * number, as the api does), a booking's cancellation terms and the cancel (the fee kept from the hold), job photos and
 * the quote requests list. Bookings are the services area's; the threads, photos and requests live here.
 */
export interface HireFixtureState {
  threads: Map<string, { row: ThreadRow; messages: Message[] }>;
  photos: string[];
  requests: RequestRow[];
  /** The late-cancellation terms every fixture booking was made under. */
  terms: { windowHours: number; feeBps: number; feeCents: number };
  seq: number;
}

const PHONE = /\+?\d[\d\s().-]{7,}\d/g;

export function newHireState(now: number): HireFixtureState {
  return {
    threads: new Map([
      [
        'th-1',
        {
          row: { id: 'th-1', refType: 'quote', refId: 'qr-9', refCode: 'QT-2988', subject: 'Mocktail bar · 40 guests', businessName: 'Sable & Soda', businessSlug: 'sable-and-soda', href: '/quotes/q-2988', lastMessageAt: new Date(now - 3_600_000).toISOString(), lastMessage: 'Is the venue indoors?', unread: true },
          messages: [{ id: 'ms-1', from: 'business', body: 'Is the venue indoors?', at: new Date(now - 3_600_000).toISOString(), flagged: false }],
        },
      ],
    ]),
    photos: [],
    requests: [
      { id: 'qr-9', ref: 'QT-2988', title: 'Mocktail bar · 40 guests', createdAt: new Date(now - 50 * 3_600_000).toISOString(), expiresAt: new Date(now + 5 * 24 * 3_600_000).toISOString(), providers: 2, quotes: 1, lowestCents: 64000, status: 'quoted', bookingId: null, photoCount: 2, href: '/quotes/q-2988' },
      { id: 'qr-7', ref: 'QT-2901', title: 'Alternator', createdAt: new Date(now - 9 * 24 * 3_600_000).toISOString(), expiresAt: null, providers: 3, quotes: 0, lowestCents: null, status: 'expired', bookingId: null, photoCount: 0, href: '/quotes/requests/qr-7' },
    ],
    terms: { windowHours: 24, feeBps: 5000, feeCents: 0 },
    seq: 1,
  };
}

export function hireFixtures(ctx: FixtureContext, state: HireFixtureState, services: ServicesFixtureState): FixtureArea {
  const signed = (req: FixtureRequest) => (req.headers.authorization ?? '').startsWith('DPoP ');
  const iso = () => new Date(ctx.now()).toISOString();
  const invalid = (field: string, message: string) => ctx.answer(422, { errors: [{ field, rule: 'invalid', message }] });

  return (req) => {
    const { method, path } = req;
    let m: RegExpExecArray | null;
    const mine = path === '/me/threads' || path.startsWith('/me/threads/') || path === '/me/photos' || (method === 'GET' && path === '/me/quote-requests') || /^\/me\/bookings\/[^/]+\/(cancellation|cancel)$/.test(path);
    if (!mine) return undefined;
    if (!signed(req)) return ctx.answer(401, { code: 'unauthorized', detail: 'Not signed in, or the token expired.' });

    if (method === 'GET' && path === '/me/threads') {
      const items = [...state.threads.values()].map((t) => t.row).sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? ''));
      return ctx.answer(200, { items });
    }
    if (method === 'POST' && path === '/me/threads') {
      const { refType, refId } = req.body as { refType: 'quote' | 'booking'; refId: string };
      const found = [...state.threads.values()].find((t) => t.row.refType === refType && t.row.refId === refId);
      if (found) return ctx.answer(200, { thread: found.row, messages: found.messages });
      const booking = refType === 'booking' ? services.bookings.get(refId) : undefined;
      if (refType === 'booking' && !booking) return ctx.answer(404, { code: 'not_found', detail: 'Not found.' });
      const id = `th-${++state.seq}`;
      const row: ThreadRow = booking
        ? { id, refType, refId, refCode: booking.ref, subject: booking.title, businessName: booking.providerName, businessSlug: booking.providerSlug, href: `/bookings/${refId}`, lastMessageAt: null, lastMessage: null, unread: false }
        : { id, refType, refId, refCode: 'QT-2988', subject: 'Mocktail bar · 40 guests', businessName: 'Sable & Soda', businessSlug: 'sable-and-soda', href: '/quotes/q-2988', lastMessageAt: null, lastMessage: null, unread: false };
      const page = { row, messages: [] as Message[] };
      state.threads.set(id, page);
      return ctx.answer(201, { thread: row, messages: [] });
    }
    if ((m = /^\/me\/threads\/([^/]+)(\/messages|\/read)?$/.exec(path))) {
      const t = state.threads.get(decodeURIComponent(m[1]!));
      if (!t) return ctx.answer(404, { code: 'not_found', detail: 'Not found.' });
      if (method === 'GET' && !m[2]) return ctx.answer(200, { thread: t.row, messages: t.messages });
      if (method === 'POST' && m[2] === '/read') {
        t.row.unread = false;
        return ctx.answer(204);
      }
      if (method === 'POST' && m[2] === '/messages') {
        const body = String((req.body as { body?: string }).body ?? '').trim();
        if (!body) return invalid('body', 'Write a message.');
        const masked = body.replace(PHONE, '•••');
        const msg: Message = { id: `ms-${++state.seq}`, from: 'me', body: masked, at: iso(), flagged: masked !== body };
        t.messages.push(msg);
        Object.assign(t.row, { lastMessage: masked, lastMessageAt: msg.at });
        return ctx.answer(201, msg);
      }
    }

    if (method === 'POST' && path === '/me/photos') {
      const id = `ph-${state.photos.length + 1}`;
      state.photos.push(id);
      return ctx.answer(201, { id, fileName: `${id}.jpg`, contentType: 'image/jpeg', size: 1000 });
    }
    if (method === 'GET' && path === '/me/quote-requests') return ctx.answer(200, { items: state.requests });

    if ((m = /^\/me\/bookings\/([^/]+)\/(cancellation|cancel)$/.exec(path))) {
      const b = services.bookings.get(decodeURIComponent(m[1]!));
      if (!b) return ctx.answer(404, { code: 'not_found', detail: 'Not found.' });
      const freeUntil = Date.parse(b.startsAt) - state.terms.windowHours * 3_600_000;
      const cancellable = ['requested', 'confirmed', 'en_route'].includes(b.state);
      const late = ctx.now() >= freeUntil;
      const fee = late ? Math.min(b.priceCents, state.terms.feeCents + Math.round((b.priceCents * state.terms.feeBps) / 10_000)) : 0;
      const feeTax = Math.round((fee * b.taxCents) / Math.max(1, b.priceCents));
      const terms = { cancellable, late, feeCents: fee, feeTaxCents: feeTax, feeTotalCents: fee + feeTax, releasedCents: b.heldCents - fee - feeTax, freeUntil: new Date(freeUntil).toISOString(), windowHours: state.terms.windowHours, feeBps: state.terms.feeBps, feeFixedCents: state.terms.feeCents };
      if (method === 'GET' && m[2] === 'cancellation') return ctx.answer(200, terms);
      if (method === 'POST' && m[2] === 'cancel') {
        if (!cancellable) return ctx.answer(409, { code: 'job_state', detail: 'The job has started, so it can’t be cancelled.' });
        Object.assign(b, { state: 'cancelled', cancelled: { at: iso(), feeCents: fee, feeTaxCents: feeTax } });
        return ctx.answer(200, b);
      }
    }
    return undefined;
  };
}
