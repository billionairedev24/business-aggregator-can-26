import { ApiError, type ApiClient, type Locale } from '@northline/mobile-kit';

/**
 * Journey D's api (S-101): the consumer web's account endpoints (S-56, S-58, S-59, S-60) under `/api/v1/me`, called
 * directly with the app's DPoP tokens, and northline-auth's security API (S-19) in the app's auth session. Shapes
 * follow the api's records (the consumer web's zod schemas in web/apps/consumer/src/features/account/*Api.ts and
 * features/quotes/api.ts). Everything here is personal: a guest never calls it.
 */

// ── the account menu's values (S-58/S-59; MOBILE_PLAN § Contracts: query key ['account', 'summary']) ──────────────
export interface AccountSummary {
  reliability?: number | null;
  points?: { balance: number; valueCents: number } | null;
  plus?: boolean | null;
  activeOrders?: number | null;
  favourites?: number | null;
  openCases?: number | null;
  paymentMethod?: { brand: string; last4: string } | null;
  addresses?: { count: number; members: number } | null;
  /** passkey | totp | sms */
  signIn?: string | null;
  /** "22:00" / "07:00" — null when quiet hours are off */
  quietHours?: { from: string; to: string } | null;
  dietary?: string[] | null;
  province?: string | null;
}

// ── Orders & bookings (S-58) ─────────────────────────────────────────────────────────────────────────────────────
export type ActivityKind = 'order' | 'food' | 'booking' | 'quote';
export type ActivityAction = 'track' | 'details' | 'view_quote' | 'rebook' | 'view_case' | 'report';
export type Tone = 'accent' | 'neutral' | 'accent-2';
export interface CaseRef { id: string; number: string; kind: string; open: boolean }
export interface ActivityItem {
  id: string;
  kind: ActivityKind;
  ref?: string | null;
  title: string;
  with: string[];
  /** pooled | direct (shop order), delivery | pickup (food) */
  delivery?: string | null;
  shops: number;
  items: number;
  when: string;
  whenEnd?: string | null;
  amountCents: number;
  status: string;
  tone: Tone;
  active: boolean;
  caseRef?: CaseRef | null;
  action: ActivityAction;
  /** The consumer web's path for the row (the app maps it to its own routes, `src/account/routes.ts`). */
  href?: string | null;
}

// ── quotes (S-56) ────────────────────────────────────────────────────────────────────────────────────────────────
export type LineKind = 'labour' | 'part' | 'fee' | 'travel' | 'discount';
export interface QuoteLine { kind: LineKind; description: string; note?: string | null; qty: number; unitCents: number; amountCents: number; taxable: boolean }
export interface QuoteVersion { quoteId: string; version: number; state: string; totalCents: number; sentAt?: string | null }
export interface Quote {
  id: string; requestId: string; ref: string; merchantId: string; version: number; state: string; expired: boolean;
  scope: string; exclusions?: string | null; proposedAt?: string | null; durationMin?: number | null;
  warranty: string; depositKind: string; depositBps?: number | null; lines: QuoteLine[];
  subtotalCents: number; taxBps: number; taxCents: number; totalCents: number; depositCents: number;
  sentAt?: string | null; validUntil?: string | null; versions: QuoteVersion[]; currentQuoteId: string;
}
export interface QuoteProvider { merchantId: string; slug: string; name: string; tier: string; rating: number; reviewCount: number; onTimePct?: number | null; disputePct?: number | null; verifiedFacts: string[] }
export interface QuotePage {
  quote: Quote; provider: QuoteProvider; title: string; area?: string | null; bookingId?: string | null;
  others: Array<{ quoteId: string; providerName: string; totalCents: number; state: string }>;
}
/** Where the job happens — given to the chosen provider only when accepting (S-56). */
export interface Visit { addressLine?: string; unit?: string; accessNote?: string; contactPhone?: string }
export interface Acceptance {
  quoteId: string; bookingId: string; amountCents: number; taxCents: number; totalCents: number;
  /** requires_action | requires_payment_method (the card confirms), authorized (the api's stand-in) */
  status: string;
  paymentIntent?: string | null; clientSecret?: string | null; provider: 'stripe' | 'fake'; publishableKey?: string | null;
}
export interface BookingConfirmation { bookingId: string; ref: string; providerName: string; title: string; startsAt: string; heldCents: number }

// ── profile, addresses, household & Plus (S-59) ──────────────────────────────────────────────────────────────────
export interface Profile {
  id: string; firstName: string; lastName: string; email?: string | null; phone?: string | null; locale: string;
  memberSince: string; pronouns?: string | null; birthday?: string | null; reliability?: number | null; erasureRequestedAt?: string | null;
}
export interface ProfileChange { firstName: string; lastName: string; email: string; pronouns: string | null; birthday: string | null }
export interface Address { id: string; label?: string | null; street: string; unit?: string | null; city: string; province: string; postal: string; note?: string | null; isDefault: boolean }
export interface AddressInput { label?: string; street: string; unit?: string; city: string; province: string; postal: string; note?: string }
export interface Household {
  id?: string | null;
  members: Array<{ userId: string; name: string; role: string; you: boolean }>;
  plan: 'none' | 'monthly' | 'annual';
  plusSince?: string | null;
  renewsAt?: string | null;
}

// ── wallet & payment methods (S-58, S-59) ────────────────────────────────────────────────────────────────────────
export interface Wallet {
  points: { balance: number; valueCents: number; weekly: number[] };
  plus?: { plan: 'monthly' | 'annual'; since?: string | null; renewsAt?: string | null; members: number } | null;
}
export interface Card { id: string; brand: string; last4: string; expMonth: number; expYear: number; isDefault: boolean; addedAt?: string }
export interface Cards { provider: 'stripe' | 'fake'; publishableKey?: string | null; items: Card[] }
export interface CardSetup { setupIntentId: string; clientSecret?: string | null; provider: 'stripe' | 'fake'; publishableKey?: string | null }

// ── notifications (S-59; push since S-102) ───────────────────────────────────────────────────────────────────────
export const NOTIFY_EVENTS = ['booking_reminders', 'order_updates', 'sign_off', 'quotes_messages', 'refunds_cases', 'offers', 'security'] as const;
export const NOTIFY_CHANNELS = ['push', 'sms', 'email'] as const;
export type NotifyEvent = (typeof NOTIFY_EVENTS)[number];
export type NotifyChannel = (typeof NOTIFY_CHANNELS)[number];
export type Matrix = Record<string, Record<string, boolean>>;
export interface NotificationPrefs {
  events?: string[];
  channels?: string[];
  matrix: Matrix;
  quietOn: boolean;
  /** "22:00" (or "22:00:00") */
  quietFrom: string;
  quietTo: string;
  /** app | en | fr */
  language: string;
  /** weekly | rewards | none */
  marketing: string;
}
export type NotificationChange = Partial<Omit<NotificationPrefs, 'matrix' | 'events' | 'channels'>> & { matrix?: Matrix };

// ── preferences: language & region, dietary & accessibility (S-59) ───────────────────────────────────────────────
export const DIETARY = ['halal', 'kosher', 'vegetarian', 'vegan', 'gluten_free', 'dairy_free', 'nut_free', 'low_sodium'] as const;
export const ACCESSIBILITY = ['step_free', 'deaf_text', 'low_vision', 'service_animal', 'knock_loudly', 'fragrance_free'] as const;
export interface Prefs {
  /** en | fr */
  language: string;
  province?: string | null;
  units: string;
  timeFormat: string;
  dietary: string[];
  allergies?: string | null;
  accessibility: string[];
  accessNotes?: string | null;
  display: string[];
}

// ── favourites (S-58) ────────────────────────────────────────────────────────────────────────────────────────────
export interface Favourite { merchantId: string; name: string; type: string; tier: string; slug?: string | null; visits: number; lastAt?: string | null; addedAt: string }

// ── help & cases (S-60) ──────────────────────────────────────────────────────────────────────────────────────────
export interface CaseRow {
  id: string; number: string; kind: string; state: string; open: boolean; what: string; amountCents: number; taxCents: number;
  merchantName: string; openedAt: string; respondBy?: string | null; outcome?: string | null; settledCents?: number | null; subject?: ActivityItem | null;
}
export interface CaseStep { key: 'submitted' | 'seller' | 'northline' | 'refund' | string; state: 'done' | 'current' | 'todo' | 'skipped' | 'denied' | string; at?: string | null }
export interface CaseNote { at: string; by: 'you' | 'northline' | string; body: string }
export interface CaseDetail {
  row: CaseRow; steps: CaseStep[]; card?: { brand: string; last4: string } | null;
  thread?: { id: string; code: string; state: string; notes: CaseNote[] } | null;
}

// ── northline-auth: security (S-19) ──────────────────────────────────────────────────────────────────────────────
export interface ActiveSession { id: string; device?: string | null; city?: string | null; method?: string | null; signedInAt: string; lastSeenAt?: string | null; apps?: string[]; current: boolean }
export interface Security {
  email?: string | null;
  mfaPrimary?: string | null;
  passkeys: Array<{ id: string; label: string; createdAt?: string | null; lastUsedAt?: string | null }>;
  authenticator: boolean;
  backupCodesRemaining: number;
  sessions: ActiveSession[];
}

const id = (s: string) => encodeURIComponent(s);
/** An answer the screen needs: an empty body where JSON was due is the api failing (502), not data. */
const need = async <T,>(p: Promise<T | null>): Promise<T> => {
  const v = await p;
  if (v === null || v === undefined) throw new ApiError(502, 'empty_answer', undefined);
  return v;
};
const lang = (l: Locale) => (l === 'fr-CA' ? 'fr' : 'en');

/** The `/api/v1/me` account calls (every one signed). */
export const accountApi = (api: ApiClient) => ({
  summary: () => api.get<AccountSummary>('/me/account-summary'),
  activity: async () => (await api.get<{ items: ActivityItem[] }>('/me/activity'))?.items ?? [],

  quote: (quoteId: string, locale: Locale) => need(api.get<QuotePage>(`/me/quotes/${id(quoteId)}?lang=${lang(locale)}`)),
  declineQuote: (quoteId: string) => api.post<void>(`/me/quotes/${id(quoteId)}/decline`),
  acceptQuote: (quoteId: string, visit: Visit, key: string, stepUp?: string) =>
    need(api.post<Acceptance>(`/me/quotes/${id(quoteId)}/accept`, { json: visit, idempotencyKey: key, headers: stepUp ? { 'X-Step-Up': stepUp } : undefined })),
  confirmQuote: (quoteId: string, visit: Visit, key: string) =>
    need(api.post<BookingConfirmation>(`/me/quotes/${id(quoteId)}/accept/confirm`, { json: visit, idempotencyKey: key })),

  profile: () => need(api.get<Profile>('/me/profile')),
  saveProfile: (c: ProfileChange) => need(api.patch<Profile>('/me/profile', { json: c })),
  requestErasure: () => need(api.post<Profile>('/me/erasure-request')),
  addresses: async () => (await api.get<{ items: Address[] }>('/me/addresses'))?.items ?? [],
  addAddress: (a: AddressInput) => need(api.post<Address>('/me/addresses', { json: a })),
  defaultAddress: async (addressId: string) => (await api.post<{ items: Address[] }>(`/me/addresses/${id(addressId)}/default`))?.items ?? [],
  removeAddress: async (addressId: string) => (await api.delete<{ items: Address[] }>(`/me/addresses/${id(addressId)}`))?.items ?? [],
  household: () => need(api.get<Household>('/me/household')),
  startPlus: (plan: 'monthly' | 'annual') => need(api.post<Household>('/me/plus', { json: { plan } })),
  cancelPlus: () => need(api.delete<Household>('/me/plus')),

  wallet: () => need(api.get<Wallet>('/me/wallet')),
  cards: () => need(api.get<Cards>('/me/payment-methods')),
  startCardSetup: () => need(api.post<CardSetup>('/me/payment-methods/setup-intents')),
  saveCard: (setupIntentId: string) => need(api.post<Cards>('/me/payment-methods', { json: { setupIntentId } })),
  defaultCard: (cardId: string) => need(api.post<Cards>(`/me/payment-methods/${id(cardId)}/default`)),
  removeCard: (cardId: string) => need(api.delete<Cards>(`/me/payment-methods/${id(cardId)}`)),

  notifications: () => need(api.get<NotificationPrefs>('/me/notifications')),
  saveNotifications: (c: NotificationChange) => need(api.put<NotificationPrefs>('/me/notifications', { json: c })),
  prefs: () => need(api.get<Prefs>('/me/preferences')),
  savePrefs: (c: Partial<Prefs>) => need(api.patch<Prefs>('/me/preferences', { json: c })),
  /** "Download my data": the JSON export, as text (the phone's share sheet saves or sends it). */
  exportData: () => api.get<unknown>('/me/export'),

  favourites: async () => (await api.get<{ items: Favourite[] }>('/me/favourites'))?.items ?? [],
  removeFavourite: (merchantId: string) => api.delete<void>(`/me/favourites/${id(merchantId)}`),

  cases: async () => (await api.get<{ items: CaseRow[] }>('/me/cases'))?.items ?? [],
  caseDetail: (caseId: string) => need(api.get<CaseDetail>(`/me/cases/${id(caseId)}`)),
  addCaseNote: (caseId: string, body: string) => need(api.post<CaseDetail>(`/me/cases/${id(caseId)}/notes`, { json: { body } })),
});

export type AccountApi = ReturnType<typeof accountApi>;
