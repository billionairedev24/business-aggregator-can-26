import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '@northline/client';
import { accountSummaryQuery, walletQuery } from './api';

/**
 * The account's settings (S-59) — all under `/api/v1/me`, personal, loaded in the browser. Every change refreshes the
 * account menu's values (`accountSummaryQuery`).
 */

// ── Profile ─────────────────────────────────────────────────────────────────────────────────────────────────────────
export const Profile = z.object({
  id: z.string(), firstName: z.string(), lastName: z.string(), email: z.string().nullish(), phone: z.string().nullish(),
  locale: z.string(), memberSince: z.string(), pronouns: z.enum(['she', 'he', 'they', 'none']).nullish(), birthday: z.string().nullish(),
  reliability: z.number().nullish(), erasureRequestedAt: z.string().nullish(),
});
export type Profile = z.infer<typeof Profile>;
export const profileQuery = queryOptions({ queryKey: ['me', 'profile'], queryFn: () => http('/api/v1/me/profile', {}, Profile) });
export interface ProfileChange { firstName: string; lastName: string; email: string; pronouns: string | null; birthday: string | null }

// ── Addresses & household ───────────────────────────────────────────────────────────────────────────────────────────
export const Address = z.object({
  id: z.string(), label: z.string().nullish(), street: z.string(), unit: z.string().nullish(), city: z.string(), province: z.string(),
  postal: z.string(), note: z.string().nullish(), isDefault: z.boolean(),
});
export type Address = z.infer<typeof Address>;
const Addresses = z.object({ items: z.array(Address) });
export const addressesQuery = queryOptions({ queryKey: ['me', 'addresses'], queryFn: async () => (await http('/api/v1/me/addresses', {}, Addresses)).items });
export interface AddressInput { label?: string; street: string; unit?: string; city: string; province: string; postal: string; note?: string }

export const Household = z.object({
  id: z.string().nullish(),
  members: z.array(z.object({ userId: z.string(), name: z.string(), role: z.string(), you: z.boolean() })),
  plan: z.enum(['none', 'monthly', 'annual']), plusSince: z.string().nullish(), renewsAt: z.string().nullish(),
});
export type Household = z.infer<typeof Household>;
export const householdQuery = queryOptions({ queryKey: ['me', 'household'], queryFn: () => http('/api/v1/me/household', {}, Household) });

// ── Payment methods & billing ───────────────────────────────────────────────────────────────────────────────────────
export const SavedCard = z.object({
  id: z.string(), brand: z.string(), last4: z.string(), expMonth: z.number().int(), expYear: z.number().int(), isDefault: z.boolean(), addedAt: z.string(),
});
export type SavedCard = z.infer<typeof SavedCard>;
export const Cards = z.object({ provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish(), items: z.array(SavedCard) });
export type Cards = z.infer<typeof Cards>;
export const cardsQuery = queryOptions({ queryKey: ['me', 'payment-methods'], queryFn: () => http('/api/v1/me/payment-methods', {}, Cards) });
export const Setup = z.object({ setupIntentId: z.string(), clientSecret: z.string().nullish(), provider: z.enum(['stripe', 'fake']).catch('fake'), publishableKey: z.string().nullish() });
export type Setup = z.infer<typeof Setup>;
export const Payment = z.object({
  id: z.string(), at: z.string(), what: z.string(), ref: z.string().nullish(), amountCents: z.number().int(),
  cardBrand: z.string().nullish(), cardLast4: z.string().nullish(), status: z.string(),
});
export type Payment = z.infer<typeof Payment>;
export const billingQuery = queryOptions({ queryKey: ['me', 'billing'], queryFn: async () => (await http('/api/v1/me/billing-history', {}, z.object({ items: z.array(Payment) }))).items });

// ── Notifications ───────────────────────────────────────────────────────────────────────────────────────────────────
export const NOTIFY_EVENTS = ['booking_reminders', 'order_updates', 'sign_off', 'quotes_messages', 'refunds_cases', 'offers', 'security'] as const;
export const NOTIFY_CHANNELS = ['push', 'sms', 'email'] as const;
export const NotificationPrefs = z.object({
  matrix: z.record(z.string(), z.record(z.string(), z.boolean())),
  quietOn: z.boolean(), quietFrom: z.string(), quietTo: z.string(),
  language: z.enum(['app', 'en', 'fr']).catch('app'), marketing: z.enum(['weekly', 'rewards', 'none']).catch('none'),
});
export type NotificationPrefs = z.infer<typeof NotificationPrefs>;
export const notificationsQuery = queryOptions({ queryKey: ['me', 'notifications'], queryFn: () => http('/api/v1/me/notifications', {}, NotificationPrefs) });

/**
 * CASL consent to Northline's marketing (S-108, `GET /api/v1/me/consents`): per channel whether it is given, the wording
 * to read before saying yes (in the UI's language, the legal sender filled in), who asks, and every grant and withdrawal.
 * The `offers` row and "Marketing emails" above are these consents; a save sends the wording versions shown.
 */
export const ConsentHistoryItem = z.object({
  id: z.string(), category: z.string(), action: z.enum(['granted', 'withdrawn']), at: z.string(), source: z.string(),
  wordingVersion: z.string().nullish(), language: z.string().nullish(),
});
export type ConsentHistoryItem = z.infer<typeof ConsentHistoryItem>;
export const Consents = z.object({
  categories: z.array(z.object({
    category: z.string(), channel: z.enum(NOTIFY_CHANNELS), granted: z.boolean(), since: z.string().nullish(), source: z.string().nullish(),
    wordingVersion: z.string(), wording: z.string(),
  })),
  history: z.array(ConsentHistoryItem),
  requester: z.string(),
});
export type Consents = z.infer<typeof Consents>;
export const consentsQuery = (locale: string) => queryOptions({ queryKey: ['me', 'consents', locale], queryFn: () => http('/api/v1/me/consents', {}, Consents) });

// ── Preferences (language & region, dietary & accessibility) ────────────────────────────────────────────────────────
export const DIETARY = ['halal', 'kosher', 'vegetarian', 'vegan', 'gluten_free', 'dairy_free', 'nut_free', 'low_sodium'] as const;
export const ACCESSIBILITY = ['step_free', 'deaf_text', 'low_vision', 'service_animal', 'knock_loudly', 'fragrance_free'] as const;
export const DISPLAY = ['larger_text', 'high_contrast', 'reduce_motion'] as const;
export const Prefs = z.object({
  language: z.enum(['en', 'fr']).catch('en'), province: z.string().nullish(), units: z.enum(['metric', 'imperial']).catch('metric'),
  timeFormat: z.enum(['12h', '24h']).catch('12h'), dietary: z.array(z.string()), allergies: z.string().nullish(),
  accessibility: z.array(z.string()), accessNotes: z.string().nullish(), display: z.array(z.string()),
});
export type Prefs = z.infer<typeof Prefs>;
export const prefsQuery = queryOptions({ queryKey: ['me', 'preferences'], queryFn: () => http('/api/v1/me/preferences', {}, Prefs) });

/**
 * A mutation that writes `data` into its query's cache and refreshes the account menu (and anything else listed).
 */
function useAccountMutation<A, R>(fn: (a: A) => Promise<R>, opts: { set?: readonly unknown[]; refresh?: (readonly unknown[])[] } = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async data => {
      if (opts.set) qc.setQueryData(opts.set, data);
      await Promise.all([accountSummaryQuery.queryKey, ...(opts.refresh ?? [])].map(k => qc.invalidateQueries({ queryKey: k })));
    },
  });
}

export const useSaveProfile = () => useAccountMutation((c: ProfileChange) => http('/api/v1/me/profile', { method: 'PATCH', body: c }, Profile), { set: profileQuery.queryKey });

export const useAddAddress = () => useAccountMutation((a: AddressInput) => http('/api/v1/me/addresses', { method: 'POST', body: a }, Address), { refresh: [addressesQuery.queryKey] });
export const useChangeAddress = () => useAccountMutation(({ id, ...c }: { id: string; label?: string; unit?: string; note?: string }) =>
  http(`/api/v1/me/addresses/${encodeURIComponent(id)}`, { method: 'PATCH', body: c }, Address), { refresh: [addressesQuery.queryKey] });
export const useDefaultAddress = () => useAccountMutation(async (id: string) =>
  (await http(`/api/v1/me/addresses/${encodeURIComponent(id)}/default`, { method: 'POST' }, Addresses)).items, { set: addressesQuery.queryKey });
export const useRemoveAddress = () => useAccountMutation(async (id: string) =>
  (await http(`/api/v1/me/addresses/${encodeURIComponent(id)}`, { method: 'DELETE' }, Addresses)).items, { set: addressesQuery.queryKey });

export const useStartPlus = () => useAccountMutation((plan: 'monthly' | 'annual') => http('/api/v1/me/plus', { method: 'POST', body: { plan } }, Household),
  { set: householdQuery.queryKey, refresh: [walletQuery.queryKey] });
export const useCancelPlus = () => useAccountMutation(() => http('/api/v1/me/plus', { method: 'DELETE' }, Household),
  { set: householdQuery.queryKey, refresh: [walletQuery.queryKey] });

export const startSetup = () => http('/api/v1/me/payment-methods/setup-intents', { method: 'POST' }, Setup);
export const useConfirmCard = () => useAccountMutation((setupIntentId: string) => http('/api/v1/me/payment-methods', { method: 'POST', body: { setupIntentId } }, Cards), { set: cardsQuery.queryKey });
export const useDefaultCard = () => useAccountMutation((id: string) => http(`/api/v1/me/payment-methods/${encodeURIComponent(id)}/default`, { method: 'POST' }, Cards), { set: cardsQuery.queryKey });
export const useRemoveCard = () => useAccountMutation((id: string) => http(`/api/v1/me/payment-methods/${encodeURIComponent(id)}`, { method: 'DELETE' }, Cards), { set: cardsQuery.queryKey });

export type NotificationChange = Partial<Omit<NotificationPrefs, 'matrix'>> & {
  matrix?: Record<string, Record<string, boolean>>;
  /** S-108: where consent changes happen and the wording versions shown, by channel. */
  consentSource?: 'web_settings'; consentWordings?: Record<string, string>;
};
export const useSaveNotifications = () => useAccountMutation((c: NotificationChange) => http('/api/v1/me/notifications', { method: 'PUT', body: c }, NotificationPrefs),
  { set: notificationsQuery.queryKey, refresh: [['me', 'consents']] });

export type PrefsChange = Partial<Prefs>;
export const useSavePrefs = () => useAccountMutation((c: PrefsChange) => http('/api/v1/me/preferences', { method: 'PATCH', body: c }, Prefs),
  { set: prefsQuery.queryKey, refresh: [profileQuery.queryKey] });

