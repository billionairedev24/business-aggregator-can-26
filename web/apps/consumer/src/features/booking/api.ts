import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';

/**
 * The booking wizard's api (S-55, module `hire`):
 *   GET  /api/v1/public/providers/{slug}/slots?serviceId&from&days  — the live calendar (public)
 *   POST /api/v1/me/bookings/holds {slug, serviceId, startsAt, hours?}  — hold the slot 10 minutes
 *   POST /api/v1/me/bookings/checkout (Idempotency-Key, X-Step-Up)  — price it, open the escrow payment
 *   POST /api/v1/me/bookings/holds/{holdId}/confirm (Idempotency-Key)  — the card is authorized: book it
 *   GET  /api/v1/me/bookings/{bookingId}  — the confirmation
 */
export const Slot = z.object({ startsAt: z.string(), free: z.boolean() });
export const CalendarDay = z.object({ date: z.string(), closed: z.string().nullish(), free: z.number().int(), slots: z.array(Slot) });
export type CalendarDay = z.infer<typeof CalendarDay>;
/**
 * S-153: the provider's late-cancellation terms (Availability › Booking rules within the region's limits): free until
 * `windowHours` before the start, then `feeBps` of the price or a fixed `feeCents`.
 */
export const CancellationPolicy = z.object({ windowHours: z.number().int(), feeBps: z.number().int(), feeCents: z.number().int() });
export type CancellationPolicy = z.infer<typeof CancellationPolicy>;
/** `timeZone`: the business's (region model) — the days and times are read in it. */
export const Calendar = z.object({
  serviceId: z.string(), durationMin: z.number().int(), days: z.array(CalendarDay), timeZone: z.string().optional(),
  cancellation: CancellationPolicy.optional(),
});
export type Calendar = z.infer<typeof Calendar>;

export const Hold = z.object({ holdId: z.string(), bookingId: z.string(), startsAt: z.string(), endsAt: z.string(), expiresAt: z.string() });
export type Hold = z.infer<typeof Hold>;

export const Confirmation = z.object({
  bookingId: z.string(), ref: z.string(), providerName: z.string(), providerSlug: z.string(), memberFirstName: z.string().nullish(),
  title: z.string(), type: z.string(), startsAt: z.string(), endsAt: z.string(), addressLine: z.string().nullish(),
  priceCents: z.number(), taxCents: z.number(), heldCents: z.number(), freeCancelUntil: z.string().nullish(),
  // S-100: how the job went so far (the booking page, mobile gaps part 2)
  state: z.string().optional(), timeZone: z.string().optional(),
  // S-153: the job's photos, the terms it was booked under, a deposit quote's balance, the cancellation
  photos: z.array(z.object({ id: z.string(), fileName: z.string() })).optional(),
  cancellation: CancellationPolicy.nullish(),
  balance: z.object({
    state: z.enum(['none', 'pending', 'held', 'failed', 'cancelled']).catch('none'), cents: z.number(), taxCents: z.number(),
    dueAt: z.string().nullish(), nextAt: z.string().nullish(), attempts: z.number().int(), failure: z.string().nullish(),
  }).optional(),
  cancelled: z.object({ at: z.string(), feeCents: z.number(), feeTaxCents: z.number() }).nullish(),
});
export type Confirmation = z.infer<typeof Confirmation>;

export const Checkout = z.object({
  holdId: z.string(), bookingId: z.string(), priceCents: z.number(), taxCents: z.number(), totalCents: z.number(),
  status: z.string(), paymentIntent: z.string().nullish(), clientSecret: z.string().nullish(),
  provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish(), booking: Confirmation.nullish(),
  discountCents: z.number().int().optional(), pointsCents: z.number().int().optional(), promoCode: z.string().nullish(),
});
export type Checkout = z.infer<typeof Checkout>;

export const calendarQuery = (slug: string, serviceId: string, from: string, days = 7) => queryOptions({
  queryKey: ['booking', 'calendar', slug, serviceId, from, days],
  queryFn: () => http(`/api/v1/public/providers/${encodeURIComponent(slug)}/slots?serviceId=${encodeURIComponent(serviceId)}&from=${from}&days=${days}`, {}, Calendar),
  staleTime: 15_000,
});

export const bookingQuery = (bookingId: string) => queryOptions({
  queryKey: ['booking', 'confirmation', bookingId],
  queryFn: () => http(`/api/v1/me/bookings/${encodeURIComponent(bookingId)}`, {}, Confirmation),
});

export const holdSlot = (body: { slug: string; serviceId: string; startsAt: string; hours?: number }) =>
  http('/api/v1/me/bookings/holds', { method: 'POST', body }, Hold);

export const releaseHold = (holdId: string) => http(`/api/v1/me/bookings/holds/${encodeURIComponent(holdId)}`, { method: 'DELETE' });

export const startCheckout = (body: unknown, idempotencyKey: string, stepUp?: string) =>
  http('/api/v1/me/bookings/checkout', { method: 'POST', body, idempotencyKey, headers: stepUp ? { 'x-step-up': stepUp } : undefined }, Checkout);

/** Mobile gaps part 2: the price with a promo code and points (`POST /api/v1/me/bookings/price`; 422 on promoCode). */
export const BookingPrice = z.object({
  priceCents: z.number().int(), discountCents: z.number().int(), taxCents: z.number().int(), points: z.number().int(),
  pointsCents: z.number().int(), pointsAvailable: z.number().int(), totalCents: z.number().int(), promoCode: z.string().nullish(),
});
export type BookingPrice = z.infer<typeof BookingPrice>;
export const priceQuery = (body: { holdId: string; serviceId: string; hours?: number; promoCode?: string; usePoints: boolean }) => queryOptions({
  queryKey: ['booking', 'price', body],
  queryFn: () => http('/api/v1/me/bookings/price', { method: 'POST', body }, BookingPrice),
  retry: false,
  staleTime: 30_000,
});

export const confirmBooking = (holdId: string, idempotencyKey: string) =>
  http(`/api/v1/me/bookings/holds/${encodeURIComponent(holdId)}/confirm`, { method: 'POST', idempotencyKey }, Confirmation);

/**
 * S-153 — after the booking:
 *   GET  /api/v1/me/bookings/{id}/cancellation               — what cancelling now costs
 *   POST /api/v1/me/bookings/{id}/cancel (Idempotency-Key)    — cancel: the late fee is kept from the hold, the rest released
 *   POST /api/v1/me/bookings/{id}/balance (Idempotency-Key, X-Step-Up) — pay a deposit quote's balance by hand
 *   POST /api/v1/me/bookings/{id}/balance/confirm (Idempotency-Key)    — the card is authorized: hold it
 */
export const CancelTerms = z.object({
  cancellable: z.boolean(), late: z.boolean(), feeCents: z.number(), feeTaxCents: z.number(), feeTotalCents: z.number(),
  releasedCents: z.number(), freeUntil: z.string().nullish(), windowHours: z.number().int(), feeBps: z.number().int(),
  feeFixedCents: z.number().int(),
});
export type CancelTerms = z.infer<typeof CancelTerms>;
export const cancellationQuery = (bookingId: string) => queryOptions({
  queryKey: ['booking', 'cancellation', bookingId],
  queryFn: () => http(`/api/v1/me/bookings/${encodeURIComponent(bookingId)}/cancellation`, {}, CancelTerms),
  staleTime: 0,
});
export const cancelBooking = (bookingId: string, idempotencyKey: string) =>
  http(`/api/v1/me/bookings/${encodeURIComponent(bookingId)}/cancel`, { method: 'POST', idempotencyKey }, Confirmation);

export const BalancePayment = z.object({
  bookingId: z.string(), amountCents: z.number(), taxCents: z.number(), totalCents: z.number(), status: z.string(),
  paymentIntent: z.string().nullish(), clientSecret: z.string().nullish(), provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish(),
});
export type BalancePayment = z.infer<typeof BalancePayment>;
export const payBalance = (bookingId: string, idempotencyKey: string, opts: { paymentMethodId?: string; stepUp?: string } = {}) =>
  http(`/api/v1/me/bookings/${encodeURIComponent(bookingId)}/balance`, {
    method: 'POST', body: opts.paymentMethodId ? { paymentMethodId: opts.paymentMethodId } : {}, idempotencyKey,
    headers: opts.stepUp ? { 'x-step-up': opts.stepUp } : undefined,
  }, BalancePayment);
export const confirmBalance = (bookingId: string, idempotencyKey: string) =>
  http(`/api/v1/me/bookings/${encodeURIComponent(bookingId)}/balance/confirm`, { method: 'POST', idempotencyKey }, Confirmation);

/** "Free until 24 h before, then 50 % of the price" — the words of a policy (both apps). */
export const policyFee = (p: { feeBps: number; feeCents: number }, money: (c: number) => string, pct: (bps: number) => string) =>
  p.feeCents > 0 ? money(p.feeCents) : p.feeBps > 0 ? pct(p.feeBps) : null;
