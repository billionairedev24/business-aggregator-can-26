import { REVIEW_TAGS, type MyReview, type ReviewKind } from '../api/aftercare';
import type { FixtureArea, FixtureContext } from './context';
import type { ServicesFixtureState } from './services';
import { SHOPS, type ShopFixtureState } from './shop';

/**
 * Mobile gaps part 2 in the fixture backend: reviews of a job or an order (`/me/reviews…`) and the courier's tip after
 * the delivery (`/me/orders/{id}/tips…`), with the api's rules and messages — once per business and job, within 30
 * days, 24 hours to change it; tips from $1 up to 7 days after the delivery, one per order.
 */
export interface AftercareFixtureState {
  reviews: Map<string, MyReview & { kind: ReviewKind; refId: string }>;
}

export const newAftercareState = (): AftercareFixtureState => ({ reviews: new Map() });

const DAY = 86_400_000;
const signed = (headers: Record<string, string>) => (headers.authorization ?? '').startsWith('DPoP ');
const invalid = (field: string, message: string) => ({ errors: [{ field, rule: field, message }] });

export function aftercareFixtures(ctx: FixtureContext, state: AftercareFixtureState, shop: ShopFixtureState, services: ServicesFixtureState): FixtureArea {
  const shopName = (id: string) => SHOPS.find((s) => s.merchantId === id)?.name ?? id;

  /** The businesses to review for a job or an order, each with its state. */
  function targets(kind: ReviewKind, id: string) {
    const review = (merchantId: string) => [...state.reviews.values()].find((r) => r.kind === kind && r.refId === id && r.merchantId === merchantId) ?? null;
    if (kind === 'booking') {
      const b = services.bookings.get(id);
      if (!b) return null;
      const done = b.state === 'completed' || b.state === 'signed_off';
      const r = review(b.merchantId);
      return [{ merchantId: b.merchantId, merchantName: b.providerName, slug: b.providerSlug, jobLabel: b.title, status: r ? 'reviewed' : done ? 'open' : 'not_yet', review: r }];
    }
    const o = shop.orders.get(id);
    if (!o) return null;
    return [...new Set(o.lines.map((l) => l.merchantId))].map((merchantId) => {
      const r = review(merchantId);
      return { merchantId, merchantName: shopName(merchantId), slug: null, jobLabel: `Order ${o.ref}`, status: r ? 'reviewed' : o.deliveredAt ? 'open' : 'not_yet', review: r };
    });
  }

  const check = (kind: ReviewKind, body: Record<string, unknown>) => {
    const errors = [];
    const rating = Number(body.rating);
    if (!(rating >= 1 && rating <= 5)) errors.push(invalid('rating', 'Choose from 1 to 5 stars.').errors[0]!);
    const text = typeof body.text === 'string' ? body.text.trim() : '';
    if (text && text.length < 10) errors.push(invalid('text', 'Write at least 10 characters, or leave the review empty.').errors[0]!);
    if (text.length > 1000) errors.push(invalid('text', 'Keep your review under 1,000 characters.').errors[0]!);
    const tags = (body.tags as string[] | undefined) ?? [];
    if (tags.length > 5 || tags.some((x) => !REVIEW_TAGS[kind].includes(x))) errors.push(invalid('tags', 'Choose up to 5 of the tags offered.').errors[0]!);
    // the api masks contact details and swearing (Redaction + the word list)
    const masked = text.replace(/\S+@\S+\.\S+/g, '****').replace(/\+?\d[\d ()-]{8,}\d/g, '****');
    return { errors, rating, tags, text: masked || null, screened: masked !== text };
  };

  return (req) => {
    const { path, method } = req;
    if (!path.startsWith('/me/reviews') && !/^\/me\/orders\/[^/]+\/tips/.test(path)) return undefined;
    if (!signed(req.headers)) return ctx.answer(401, { code: 'unauthorized', detail: 'Not signed in, or the token expired.' });
    let m: RegExpExecArray | null;

    // ── reviews ──
    if (method === 'GET' && (m = /^\/me\/reviews\/(booking|order|food)\/([^/]+)$/.exec(path))) {
      const kind = m[1] as ReviewKind;
      const id = decodeURIComponent(m[2]!);
      const list = targets(kind, id);
      if (!list) return ctx.answer(404, { code: 'not_found', detail: 'Nothing to review here.' });
      return ctx.answer(200, { kind, id, ref: null, reviewBy: new Date(ctx.now() + 30 * DAY).toISOString(), targets: list });
    }
    if (method === 'POST' && path === '/me/reviews') {
      const kind = String(req.body.kind) as ReviewKind;
      const id = String(req.body.id ?? '');
      const target = (targets(kind, id) ?? []).find((x) => x.merchantId === req.body.merchantId);
      if (!target || target.status === 'not_yet') return ctx.answer(409, { code: 'not_reviewable', detail: 'You can review once it’s done.' });
      if (target.status === 'reviewed') return ctx.answer(409, { code: 'already_reviewed', detail: "You've already reviewed this." });
      const c = check(kind, req.body);
      if (c.errors.length) return ctx.answer(422, { errors: c.errors });
      const now = ctx.now();
      const review = {
        id: `rev-${state.reviews.size + 1}`, merchantId: target.merchantId, rating: c.rating, tags: c.tags, text: c.text, screened: c.screened,
        createdAt: new Date(now).toISOString(), editUntil: new Date(now + DAY).toISOString(), editedAt: null, reply: null, hidden: false, kind, refId: id,
      };
      state.reviews.set(review.id, review);
      const { kind: _k, refId: _r, ...view } = review;
      return ctx.answer(201, view);
    }
    if (method === 'PATCH' && (m = /^\/me\/reviews\/([^/]+)$/.exec(path))) {
      const review = state.reviews.get(decodeURIComponent(m[1]!));
      if (!review) return ctx.answer(404, { code: 'not_found', detail: 'No such review.' });
      if (review.reply || !review.editUntil || Date.parse(review.editUntil) <= ctx.now()) return ctx.answer(409, { code: 'edit_closed', detail: "This review can't be changed any more." });
      const c = check(review.kind, req.body);
      if (c.errors.length) return ctx.answer(422, { errors: c.errors });
      Object.assign(review, { rating: c.rating, tags: c.tags, text: c.text, screened: c.screened, editedAt: new Date(ctx.now()).toISOString() });
      const { kind: _k, refId: _r, ...view } = review;
      return ctx.answer(200, view);
    }

    // ── the courier's tip after the delivery ──
    m = /^\/me\/orders\/([^/]+)\/tips(?:\/([^/]+)\/confirm)?$/.exec(path);
    if (!m) return undefined;
    const o = shop.orders.get(decodeURIComponent(m[1]!));
    if (!o) return ctx.answer(404, { code: 'not_found', detail: 'We couldn’t find this order.' });
    const tips = shop.tips.get(o.orderId) ?? [];
    const after = tips.find((x) => x.source === 'after_delivery' && x.state !== 'canceled');
    const canTip = !!o.deliveredAt && ctx.now() - o.deliveredAt <= 7 * DAY && !(after && after.state !== 'pending');
    if (method === 'GET' && !m[2]) return ctx.answer(200, { items: tips, canTip, courierFirstName: 'Robin' });
    if (method === 'POST' && !m[2]) {
      const key = req.headers['idempotency-key'];
      if (!key) return ctx.answer(422, invalid('Idempotency-Key', 'Idempotency-Key header is required.'));
      const replay = shop.idempotent.get(`tip:${key}`);
      if (replay) return ctx.answer(201, replay, { 'Idempotent-Replayed': 'true' });
      if (!o.deliveredAt) return ctx.answer(409, { code: 'not_delivered', detail: 'You can tip once the courier has delivered.' });
      if (!canTip) return ctx.answer(409, { code: 'already_tipped', detail: "You've already tipped for this delivery." });
      const kind = String(req.body.kind);
      const value = Number(req.body.value);
      const items = o.lines.reduce((n, l) => n + l.unitCents * l.qty, 0);
      const amount = kind === 'percent' ? Math.round((items * value) / 100) : value;
      if (!(amount >= 100)) return ctx.answer(422, invalid('value', 'Tips start at $1.00.'));
      if (amount > 10_000 || (kind === 'percent' && value > 30)) return ctx.answer(422, invalid('value', 'Choose a tip between $0 and $100, or up to 30 %.'));
      const stripe = shop.provider === 'stripe';
      const tip = {
        id: `tip-${o.orderId}-${tips.length + 1}`, orderId: o.orderId, amountCents: amount, source: 'after_delivery' as const, state: 'pending',
        courierUserId: null, createdAt: new Date(ctx.now()).toISOString(), clientSecret: stripe ? `pi_tip_${o.orderId}_secret_fixture` : null,
      };
      shop.tips.set(o.orderId, [...tips.filter((x) => x !== after), tip]);
      const answer = { tip, provider: shop.provider, publishableKey: stripe ? 'pk_test_fixture' : null };
      shop.idempotent.set(`tip:${key}`, answer);
      return ctx.answer(201, answer);
    }
    if (method === 'POST' && m[2]) {
      const tip = tips.find((x) => x.id === decodeURIComponent(m![2]!));
      if (!tip) return ctx.answer(404, { code: 'not_found', detail: 'No such tip.' });
      if (tip.state === 'pending') Object.assign(tip, { state: 'allocated', courierUserId: 'courier-robin', clientSecret: null });
      return ctx.answer(200, tip);
    }
    return undefined;
  };
}
