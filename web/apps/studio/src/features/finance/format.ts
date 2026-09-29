import { TIME_ZONE, type Locale } from '@northline/ui';
import { ApiError } from '../../lib/http';

const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };
const fmt = (locale: Locale, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(INTL[locale], { timeZone: TIME_ZONE, ...o });

/** "Friday" / "vendredi". */
export const weekdayLong = (iso: string, locale: Locale) => fmt(locale, { weekday: 'long' }).format(new Date(iso));
/** "Fri Sep 11" / "ven. 11 sept." — the design's payout dates. */
export const dayDate = (iso: string, locale: Locale) => fmt(locale, { weekday: 'short', month: 'short', day: 'numeric' }).format(new Date(iso)).replace(/,/g, '');
/** "Sep 9, 2:14 p.m." */
export const dateTime = (iso: string, locale: Locale) => fmt(locale, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
/** "3:45 p.m." with "today" left to the caller. */
export const timeOf = (iso: string, locale: Locale) => fmt(locale, { hour: 'numeric', minute: '2-digit' }).format(new Date(iso));

/** Whole dollars when there are no cents ("$160"), else two decimals — the design writes "$160" and "$38.00". */
export const moneyShort = (cents: number, money: (c: number, o?: { whole?: boolean }) => string) => money(cents, { whole: cents % 100 === 0 });

/** Percent from basis points: 240 → "2.4%" (fr: "2,4 %"). */
export const pctFromBps = (bps: number, locale: Locale) => new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: 1 }).format(bps / 10_000);
export const pct = (n: number, locale: Locale) => new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: 0 }).format(n / 100);
export const signedPct = (n: number, locale: Locale) => new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: 0, signDisplay: 'exceptZero' }).format(n / 100);

/** Hours until an instant, at least 1. */
export const hoursUntil = (iso: string, now = Date.now()) => Math.max(1, Math.ceil((new Date(iso).getTime() - now) / 3_600_000));

export const isForbidden = (e: unknown) => e instanceof ApiError && e.status === 403;

/** Dollars typed by a person ("822.60", "$1,000") → cents, or NaN. */
export function toCents(input: string): number {
  const clean = input.replace(/[$\s]/g, '').replace(/,(?=\d{3}\b)/g, '').replace(',', '.');
  if (!/^\d+(\.\d{0,2})?$/.test(clean)) return Number.NaN;
  return Math.round(Number(clean) * 100);
}
export const toDollars = (cents: number) => (cents / 100).toFixed(2);
