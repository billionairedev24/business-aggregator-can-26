import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';

/**
 * After the job or the delivery (mobile gaps part 2), personal — loaded in the browser only:
 *   GET   /api/v1/me/reviews/{kind}/{id}            what can be reviewed (kind booking | order | food)
 *   POST  /api/v1/me/reviews                        {kind, id, merchantId?, rating, tags, text?}
 *   PATCH /api/v1/me/reviews/{reviewId}             within 24 h, until the business replies
 *   GET   /api/v1/me/orders/{id}/tips               the courier's tips and whether one can be added
 *   POST  /api/v1/me/orders/{id}/tips               {kind: amount | percent, value} (Idempotency-Key)
 *   POST  /api/v1/me/orders/{id}/tips/{tip}/confirm the card authorized it
 *   GET   /api/v1/me/bookings/{id}/eta              minutes away while the provider is on the way
 */
export const Review = z.object({
  id: z.string(), merchantId: z.string(), rating: z.number().int(), tags: z.array(z.string()), text: z.string().nullish(),
  screened: z.boolean(), createdAt: z.string(), editUntil: z.string().nullish(), editedAt: z.string().nullish(),
  reply: z.string().nullish(), hidden: z.boolean(),
});
export type Review = z.infer<typeof Review>;
export const Target = z.object({
  merchantId: z.string(), merchantName: z.string(), slug: z.string().nullish(), jobLabel: z.string(),
  status: z.enum(['open', 'reviewed', 'not_yet', 'closed']), review: Review.nullish(),
});
export type Target = z.infer<typeof Target>;
export const ReviewContext = z.object({ kind: z.string(), id: z.string(), ref: z.string().nullish(), reviewBy: z.string().nullish(), targets: z.array(Target) });
export type ReviewContext = z.infer<typeof ReviewContext>;

export type ReviewKind = 'booking' | 'order' | 'food';
export const reviewQuery = (kind: ReviewKind, id: string) => queryOptions({
  queryKey: ['me', 'reviews', kind, id],
  queryFn: () => http(`/api/v1/me/reviews/${kind}/${encodeURIComponent(id)}`, {}, ReviewContext),
});
export const postReview = (body: { kind: ReviewKind; id: string; merchantId: string; rating: number; tags: string[]; text?: string }) =>
  http('/api/v1/me/reviews', { method: 'POST', body }, Review);
export const editReview = (reviewId: string, body: { rating: number; tags: string[]; text?: string }) =>
  http(`/api/v1/me/reviews/${encodeURIComponent(reviewId)}`, { method: 'PATCH', body }, Review);

export const Tip = z.object({
  id: z.string(), orderId: z.string(), amountCents: z.number().int(), source: z.enum(['checkout', 'after_delivery']),
  state: z.string(), courierUserId: z.string().nullish(), createdAt: z.string(), clientSecret: z.string().nullish(),
});
export type Tip = z.infer<typeof Tip>;
export const Tips = z.object({ items: z.array(Tip), canTip: z.boolean(), courierFirstName: z.string().nullish() });
export const TipStarted = z.object({ tip: Tip, provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish() });
export type TipStarted = z.infer<typeof TipStarted>;
export const tipsQuery = (orderId: string) => queryOptions({
  queryKey: ['me', 'tips', orderId],
  queryFn: () => http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/tips`, {}, Tips),
});
export const startTip = (orderId: string, body: { kind: 'amount' | 'percent'; value: number }, idempotencyKey: string) =>
  http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/tips`, { method: 'POST', body, idempotencyKey }, TipStarted);
export const confirmTip = (orderId: string, tipId: string) =>
  http(`/api/v1/me/orders/${encodeURIComponent(orderId)}/tips/${encodeURIComponent(tipId)}/confirm`, { method: 'POST' }, Tip);

export const Eta = z.object({
  state: z.string(), sharing: z.boolean(), minutesAway: z.number().int().nullish(), kmAway: z.number().nullish(),
  updatedAt: z.string().nullish(), method: z.string(),
});
export type Eta = z.infer<typeof Eta>;
/** Asked again every 30 s while the provider is on the way (the app's rhythm, S-100). */
export const etaQuery = (bookingId: string) => queryOptions({
  queryKey: ['me', 'booking-eta', bookingId],
  queryFn: () => http(`/api/v1/me/bookings/${encodeURIComponent(bookingId)}/eta`, {}, Eta),
  refetchInterval: q => (q.state.data?.state === 'en_route' || q.state.data?.state === 'confirmed' ? 30_000 : false),
});

/** The praise tags customers pick from (trust.domain.ReviewRules.TAGS), per kind. */
export const TAGS: Record<ReviewKind, readonly string[]> = {
  booking: ['on_time', 'clear_explanation', 'fair_price', 'clean_work', 'extra_mile', 'friendly'],
  order: ['on_time', 'well_packed', 'as_described', 'fair_price', 'friendly'],
  food: ['hot_on_arrival', 'tasty', 'generous_portions', 'well_packed', 'on_time'],
};
