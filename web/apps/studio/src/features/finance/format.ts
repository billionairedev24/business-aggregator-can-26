import { formatNumber, formatWhen, type ColumnPoint, type Locale, timeZone } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { addDays, localDate } from '../../lib/time';

/*
 * S-176: the finance screens draw dates and money with the shared formatters only (formatWhen / formatPrice, through
 * `useFormatters()`); these are the few shapes they add on top: a weekday name, a percentage, an axis label.
 */
const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };

/** "Friday" / "vendredi". */
export const weekdayLong = (iso: string, locale: Locale) => new Intl.DateTimeFormat(INTL[locale], { timeZone: timeZone(), weekday: 'long' }).format(new Date(iso));
/** "Friday, October 30" — the payout dates (formatWhen `long`). */
export const dayDate = (iso: string, locale: Locale) => formatWhen(iso, locale, 'long');
/** "Sep 9, 2:14 p.m." (formatWhen `dateTime`). */
export const dateTime = (iso: string, locale: Locale) => formatWhen(iso, locale, 'dateTime');
/** "3:45 p.m." (formatWhen `time`), with "today" left to the caller. */
export const timeOf = (iso: string, locale: Locale) => formatWhen(iso, locale, 'time');

/** Percent from basis points: 240 → "2.4%" (fr: "2,4 %"). */
export const pctFromBps = (bps: number, locale: Locale) => new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: 1 }).format(bps / 10_000);
export const pct = (n: number, locale: Locale) => new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: 0 }).format(n / 100);
export const signedPct = (n: number, locale: Locale) => new Intl.NumberFormat(INTL[locale], { style: 'percent', maximumFractionDigits: 0, signDisplay: 'exceptZero' }).format(n / 100);

/** A chart's y-axis figure in whole dollars ("$750" / "750 $"): an axis step, not a price (prices are formatPrice). */
export const axisDollars = (cents: number, locale: Locale) => formatNumber(Math.round(cents / 100), locale, { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol', maximumFractionDigits: 0 }).replace(/ | /g, ' ');

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

/**
 * Amounts per calendar day for the last `days` days ending today (the business's zone), oldest first: the columns of a
 * ColumnChart (weekday initial as the label, the full date as its name). `at` picks each item's instant.
 */
export function dailyColumns<T>(items: readonly T[], at: (x: T) => string | null | undefined, cents: (x: T) => number, locale: Locale, days = 14, now = new Date()): ColumnPoint[] {
  const end = localDate(now);
  const sums = new Map<string, number>();
  for (const x of items) {
    const when = at(x);
    if (!when) continue;
    const d = localDate(when);
    sums.set(d, (sums.get(d) ?? 0) + cents(x));
  }
  const fmt = (d: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(INTL[locale], { timeZone: 'UTC', ...o }).format(new Date(`${d}T12:00:00Z`)).replace('.', '');
  return Array.from({ length: days }, (_, i) => {
    const d = addDays(end, i - days + 1);
    return { label: fmt(d, { weekday: 'narrow' }), name: fmt(d, { weekday: 'long', month: 'short', day: 'numeric' }), value: sums.get(d) ?? 0 };
  });
}
