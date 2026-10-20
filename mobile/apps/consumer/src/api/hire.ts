import type { ApiClient } from '@northline/mobile-kit';

/**
 * S-153: talking to a provider about a quote request or a booking, cancelling a booking under its terms, a deposit
 * quote's balance, photos of the job, and the customer's quote requests — the consumer web's endpoints
 * (web/apps/consumer/src/features/messages, …/booking, …/photos, …/account):
 *
 *   GET  /me/threads                                 every thread, most recent first
 *   POST /me/threads {refType, refId, merchantId?}   open (or find) the thread of a quote request or a booking
 *   GET  /me/threads/{id}                            one thread with its messages
 *   POST /me/threads/{id}/messages {body}            write (masked and checked for off-platform payment by the api)
 *   POST /me/threads/{id}/read
 *   GET  /me/bookings/{id}/cancellation              what cancelling now costs (the terms it was booked under)
 *   POST /me/bookings/{id}/cancel (Idempotency-Key)  the late fee is kept from the hold, the rest released
 *   POST /me/photos (multipart `file`)               a photo of the job: JPEG, PNG, HEIC or WebP, up to 10 MB, scanned
 *   GET  /me/quote-requests                          every quote request with what became of it
 *
 * Paying a balance that couldn't be held by hand needs the card sheet; the app opens the booking on the web for it.
 */
export interface ThreadRow {
  id: string;
  refType: 'quote' | 'booking';
  refId: string;
  refCode?: string | null;
  subject?: string | null;
  businessName: string;
  businessSlug: string;
  href: string;
  lastMessageAt?: string | null;
  lastMessage?: string | null;
  unread: boolean;
}
export interface Message { id: string; from: 'me' | 'business' | 'northline'; body: string; at: string; flagged: boolean }
export interface ThreadPage { thread: ThreadRow; messages: Message[] }
export type ThreadRef = { refType: 'quote'; refId: string; merchantId: string } | { refType: 'booking'; refId: string };

export interface CancelTerms {
  cancellable: boolean;
  late: boolean;
  feeCents: number;
  feeTaxCents: number;
  feeTotalCents: number;
  releasedCents: number;
  freeUntil?: string | null;
  windowHours: number;
  feeBps: number;
  feeFixedCents: number;
}

export interface Photo { id: string; fileName: string; contentType: string; size: number }

export type RequestStatus = 'waiting' | 'quoted' | 'accepted' | 'declined' | 'expired';
export interface RequestRow {
  id: string;
  ref: string;
  title: string;
  createdAt: string;
  expiresAt?: string | null;
  providers: number;
  quotes: number;
  lowestCents?: number | null;
  status: RequestStatus;
  bookingId?: string | null;
  photoCount: number;
  href: string;
}

/** The api's photo limits (booking.api.CustomerPhotos), checked again by the api. */
export const JOB_PHOTO_MAX = 6;
export const JOB_PHOTO_MAX_BYTES = 10 * 1024 * 1024;
export const JOB_PHOTO_TYPES: readonly string[] = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'image/webp'];

const e = encodeURIComponent;
const need = async <T>(p: Promise<T | null>): Promise<T> => {
  const v = await p;
  if (v == null) throw new Error('empty answer');
  return v;
};

export const hireApi = (api: ApiClient) => ({
  threads: async () => (await need(api.get<{ items: ThreadRow[] }>('/me/threads'))).items,
  thread: (id: string) => need(api.get<ThreadPage>(`/me/threads/${e(id)}`)),
  openThread: (ref: ThreadRef) => need(api.post<ThreadPage>('/me/threads', { json: ref })),
  send: (id: string, body: string) => need(api.post<Message>(`/me/threads/${e(id)}/messages`, { json: { body } })),
  markRead: (id: string) => api.post<void>(`/me/threads/${e(id)}/read`),
  cancellation: (bookingId: string) => need(api.get<CancelTerms>(`/me/bookings/${e(bookingId)}/cancellation`)),
  cancel: (bookingId: string, idempotencyKey: string) => api.post<unknown>(`/me/bookings/${e(bookingId)}/cancel`, { idempotencyKey }),
  uploadPhoto: (form: FormData) => need(api.post<Photo>('/me/photos', { form })),
  requests: async () => (await need(api.get<{ items: RequestRow[] }>('/me/quote-requests'))).items,
});

export type HireApi = ReturnType<typeof hireApi>;
