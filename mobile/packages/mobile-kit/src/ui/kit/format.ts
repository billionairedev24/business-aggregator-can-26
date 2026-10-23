import type { Locale } from '../../i18n';

/**
 * The one price format and the date-range formats of the revamp (S-169, the web's `@northline/ui` i18n: same rules,
 * same strings): `$8.50` / `8,50 $`, always with cents and the narrow symbol; a time window "6–9 p.m." / "18 h – 21 h"
 * on one day and with the dates across midnight; a span of days "Oct 19 – 22" / "19–22 oct.". Never format a price or
 * a date range by hand, and never show an ISO string.
 */
const INTL: Record<Locale, string> = { en: 'en-CA', 'fr-CA': 'fr-CA' };

/** Intl puts narrow no-break spaces in times and amounts ("6:00 p.m.", "8,50 $"); copy and tests read plain spaces. */
const plain = (s: string) => s.replace(/[\u00a0\u202f\u2009]/g, ' ');

export function formatPrice(cents: number, locale: Locale = 'en'): string {
  return plain(
    new Intl.NumberFormat(INTL[locale], { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(cents / 100),
  );
}

/** A unit a price can be quoted per: weight or volume per 100 g / 100 mL / kg / L, each, or per hour (services). */
export type PriceUnit = '100g' | 'kg' | '100ml' | 'l' | 'each' | 'hour';
const UNIT_LABEL: Record<Locale, Record<PriceUnit, string>> = {
  en: { '100g': '/100 g', kg: '/kg', '100ml': '/100 mL', l: '/L', each: ' each', hour: '/hr' },
  'fr-CA': { '100g': '/100 g', kg: '/kg', '100ml': '/100 mL', l: '/L', each: ' chacun', hour: '/h' },
};

/** The unit price as meta beside a price: `$0.94/100 g`, `$3.75 each` · `0,94 $/100 g`, `3,75 $ chacun`. */
export function formatUnitPrice(cents: number, unit: PriceUnit, locale: Locale = 'en'): string {
  return `${formatPrice(cents, locale)}${UNIT_LABEL[locale][unit]}`;
}

/** The per-unit cents of a pack: `unitCents(850, 900, '100g')` = 94 (a 900 g loaf at $8.50 is $0.94/100 g). */
export function unitCents(priceCents: number, size: number, per: PriceUnit): number {
  const base = per === '100g' || per === '100ml' ? 100 : per === 'kg' || per === 'l' ? 1000 : 1;
  return size > 0 ? Math.round((priceCents * base) / size) : priceCents;
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
type When = string | number | Date;
const toDate = (v: When) => (v instanceof Date ? v : new Date(v));

function dtf(locale: Locale, options: Intl.DateTimeFormatOptions, zone?: string) {
  try {
    return new Intl.DateTimeFormat(INTL[locale], { ...options, ...(zone ? { timeZone: zone } : {}) });
  } catch {
    return new Intl.DateTimeFormat(INTL[locale], options);
  }
}

/** `formatRange` where the engine has it (browsers, Hermes with Intl), else "start – end" from the same options. */
function range(f: Intl.DateTimeFormat, a: Date, b: Date): string {
  const r = (f as Intl.DateTimeFormat & { formatRange?: (x: Date, y: Date) => string }).formatRange;
  return plain(typeof r === 'function' ? r.call(f, a, b) : `${f.format(a)} – ${f.format(b)}`);
}

const dayKey = (d: Date, zone?: string) => dtf('en', { year: 'numeric', month: '2-digit', day: '2-digit' }, zone).format(d);

/** One instant's time of day: "9:30 a.m." / "9 h 30", in the market's zone (the device's when absent). */
export function formatTime(at: When, locale: Locale = 'en', zone?: string): string {
  return plain(dtf(locale, { hour: 'numeric', minute: '2-digit' }, zone).format(toDate(at)));
}

/**
 * A span of two instants (a delivery window, a booking): the same day reads "6–9 p.m." / "18 h – 21 h" (minutes only
 * when one is off the hour), across days with the dates. `zone` is the market's or merchant's (the region model); the
 * device's when absent.
 */
export function formatTimeRange(start: When, end: When, locale: Locale = 'en', zone?: string): string {
  const a = toDate(start);
  const b = toDate(end);
  const onHour = a.getUTCMinutes() === 0 && b.getUTCMinutes() === 0;
  const time: Intl.DateTimeFormatOptions = { hour: 'numeric', ...(onHour ? {} : { minute: '2-digit' }) };
  const opts: Intl.DateTimeFormatOptions = dayKey(a, zone) === dayKey(b, zone) ? time : { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' };
  return range(dtf(locale, opts, zone), a, b);
}

/**
 * A span of days: calendar dates (YYYY-MM-DD) or instants. "Oct 19 – 22" / "19–22 oct." in one month,
 * "Oct 30 – Nov 2" across two, with the year when `full`. A one-day span is that day.
 */
export function formatDateRange(start: When, end: When, locale: Locale = 'en', style: 'date' | 'full' = 'date', zone?: string): string {
  const calendar = typeof start === 'string' && ISO_DAY.test(start);
  const day = (v: When) => (typeof v === 'string' && ISO_DAY.test(v) ? new Date(`${v}T12:00:00Z`) : toDate(v));
  const opts: Intl.DateTimeFormatOptions = style === 'full' ? { year: 'numeric', month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric' };
  return range(dtf(locale, opts, calendar ? 'UTC' : zone), day(start), day(end));
}
