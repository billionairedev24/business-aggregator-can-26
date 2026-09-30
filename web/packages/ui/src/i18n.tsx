import { IntlMessageFormat } from 'intl-messageformat';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

export type Locale = 'en' | 'fr';
export const LOCALES: readonly Locale[] = ['en', 'fr'];
const INTL_LOCALE: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };
export const TIME_ZONE = 'America/Edmonton';

interface LocaleState { locale: Locale; setLocale: (l: Locale) => void }
const LocaleContext = createContext<LocaleState>({ locale: 'en', setLocale: () => {} });

export function I18nProvider({ initial = 'en', children, onChange }: { initial?: Locale; children: ReactNode; onChange?: (l: Locale) => void }) {
  const [locale, set] = useState<Locale>(initial);
  const setLocale = useCallback((l: Locale) => { set(l); onChange?.(l); if (typeof document !== 'undefined') document.documentElement.lang = INTL_LOCALE[l]; }, [onChange]);
  const value = useMemo(() => ({ locale, setLocale }), [locale, setLocale]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export const useLocale = () => useContext(LocaleContext);

type Values = Record<string, string | number | boolean | Date | null | undefined>;
export type Translate<K extends string> = (key: K, values?: Values) => string;

const cache = new Map<string, IntlMessageFormat>();
function format(message: string, locale: Locale, values?: Values): string {
  if (!values && !message.includes('{')) return message;
  const id = `${locale}\u0000${message}`;
  let f = cache.get(id);
  if (!f) { f = new IntlMessageFormat(message, INTL_LOCALE[locale]); cache.set(id, f); }
  return String(f.format(values as Record<string, string | number>));
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
    return useCallback((key, values) => format((dict[locale] as M)[key] ?? dict.en[key] ?? key, locale, values), [locale]);
  };
}

/**
 * The same catalogue outside React (a route's `head()`, which runs before render): `messagesFor(dict, 'fr')('title')`.
 */
export function messagesFor<M extends Record<string, string>>(dict: { en: M; fr: { [K in keyof M]: string } }, locale: Locale): Translate<Extract<keyof M, string>> {
  return (key, values) => format((dict[locale] as M)[key] ?? dict.en[key] ?? key, locale, values);
}

/** Money is stored in cents (CAD). en-CA → $1,912.40 · fr-CA → 1 912,40 $ */
export function formatMoney(cents: number, locale: Locale = 'en', opts: { whole?: boolean } = {}): string {
  return new Intl.NumberFormat(INTL_LOCALE[locale], { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol', minimumFractionDigits: opts.whole ? 0 : 2, maximumFractionDigits: opts.whole ? 0 : 2 }).format(cents / 100);
}

export function formatNumber(n: number, locale: Locale = 'en', opts?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(INTL_LOCALE[locale], opts).format(n);
}

/** Displays an instant in America/Edmonton. `style`: date = "Sep 4", long = "Tuesday 8 September", time = "5:45 p.m.", dateTime = "Sep 4, 10:14 a.m." */
export function formatDate(value: string | number | Date, locale: Locale = 'en', style: 'date' | 'long' | 'time' | 'dateTime' | 'full' = 'date'): string {
  const d = value instanceof Date ? value : new Date(value);
  const o: Record<typeof style, Intl.DateTimeFormatOptions> = {
    date: { month: 'short', day: 'numeric' },
    long: { weekday: 'long', day: 'numeric', month: 'long' },
    time: { hour: 'numeric', minute: '2-digit' },
    dateTime: { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' },
    full: { year: 'numeric', month: 'short', day: 'numeric' },
  };
  return new Intl.DateTimeFormat(INTL_LOCALE[locale], { timeZone: TIME_ZONE, ...o[style] }).format(d);
}

export function useFormatters() {
  const { locale } = useLocale();
  return useMemo(() => ({
    money: (cents: number, opts?: { whole?: boolean }) => formatMoney(cents, locale, opts),
    number: (n: number, opts?: Intl.NumberFormatOptions) => formatNumber(n, locale, opts),
    date: (v: string | number | Date, style?: Parameters<typeof formatDate>[2]) => formatDate(v, locale, style),
  }), [locale]);
}
