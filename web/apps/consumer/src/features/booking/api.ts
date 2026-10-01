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
export const Calendar = z.object({ serviceId: z.string(), durationMin: z.number().int(), days: z.array(CalendarDay) });
export type Calendar = z.infer<typeof Calendar>;

export const Hold = z.object({ holdId: z.string(), bookingId: z.string(), startsAt: z.string(), endsAt: z.string(), expiresAt: z.string() });
export type Hold = z.infer<typeof Hold>;

export const Confirmation = z.object({
  bookingId: z.string(), ref: z.string(), providerName: z.string(), providerSlug: z.string(), memberFirstName: z.string().nullish(),
  title: z.string(), type: z.string(), startsAt: z.string(), endsAt: z.string(), addressLine: z.string().nullish(),
  priceCents: z.number(), taxCents: z.number(), heldCents: z.number(), freeCancelUntil: z.string().nullish(),
});
export type Confirmation = z.infer<typeof Confirmation>;

export const Checkout = z.object({
  holdId: z.string(), bookingId: z.string(), priceCents: z.number(), taxCents: z.number(), totalCents: z.number(),
  status: z.string(), paymentIntent: z.string().nullish(), clientSecret: z.string().nullish(),
  provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish(), booking: Confirmation.nullish(),
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

export const confirmBooking = (holdId: string, idempotencyKey: string) =>
  http(`/api/v1/me/bookings/holds/${encodeURIComponent(holdId)}/confirm`, { method: 'POST', idempotencyKey }, Confirmation);
