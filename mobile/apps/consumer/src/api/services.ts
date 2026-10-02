import type { ApiClient } from '@northline/mobile-kit';

/**
 * Journey C's api (S-100): the Services journey of the consumer web, called directly with the app's DPoP token.
 *
 *   GET  /public/services?lang                         the groups and categories (S-53)
 *   GET  /public/services/{slug}?lang                  one category: kind, vehicle, quoteable
 *   GET  /public/services/{slug}/providers?lat&lng&city&lang   who covers the customer, most trusted first
 *   GET  /public/providers/{slug}?lang                 the provider page: trust figures, services, time zone (S-54)
 *   GET  /public/providers/{slug}/reviews?offset&limit
 *   GET  /public/providers/{slug}/slots?serviceId&from&days    the live calendar, in the business's time zone (S-55)
 *   POST /me/bookings/holds                            hold the slot 10 minutes
 *   DELETE /me/bookings/holds/{id}
 *   POST /me/bookings/checkout       (Idempotency-Key, X-Step-Up)   price it, open the escrow payment
 *   POST /me/bookings/holds/{id}/confirm (Idempotency-Key)          the card is authorized: book it
 *   GET  /me/bookings/{id}                             the booking and how the job went so far (S-100)
 *   POST /me/bookings/{id}/sign-off                    "Release payment" (S-100)
 *   POST /me/quote-requests                            "Ask for a quote" (S-56)
 *   GET /me/favourites, PUT|DELETE /me/favourites/{businessId}   (S-58)
 *   GET  /me/payment-methods                           saved cards (S-59): pay with one, or a new one in Stripe's sheet
 *   GET  /me/activity, GET|PUT /me/notifications       the inbox and quiet hours (S-58, S-61)
 *
 * Public reads go out anonymously for guests (`auth: 'optional'`). Times are instants; the screens show them in the
 * business's `timeZone` (never the phone's).
 */
export type Names = Record<string, string>;
export type ServiceKind = 'visit' | 'home' | 'event' | 'appointment' | 'consult';
export type PricingMode = 'fixed' | 'hourly' | 'quote';

export interface ServiceItem { slug: string; names: Names; kind: ServiceKind; providers: number }
export interface ServiceGroup { id: string; key: string; names: Names; note?: string | null; items: ServiceItem[] }
export interface Landing { liveCategories: number; providers: number; provinces: string[]; groups: ServiceGroup[] }

export interface Category {
  id: string;
  slug: string;
  names: Names;
  kind: ServiceKind;
  vehicle: boolean;
  providers: number;
  quoteable: boolean;
}

export interface ProviderCard {
  merchantId: string;
  slug: string;
  name: string;
  tier: string;
  brandColor: string;
  blurb?: string | null;
  rating: number;
  reviewCount: number;
  onTimePct?: number | null;
  disputePct?: number | null;
  rebookPct?: number | null;
  fromCents?: number | null;
  pricingMode: PricingMode;
  instantBook: boolean;
  nextAvailable?: string | null;
  zones: string[];
  timeZone: string;
}
export interface Providers { categorySlug: string; kind: ServiceKind; area?: string | null; city?: string | null; items: ProviderCard[] }

export interface Review { id: string; rating: number; text?: string | null; author?: string | null; jobLabel?: string | null; refType: string; createdAt: string; reply?: string | null }
export interface ReviewPage { items: Review[]; nextOffset?: number | null }

export interface ProviderService {
  id: string;
  name: string;
  included?: string | null;
  pricingMode: PricingMode;
  priceCents?: number | null;
  durationMin: number;
  instantBook: boolean;
  categorySlug?: string | null;
  kind: ServiceKind;
}
export interface ProviderPage {
  merchantId: string;
  slug: string;
  name: string;
  tier: string;
  city?: string | null;
  since: string;
  verifiedFacts: string[];
  rating: number;
  reviewCount: number;
  onTimePct?: number | null;
  disputePct?: number | null;
  rebookPct?: number | null;
  kind: ServiceKind;
  category?: { id: string; slug: string; names: Names } | null;
  vehicle: boolean;
  quoteable: boolean;
  services: ProviderService[];
  zones: string[];
  nextAvailable?: string | null;
  taxBps: number;
  reviews: ReviewPage;
  timeZone: string;
}

export interface Slot { startsAt: string; free: boolean }
export interface Day { date: string; closed?: string | null; free: number; slots: Slot[] }
export interface Calendar { serviceId: string; durationMin: number; days: Day[]; timeZone: string }
export interface Hold { holdId: string; bookingId: string; startsAt: string; endsAt: string; expiresAt: string }

export type BookingState = 'requested' | 'confirmed' | 'en_route' | 'on_site' | 'completed' | 'signed_off' | 'disputed' | 'cancelled';
export interface Step { type: 'en_route' | 'on_site' | 'completed' | 'signed_off'; at: string }
export interface Booking {
  bookingId: string;
  ref: string;
  providerName: string;
  providerSlug: string;
  memberFirstName?: string | null;
  title: string;
  type: ServiceKind;
  startsAt: string;
  endsAt: string;
  addressLine?: string | null;
  priceCents: number;
  taxCents: number;
  heldCents: number;
  freeCancelUntil?: string | null;
  merchantId: string;
  state: BookingState;
  timeZone: string;
  steps: Step[];
  report?: string | null;
  photoCount: number;
  releasesAt?: string | null;
}

export type PaymentProvider = 'stripe' | 'fake';
export interface Checkout {
  holdId: string;
  bookingId: string;
  priceCents: number;
  taxCents: number;
  totalCents: number;
  /** `requires_action` / `requires_payment_method` (confirm the card with Stripe), `authorized`, or `confirmed` (free: booked). */
  status: string;
  paymentIntent?: string | null;
  clientSecret?: string | null;
  provider: PaymentProvider;
  publishableKey?: string | null;
  booking?: Booking | null;
}

/** What the booking wizard sends (the server's BookingRequest; validation messages come back as 422s). */
export interface BookingRequest {
  holdId: string;
  serviceId: string;
  description?: string;
  vehicle?: { year?: string; make?: string; model?: string; plate?: string };
  addressLine?: string;
  unit?: string;
  area?: string;
  spot?: string;
  accessNote?: string;
  agreePolicies: boolean;
  agreeTerms: boolean;
}

export interface QuoteAsk {
  /** The category's slug. */
  category: string;
  /** Up to three providers' slugs. */
  providers: string[];
  description: string;
  vehicle?: { year: string; make: string; model: string };
  area?: string;
}
export interface Requested { requestId: string; ref: string; respondBy: string; expiresAt: string; providers: number }

export interface ActivityItem {
  id: string;
  kind: 'order' | 'food' | 'booking' | 'quote';
  ref?: string | null;
  title: string;
  with: string[];
  when: string;
  whenEnd?: string | null;
  amountCents: number;
  status: string;
  tone: 'accent' | 'neutral' | 'accent-2';
  active: boolean;
  action: string;
}
export interface SavedCard { id: string; brand: string; last4: string; expMonth: number; expYear: number; isDefault: boolean }
export interface PaymentMethods { provider: PaymentProvider; publishableKey?: string | null; items: SavedCard[] }
export interface NotificationPrefs { quietOn: boolean; quietFrom: string; quietTo: string }

const q = (p: Record<string, string | number | undefined>) =>
  Object.entries(p)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&');
const e = encodeURIComponent;

/** An answer with a body (the client answers null for 204 / empty bodies). */
const need = async <T>(p: Promise<T | null>): Promise<T> => {
  const v = await p;
  if (v === null) throw new Error('The api answered without a body.');
  return v;
};

export const servicesApi = (api: ApiClient) => ({
  landing: (lang: string) => need(api.get<Landing>(`/public/services?${q({ lang })}`, { auth: 'optional' })),
  category: (slug: string, lang: string) => need(api.get<Category>(`/public/services/${e(slug)}?${q({ lang })}`, { auth: 'optional' })),
  providers: (slug: string, place: { lat?: number; lng?: number; city?: string }, lang: string) => {
    const point = place.lat !== undefined && place.lng !== undefined ? { lat: place.lat.toFixed(5), lng: place.lng.toFixed(5) } : {};
    return need(api.get<Providers>(`/public/services/${e(slug)}/providers?${q({ ...point, city: place.city, lang })}`, { auth: 'optional' }));
  },
  provider: (slug: string, lang: string) => need(api.get<ProviderPage>(`/public/providers/${e(slug)}?${q({ lang })}`, { auth: 'optional' })),
  reviews: (slug: string, offset: number) =>
    need(api.get<ReviewPage>(`/public/providers/${e(slug)}/reviews?${q({ offset, limit: 10 })}`, { auth: 'optional' })),
  slots: (slug: string, serviceId: string, days = 7) =>
    need(api.get<Calendar>(`/public/providers/${e(slug)}/slots?${q({ serviceId, days })}`, { auth: 'optional' })),
  hold: (slug: string, serviceId: string, startsAt: string) => need(api.post<Hold>('/me/bookings/holds', { json: { slug, serviceId, startsAt } })),
  release: (holdId: string) => api.delete<void>(`/me/bookings/holds/${e(holdId)}`),
  checkout: (body: BookingRequest, idempotencyKey: string, stepUp?: string) =>
    need(api.post<Checkout>('/me/bookings/checkout', { json: body, idempotencyKey, headers: stepUp ? { 'X-Step-Up': stepUp } : undefined })),
  confirm: (holdId: string, idempotencyKey: string) => need(api.post<Booking>(`/me/bookings/holds/${e(holdId)}/confirm`, { idempotencyKey })),
  booking: (id: string) => need(api.get<Booking>(`/me/bookings/${e(id)}`)),
  signOff: (id: string) => need(api.post<Booking>(`/me/bookings/${e(id)}/sign-off`)),
  askQuote: (body: QuoteAsk) => need(api.post<Requested>('/me/quote-requests', { json: body })),
  favourites: () => need(api.get<{ items: Array<{ merchantId: string }> }>('/me/favourites')),
  favourite: (businessId: string, on: boolean) =>
    on ? api.put<void>(`/me/favourites/${e(businessId)}`) : api.delete<void>(`/me/favourites/${e(businessId)}`),
  paymentMethods: () => need(api.get<PaymentMethods>('/me/payment-methods')),
  activity: () => need(api.get<{ items: ActivityItem[] }>('/me/activity')),
  notificationPrefs: () => need(api.get<NotificationPrefs>('/me/notifications')),
  setQuietHours: (quietOn: boolean) => need(api.put<NotificationPrefs>('/me/notifications', { json: { quietOn } })),
});

export type ServicesApi = ReturnType<typeof servicesApi>;

/** A name in the reader's language: French once the taxonomy has it, English otherwise. */
export const nameIn = (names: Names, locale: string) => (locale === 'fr-CA' ? (names.fr ?? names.en) : names.en) ?? Object.values(names)[0] ?? '';
