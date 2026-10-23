import { IntlMessageFormat } from 'intl-messageformat';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { brand } from '@northline/tokens';

export type Locale = 'en' | 'fr';
export const LOCALES: readonly Locale[] = ['en', 'fr'];
const INTL_LOCALE: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };

/*
 * Time zones (S-134, region-neutral): no zone is hard-coded. The app configures the platform zone (dates that belong to
 * no market: account dates, report file names) from the region configuration (`GET /api/v1/geo/regions` →
 * `platformTimeZone`, or VITE_NL_PLATFORM_TIME_ZONE before that loads), and sets the zone of what is on screen — the
 * merchant's market in the Studio, the visitor's market on the consumer site — with `setTimeZone`. Until either is
 * known, dates read in UTC.
 */
let platformZone = 'UTC';
let currentZone: string | undefined;

const validZone = (zone: string | null | undefined): zone is string => {
  if (!zone) return false;
  try { new Intl.DateTimeFormat('en-CA', { timeZone: zone }); return true; } catch { return false; }
};

/** The zone of platform-wide dates (no market). Ignores an unknown or empty zone. */
export function configurePlatformTimeZone(zone: string | null | undefined): void {
  if (validZone(zone)) platformZone = zone;
}

/** The zone of the market or merchant on screen; `undefined` = back to the platform zone. */
export function setTimeZone(zone: string | null | undefined): void {
  currentZone = validZone(zone) ? zone : undefined;
}

/** The zone dates are shown in now: the market's or merchant's, else the platform zone. */
export const timeZone = (): string => currentZone ?? platformZone;

/** The platform zone (dates that belong to no market). */
export const platformTimeZone = (): string => platformZone;

interface LocaleState { locale: Locale; setLocale: (l: Locale) => void }
const LocaleContext = createContext<LocaleState>({ locale: 'en', setLocale: () => {} });

export function I18nProvider({ initial = 'en', children, onChange }: { initial?: Locale; children: ReactNode; onChange?: (l: Locale) => void }) {
  const [locale, set] = useState<Locale>(initial);
  const setLocale = useCallback((l: Locale) => { set(l); onChange?.(l); }, [onChange]);
  // S-109 (WCAG 3.1.1): <html lang> follows the language on screen from the first render, not only after a switch —
  // a French visitor arriving with French saved got lang="en-CA" until they toggled.
  useEffect(() => { if (typeof document !== 'undefined') document.documentElement.lang = INTL_LOCALE[locale]; }, [locale]);
  const value = useMemo(() => ({ locale, setLocale }), [locale, setLocale]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export const useLocale = () => useContext(LocaleContext);

/** The BCP 47 tag of a locale (`lang` attributes): en → en-CA, fr → fr-CA. */
export const langTag = (l: Locale): string => INTL_LOCALE[l];

type Values = Record<string, string | number | boolean | Date | null | undefined>;
export type Translate<K extends string> = (key: K, values?: Values) => string;

/*
 * Ambient message values (S-134): a place name in copy is a parameter — `{province}`, `{city}`, `{privacyLaw}` — that
 * the app fills once for a whole subtree (the merchant's province in the Studio, the visitor's on the consumer site)
 * instead of every call passing it. Values given to `t()` win over the ambient ones.
 */
const ValuesContext = createContext<Values | undefined>(undefined);

export function MessageValues({ values, children }: { values: Values; children: ReactNode }) {
  const parent = useContext(ValuesContext);
  const merged = useMemo(() => ({ ...parent, ...values }), [parent, values]);
  return <ValuesContext.Provider value={merged}>{children}</ValuesContext.Provider>;
}

/** The ambient values in effect (for code that formats outside `defineMessages`). */
export const useMessageValues = (): Values => useContext(ValuesContext) ?? {};

/*
 * The product's name (redesign 2026-10-18): copy never spells it — messages say `{brand}` and get brand.json's name
 * (the same in both languages), so renaming the product is one edit. The lint `pnpm lint:brand` keeps the literal out.
 */
const BRAND_VALUES: Values = { brand: brand.name, brandShort: brand.shortName };

const cache = new Map<string, IntlMessageFormat>();
function format(message: string, locale: Locale, values?: Values): string {
  if (!message.includes('{')) return message;
  values = { ...BRAND_VALUES, ...values };
  message = oneFullStop(message, values);
  const id = `${locale}\u0000${message}`;
  let f = cache.get(id);
  if (!f) { f = new IntlMessageFormat(message, INTL_LOCALE[locale]); cache.set(id, f); }
  // a simple {name} nobody filled (a place before the region model has loaded) reads as empty instead of throwing
  let filled = values;
  for (const [, name] of message.matchAll(/\{(\w+)\}/g)) {
    if (name && (filled === undefined || filled[name] === undefined)) filled = { ...filled, [name]: '' };
  }
  return String(f.format(filled as Record<string, string | number>));
}

/**
 * A sentence's full stop right after a value that already ends with one is dropped (demo rehearsal 2026-10-19: en-CA
 * times end in "a.m." / "p.m.", so "Held for you until {time}." read "…11:27 p.m.."). Copy keeps its full stops.
 */
function oneFullStop(message: string, values: Values): string {
  if (!message.includes('}.')) return message;
  return message.replace(/\{(\w+)\}\./g, (whole, name: string) => {
    const v = values[name];
    return typeof v === 'string' && v.endsWith('.') ? `{${name}}` : whole;
  });
}

/**
 * Declares a message catalogue for one component or feature. French must cover every English key.
 * Messages are ICU MessageFormat: `{count, plural, one {# job} other {# jobs}}`.
 *
 *   const useT = defineMessages({ en: { title: 'Payouts' }, fr: { title: 'Versements' } });
 *   const t = useT(); t('title');
 */
export function defineMessages<M extends Record<string, string>>(dict: { en: M; fr: { [K in keyof M]: string } }) {
  return function useMessages(): Translate<Extract<keyof M, string>> {
    const { locale } = useLocale();
    const ambient = useContext(ValuesContext);
    return useCallback(
      (key, values) => format((dict[locale] as M)[key] ?? dict.en[key] ?? key, locale, ambient ? { ...ambient, ...values } : values),
      [locale, ambient],
    );
  };
}

/**
 * The same catalogue outside React (a route's `head()`, which runs before render): `messagesFor(dict, 'fr')('title')`.
 */
export function messagesFor<M extends Record<string, string>>(dict: { en: M; fr: { [K in keyof M]: string } }, locale: Locale, ambient?: Values): Translate<Extract<keyof M, string>> {
  return (key, values) => format((dict[locale] as M)[key] ?? dict.en[key] ?? key, locale, ambient ? { ...ambient, ...values } : values);
}

/** Money is stored in cents (CAD). en-CA → $1,912.40 · fr-CA → 1 912,40 $ */
export function formatMoney(cents: number, locale: Locale = 'en', opts: { whole?: boolean } = {}): string {
  return new Intl.NumberFormat(INTL_LOCALE[locale], { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol', minimumFractionDigits: opts.whole ? 0 : 2, maximumFractionDigits: opts.whole ? 0 : 2 }).format(cents / 100);
}

/**
 * The one price format (E-15, revamp-epic § Locked direction): `$8.50` / `8,50 $`, always with cents, the narrow
 * symbol, the locale's spaces as plain spaces (no NBSP surprises in tests and copy). Components draw it with `Price`.
 */
export function formatPrice(cents: number, locale: Locale = 'en'): string {
  return plainSpaces(formatMoney(cents, locale));
}

/** A unit a price can be quoted per: weight or volume per 100 g / 100 mL / kg / L, or each. */
export type PriceUnit = '100g' | 'kg' | '100ml' | 'l' | 'each' | 'hour';
const UNIT_LABEL: Record<Locale, Record<PriceUnit, string>> = {
  en: { '100g': '/100 g', kg: '/kg', '100ml': '/100 mL', l: '/L', each: ' each', hour: '/hr' },
  fr: { '100g': '/100 g', kg: '/kg', '100ml': '/100 mL', l: '/L', each: ' chacun', hour: '/h' },
};
/** The unit price as meta beside a price: `$0.94/100 g`, `$3.75 each` · `0,94 $/100 g`, `3,75 $ chacun`. */
export function formatUnitPrice(cents: number, unit: PriceUnit, locale: Locale = 'en'): string {
  return `${formatPrice(cents, locale)}${UNIT_LABEL[locale][unit]}`;
}
/**
 * The per-unit cents of a pack: `unitCents(850, 900, '100g')` = 94 (a 900 g loaf at $8.50 is $0.94/100 g). `size` is
 * the pack's quantity in grams, millilitres or units, as `per` counts them.
 */
export function unitCents(priceCents: number, size: number, per: PriceUnit): number {
  const base = per === '100g' || per === '100ml' ? 100 : per === 'kg' || per === 'l' ? 1000 : 1;
  return size > 0 ? Math.round((priceCents * base) / size) : priceCents;
}

/** Intl puts narrow no-break spaces in times and amounts ("6:00 p.m.", "8,50 $"); copy and tests read plain spaces. */
const plainSpaces = (s: string) => s.replace(/[   ]/g, ' ');

export function formatNumber(n: number, locale: Locale = 'en', opts?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(INTL_LOCALE[locale], opts).format(n);
}

/** Displays an instant in `zone` (default: the current market's or merchant's, else the platform zone). `style`: date = "Sep 4", long = "Tuesday 8 September", time = "5:45 p.m.", dateTime = "Sep 4, 10:14 a.m." */
export function formatDate(value: string | number | Date, locale: Locale = 'en', style: 'date' | 'long' | 'time' | 'dateTime' | 'full' = 'date', zone: string = timeZone()): string {
  const d = value instanceof Date ? value : new Date(value);
  const o: Record<typeof style, Intl.DateTimeFormatOptions> = {
    date: { month: 'short', day: 'numeric' },
    long: { weekday: 'long', day: 'numeric', month: 'long' },
    time: { hour: 'numeric', minute: '2-digit' },
    dateTime: { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' },
    full: { year: 'numeric', month: 'short', day: 'numeric' },
  };
  return new Intl.DateTimeFormat(INTL_LOCALE[locale], { timeZone: zone, ...o[style] }).format(d);
}

/**
 * The first day of the week where the locale is used, ISO-numbered (1 = Monday … 7 = Sunday), from the platform's
 * locale data (CLDR `weekInfo`); browsers without it get the CLDR answer for the locales we serve (en-CA, fr-CA:
 * Sunday). Calendars start their weeks here.
 */
export function firstDayOfWeek(locale: Locale): number {
  try {
    const l = new Intl.Locale(INTL_LOCALE[locale]) as Intl.Locale & { getWeekInfo?: () => { firstDay: number }; weekInfo?: { firstDay: number } };
    const day = l.getWeekInfo?.().firstDay ?? l.weekInfo?.firstDay;
    if (day && day >= 1 && day <= 7) return day;
  } catch { /* old engine */ }
  return 7;
}

/** ISO weekday of a calendar date (YYYY-MM-DD): 1 = Monday … 7 = Sunday. */
export const isoWeekday = (date: string): number => { const w = new Date(`${date}T12:00:00Z`).getUTCDay(); return w === 0 ? 7 : w; };

/** Adds days to a calendar date (YYYY-MM-DD). */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The first day of the week containing `date` (YYYY-MM-DD), as the locale counts weeks ({@link firstDayOfWeek}). */
export function weekStartOf(date: string, locale: Locale): string {
  return addDays(date, -((isoWeekday(date) - firstDayOfWeek(locale) + 7) % 7));
}

/** A calendar date (YYYY-MM-DD, no time or zone) in the formatter's styles: never shifted by a time zone. */
export function formatDay(date: string, locale: Locale = 'en', style: 'date' | 'long' | 'full' = 'date'): string {
  return formatDate(`${date}T12:00:00Z`, locale, style, 'UTC');
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
const sameDay = (a: Date, b: Date, zone: string) => {
  const k = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
  return k(a) === k(b);
};

/**
 * A span of two instants, never an ISO string (audit #8: "2026-10-19, 9:36 p.m.–2026-10-20, 12:36 a.m." reached the
 * orders page — en-CA's default date is numeric): the same day reads "6–9 p.m." / "18 h – 21 h" (minutes only when a
 * time is off the hour), across days "Oct 19, 9:36 p.m. – Oct 20, 12:36 a.m.".
 */
export function formatTimeRange(start: string | number | Date, end: string | number | Date, locale: Locale = 'en', zone: string = timeZone()): string {
  const a = start instanceof Date ? start : new Date(start), b = end instanceof Date ? end : new Date(end);
  const onHour = a.getUTCMinutes() === 0 && b.getUTCMinutes() === 0;
  const time: Intl.DateTimeFormatOptions = { hour: 'numeric', ...(onHour ? {} : { minute: '2-digit' }) };
  const opts: Intl.DateTimeFormatOptions = sameDay(a, b, zone) ? time : { month: 'short', day: 'numeric', ...time, minute: '2-digit' };
  return plainSpaces(new Intl.DateTimeFormat(INTL_LOCALE[locale], { timeZone: zone, ...opts }).formatRange(a, b));
}

/**
 * A span of days: calendar dates (YYYY-MM-DD) or instants. "Oct 19 – 22" / "19–22 oct." (one month), "Oct 30 – Nov 2",
 * with the year when it is not this one (`full`). A one-day span is that day.
 */
export function formatDateRange(start: string | number | Date, end: string | number | Date, locale: Locale = 'en', style: 'date' | 'full' = 'date', zone: string = timeZone()): string {
  const day = (v: string | number | Date) => (typeof v === 'string' && ISO_DAY.test(v) ? new Date(`${v}T12:00:00Z`) : v instanceof Date ? v : new Date(v));
  const calendar = typeof start === 'string' && ISO_DAY.test(start);
  const tz = calendar ? 'UTC' : zone;
  const opts: Intl.DateTimeFormatOptions = style === 'full' ? { year: 'numeric', month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric' };
  return plainSpaces(new Intl.DateTimeFormat(INTL_LOCALE[locale], { timeZone: tz, ...opts }).formatRange(day(start), day(end)));
}

/** Any date the API sends — a calendar date (YYYY-MM-DD) or an instant — in the formatter's styles, never raw. */
export function formatWhen(value: string | number | Date, locale: Locale = 'en', style: 'date' | 'long' | 'full' | 'dateTime' | 'time' = 'date', zone: string = timeZone()): string {
  if (typeof value === 'string' && ISO_DAY.test(value)) return formatDay(value, locale, style === 'dateTime' || style === 'time' ? 'date' : style);
  return plainSpaces(formatDate(value, locale, style, zone));
}

/** The instant of a wall-clock time (HH:mm) on a calendar date in `zone`, as ISO-8601. */
export function zonedInstant(date: string, time: string, zone: string): string {
  const offset = (at: Date) => {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(at);
    const g = (t: string) => Number(parts.find(p => p.type === t)?.value);
    return Math.round((Date.UTC(g('year'), g('month') - 1, g('day'), g('hour'), g('minute')) - at.getTime()) / 60000);
  };
  const guess = new Date(`${date}T${time}:00Z`);
  const first = new Date(guess.getTime() - offset(guess) * 60000);
  return new Date(guess.getTime() - offset(first) * 60000).toISOString();
}

/**
 * Daily hours kept in a zone (support hours in the platform zone), shown with the zone's short name: "7 a.m. – 11 p.m.
 * MDT" / "7 h à 23 h HAR". No zone is named in copy: it comes from the region configuration.
 */
export function formatZonedHours(from: string, until: string, zone: string, locale: Locale = 'en', on: Date = new Date()): string {
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(on);
  const opts: Intl.DateTimeFormatOptions = { timeZone: zone, hour: 'numeric', minute: '2-digit' };
  const start = new Intl.DateTimeFormat(INTL_LOCALE[locale], opts).format(new Date(zonedInstant(day, from, zone)));
  const end = new Intl.DateTimeFormat(INTL_LOCALE[locale], { ...opts, timeZoneName: 'short' }).format(new Date(zonedInstant(day, until, zone)));
  return locale === 'fr' ? `${start} à ${end}` : `${start} – ${end}`;
}

export function useFormatters() {
  const { locale } = useLocale();
  return useMemo(() => ({
    money: (cents: number, opts?: { whole?: boolean }) => formatMoney(cents, locale, opts),
    /** The one price format: $8.50 / 8,50 $ (Price draws it). */
    price: (cents: number) => formatPrice(cents, locale),
    unitPrice: (cents: number, unit: PriceUnit) => formatUnitPrice(cents, unit, locale),
    timeRange: (start: string | number | Date, end: string | number | Date, zone?: string) => formatTimeRange(start, end, locale, zone),
    dateRange: (start: string | number | Date, end: string | number | Date, style?: 'date' | 'full', zone?: string) => formatDateRange(start, end, locale, style, zone),
    when: (v: string | number | Date, style?: Parameters<typeof formatWhen>[2], zone?: string) => formatWhen(v, locale, style, zone),
    number: (n: number, opts?: Intl.NumberFormatOptions) => formatNumber(n, locale, opts),
    /** `zone`: the booking's, merchant's or market's when the page knows it; else the current zone. */
    date: (v: string | number | Date, style?: Parameters<typeof formatDate>[2], zone?: string) => formatDate(v, locale, style, zone),
  }), [locale]);
}
