import { IntlMessageFormat } from 'intl-messageformat';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

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

const cache = new Map<string, IntlMessageFormat>();
function format(message: string, locale: Locale, values?: Values): string {
  if (!message.includes('{')) return message;
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

export function useFormatters() {
  const { locale } = useLocale();
  return useMemo(() => ({
    money: (cents: number, opts?: { whole?: boolean }) => formatMoney(cents, locale, opts),
    number: (n: number, opts?: Intl.NumberFormatOptions) => formatNumber(n, locale, opts),
    date: (v: string | number | Date, style?: Parameters<typeof formatDate>[2]) => formatDate(v, locale, style),
  }), [locale]);
}
