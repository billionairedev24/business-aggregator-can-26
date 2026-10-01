import { getLocales } from 'expo-localization';
import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

/** Northline is bilingual from day one: English and Canadian French. */
export type Locale = 'en' | 'fr-CA';
export const LOCALES: readonly Locale[] = ['en', 'fr-CA'];

export type Params = Record<string, string | number>;

/** fr-CA for any French device language, else English. */
export function resolveLocale(tags: readonly string[]): Locale {
  return tags.some((t) => t.toLowerCase().startsWith('fr')) ? 'fr-CA' : 'en';
}

export function deviceLocale(): Locale {
  try {
    return resolveLocale(getLocales().map((l) => l.languageTag));
  } catch {
    return 'en';
  }
}

/** CLDR cardinal categories for the two languages (fr: 0 and 1 are "one"). */
export function pluralCategory(locale: Locale, n: number): 'one' | 'other' {
  if (locale === 'fr-CA') return n === 0 || n === 1 ? 'one' : 'other';
  return n === 1 ? 'one' : 'other';
}

/**
 * Formats a message: `{name}` is replaced by the parameter, and `{n, plural, one {…} other {…}}` picks a form (`#` is
 * the number). Place names, times and counts always arrive as parameters (region-neutral copy).
 */
export function formatMessage(locale: Locale, template: string, params: Params = {}): string {
  let out = '';
  let i = 0;
  while (i < template.length) {
    const open = template.indexOf('{', i);
    if (open === -1) {
      out += template.slice(i);
      break;
    }
    out += template.slice(i, open);
    // find the matching brace
    let depth = 0;
    let close = open;
    for (; close < template.length; close++) {
      if (template[close] === '{') depth++;
      else if (template[close] === '}' && --depth === 0) break;
    }
    const body = template.slice(open + 1, close);
    const plural = /^\s*(\w+)\s*,\s*plural\s*,(.*)$/s.exec(body);
    if (plural) {
      const n = Number(params[plural[1]!] ?? 0);
      const forms: Record<string, string> = {};
      for (const m of plural[2]!.matchAll(/(=\d+|\w+)\s*\{((?:[^{}]|\{[^{}]*\})*)\}/g)) forms[m[1]!] = m[2]!;
      const form = forms[`=${n}`] ?? forms[pluralCategory(locale, n)] ?? forms.other ?? '';
      out += formatMessage(locale, form.replace(/#/g, String(n)), params);
    } else {
      const v = params[body.trim()];
      out += v === undefined ? `{${body}}` : String(v);
    }
    i = close + 1;
  }
  return out;
}

export interface I18n<K extends string> {
  locale: Locale;
  setLocale(locale: Locale): void;
  t(key: K, params?: Params): string;
  /** "7:10 p.m." in the given IANA zone (the market's, from the region model), or the device's when absent. */
  time(iso: string | null | undefined, timeZone?: string): string;
  /** "Thu, Oct 1" in the zone. */
  day(iso: string | null | undefined, timeZone?: string): string;
}

function intlTag(locale: Locale) {
  return locale === 'fr-CA' ? 'fr-CA' : 'en-CA';
}

function formatDate(locale: Locale, iso: string | null | undefined, options: Intl.DateTimeFormatOptions, timeZone?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  try {
    return new Intl.DateTimeFormat(intlTag(locale), { ...options, ...(timeZone ? { timeZone } : {}) }).format(d);
  } catch {
    return new Intl.DateTimeFormat(intlTag(locale), options).format(d);
  }
}

/**
 * An i18n context for one app's catalogues. Both catalogues have the same keys (a test checks it); a key missing in
 * French falls back to English.
 */
export function createI18n<M extends Record<string, string>>(catalogs: { en: M; 'fr-CA': { [k in keyof M]: string } }) {
  type K = Extract<keyof M, string>;
  const Context = createContext<I18n<K> | null>(null);

  function make(locale: Locale, setLocale: (l: Locale) => void): I18n<K> {
    return {
      locale,
      setLocale,
      t: (key, params) => formatMessage(locale, catalogs[locale][key] ?? catalogs.en[key] ?? key, params),
      time: (iso, tz) => formatDate(locale, iso, { hour: 'numeric', minute: '2-digit' }, tz),
      day: (iso, tz) => formatDate(locale, iso, { weekday: 'short', month: 'short', day: 'numeric' }, tz),
    };
  }

  function I18nProvider({ initial, onChange, children }: { initial?: Locale; onChange?: (l: Locale) => void; children: ReactNode }) {
    const [locale, setLocale] = useState<Locale>(initial ?? deviceLocale());
    const value = useMemo(
      () =>
        make(locale, (l) => {
          setLocale(l);
          onChange?.(l);
        }),
      [locale, onChange],
    );
    return <Context.Provider value={value}>{children}</Context.Provider>;
  }

  function useI18n(): I18n<K> {
    const ctx = useContext(Context);
    if (!ctx) throw new Error('useI18n outside I18nProvider');
    return ctx;
  }

  /** Outside React (background tasks, notifications). */
  function translator(locale: Locale) {
    return make(locale, () => undefined);
  }

  return { I18nProvider, useI18n, translator };
}
