import type { ApiClient } from '@northline/mobile-kit';

/**
 * Mobile gaps part 2: what comes after the job or the delivery, and the checkout's promo code, points and tip — the
 * consumer web's endpoints (web/apps/consumer/src/features/aftercare/api.ts, …/promotions):
 *
 *   GET   /me/reviews/{kind}/{id}            what can be reviewed (kind booking | order | food), and the review left
 *   POST  /me/reviews                        {kind, id, merchantId, rating, tags, text?} — once per business and job
 *   PATCH /me/reviews/{reviewId}             within 24 h, until the business replies
 *   GET   /me/orders/{id}/tips               the courier's tips and whether one can still be added (7 days)
 *   POST  /me/orders/{id}/tips               {kind: amount | percent, value} (Idempotency-Key) → a PaymentIntent
 *   POST  /me/orders/{id}/tips/{tip}/confirm the card authorized it
 *   GET   /me/bookings/{id}/eta              minutes away while the provider shares their position (never the position)
 *   POST  /me/bookings/price                 a booking's price with a promo code and points (422 on promoCode)
 *
 * Validation messages come back in the app's language (the client sends Accept-Language).
 */
export type ReviewKind = 'booking' | 'order' | 'food';
export interface MyReview {
  id: string; merchantId: string; rating: number; tags: string[]; text?: string | null; screened: boolean; createdAt: string;
  editUntil?: string | null; editedAt?: string | null; reply?: string | null; hidden: boolean;
}
export interface ReviewTarget { merchantId: string; merchantName: string; slug?: string | null; jobLabel: string; status: 'open' | 'reviewed' | 'not_yet' | 'closed'; review?: MyReview | null }
export interface ReviewContext { kind: string; id: string; ref?: string | null; reviewBy?: string | null; targets: ReviewTarget[] }
export interface ReviewDraft { kind: ReviewKind; id: string; merchantId: string; rating: number; tags: string[]; text?: string }

/** The praise tags offered per kind (trust.domain.ReviewRules.TAGS). */
export const REVIEW_TAGS: Record<ReviewKind, readonly string[]> = {
  booking: ['on_time', 'clear_explanation', 'fair_price', 'clean_work', 'extra_mile', 'friendly'],
  order: ['on_time', 'well_packed', 'as_described', 'fair_price', 'friendly'],
  food: ['hot_on_arrival', 'tasty', 'generous_portions', 'well_packed', 'on_time'],
};

/** The checkout's tip choice (as food's, S-57): none, an amount in cents, or a percentage of the items. */
export interface TipChoice { kind: 'none' | 'amount' | 'percent'; value: number }
export const TIP_CHOICES: readonly TipChoice[] = [
  { kind: 'none', value: 0 }, { kind: 'amount', value: 200 }, { kind: 'amount', value: 400 }, { kind: 'percent', value: 15 }, { kind: 'amount', value: 600 },
];
export interface CourierTip {
  id: string; orderId: string; amountCents: number; source: 'checkout' | 'after_delivery'; state: string;
  courierUserId?: string | null; createdAt: string; clientSecret?: string | null;
}
export interface Tips { items: CourierTip[]; canTip: boolean; courierFirstName?: string | null }
export interface TipStarted { tip: CourierTip; provider: 'stripe' | 'fake'; publishableKey?: string | null }

export interface VisitEta { state: string; sharing: boolean; minutesAway?: number | null; kmAway?: number | null; updatedAt?: string | null; method: string }
export interface PriceAsk { holdId: string; serviceId: string; hours?: number; promoCode?: string; usePoints: boolean }
export interface BookingPrice {
  priceCents: number; discountCents: number; taxCents: number; points: number; pointsCents: number; pointsAvailable: number;
  totalCents: number; promoCode?: string | null;
}

const e = encodeURIComponent;

export const aftercareApi = (api: ApiClient) => ({
  reviewContext: (kind: ReviewKind, id: string) => api.get<ReviewContext>(`/me/reviews/${kind}/${e(id)}`),
  postReview: (draft: ReviewDraft) => api.post<MyReview>('/me/reviews', { json: draft }),
  editReview: (reviewId: string, body: { rating: number; tags: string[]; text?: string }) => api.patch<MyReview>(`/me/reviews/${e(reviewId)}`, { json: body }),
  tips: (orderId: string) => api.get<Tips>(`/me/orders/${e(orderId)}/tips`),
  startTip: (orderId: string, body: { kind: 'amount' | 'percent'; value: number }, key: string) =>
    api.post<TipStarted>(`/me/orders/${e(orderId)}/tips`, { json: body, idempotencyKey: key }),
  confirmTip: (orderId: string, tipId: string) => api.post<CourierTip>(`/me/orders/${e(orderId)}/tips/${e(tipId)}/confirm`),
  eta: (bookingId: string) => api.get<VisitEta>(`/me/bookings/${e(bookingId)}/eta`),
  bookingPrice: (ask: PriceAsk) => api.post<BookingPrice>('/me/bookings/price', { json: ask }),
});

export type AftercareApi = ReturnType<typeof aftercareApi>;
