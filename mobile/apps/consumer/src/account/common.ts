import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';

import { ApiError } from '@northline/mobile-kit';

import { accountApi, type AccountSummary } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import type { MessageKey } from '../i18n';
import { services } from '../services';

/** Journey D's api on the app's client. */
export const account = () => accountApi(services().api);

/**
 * Query keys. Everything personal is under `['account', …]`, so one `invalidateQueries({ queryKey: ['account'] })`
 * refreshes the area (S-99's report form does it after a case is opened). `['account', 'summary']` is the contract
 * with Home (MOBILE_PLAN § Contracts › Account summary).
 */
export const KEYS = {
  all: ['account'] as const,
  summary: ['account', 'summary'] as const,
  activity: ['account', 'activity'] as const,
  quote: (id: string, locale: string) => ['account', 'quote', id, locale] as const,
  profile: ['account', 'profile'] as const,
  addresses: ['account', 'addresses'] as const,
  household: ['account', 'household'] as const,
  wallet: ['account', 'wallet'] as const,
  cards: ['account', 'cards'] as const,
  notifications: ['account', 'notifications'] as const,
  prefs: ['account', 'preferences'] as const,
  favourites: ['account', 'favourites'] as const,
  cases: ['account', 'cases'] as const,
  case: (id: string) => ['account', 'case', id] as const,
  security: ['account', 'security'] as const,
};

/**
 * `GET /me/account-summary` (query key `['account', 'summary']`, read by Home and You). An api without it (404) or a
 * sign-in that just ended (401) is "no values", as on the consumer web — the rows then show no value.
 */
export function useAccountSummary() {
  const { status } = useAuth();
  return useQuery({
    queryKey: KEYS.summary,
    queryFn: async (): Promise<AccountSummary | null> => {
      try {
        return await account().summary();
      } catch (e) {
        if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return null;
        throw e;
      }
    },
    enabled: status === 'signedIn',
    staleTime: 60_000,
  });
}

/**
 * A change to the account: the answer (when it is the new state) goes into `set`'s cache, the account menu's values
 * and anything in `refresh` are read again.
 */
export function useAccountMutation<A, R>(fn: (a: A) => Promise<R>, opts: { set?: QueryKey; refresh?: QueryKey[] } = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async (data) => {
      if (opts.set && data !== undefined && data !== null) qc.setQueryData(opts.set, data);
      await Promise.all([KEYS.summary, ...(opts.refresh ?? [])].map((k) => qc.invalidateQueries({ queryKey: k })));
    },
  });
}

/** "4.9" / "4,9". */
export const decimal = (n: number, locale: string) => new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 1, minimumFractionDigits: 1 }).format(n);
/** "12,480" / "12 480". */
export const count = (n: number, locale: string) => new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA').format(n);
/** "5 %" / "5 %" from basis points. */
export const percent = (bps: number, locale: string) =>
  new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { style: 'percent', maximumFractionDigits: 3 }).format(bps / 10_000);

/** A wall-clock time from the api ("22:00", "07:00:00") in the reader's style: "10 p.m." / "22 h". */
export function clock(hhmm: string, locale: string): string {
  const [h = 0, m = 0] = hhmm.split(':').map(Number);
  const d = new Date(Date.UTC(2000, 0, 1, h, m));
  return new Intl.DateTimeFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { hour: 'numeric', minute: m ? '2-digit' : undefined, timeZone: 'UTC' }).format(d);
}

/** "May 2026" — the month someone joined (a date, no zone). */
export function monthYear(isoDate: string, locale: string): string {
  const [y, m] = isoDate.split('-').map(Number);
  if (!y || !m) return isoDate;
  return new Intl.DateTimeFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, 1)));
}

/** A code worded through the catalogue (`account.status.ready`), else the code itself (a newer api). */
export function worded(t: (k: MessageKey, p?: Record<string, string | number>) => string, prefix: string, code: string, params?: Record<string, string | number>): string {
  const key = `${prefix}.${code}` as MessageKey;
  const s = t(key, params);
  return s === key ? code : s;
}
