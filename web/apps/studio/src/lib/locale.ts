import { useEffect } from 'react';
import { useLocale, type Locale } from '@northline/ui';

/** Where the Studio remembers the person's language (the FR/EN switch). */
export const LOCALE_KEY = 'nl.locale';

/** The language the person chose with the switch on this browser, else null. */
export function storedLocale(): Locale | null {
  try {
    const v = localStorage.getItem(LOCALE_KEY);
    return v === 'fr' || v === 'en' ? v : null;
  } catch {
    return null;
  }
}

/**
 * The first language before anyone chose (S-116, Loi 96 readiness): the remembered choice, else French when the
 * browser asks for French first, else English. A French-first business switches it later ({@link useFrenchFirst}).
 */
export function initialLocale(browserLanguages: readonly string[] = typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language]): Locale {
  const stored = storedLocale();
  if (stored) return stored;
  const first = browserLanguages.map(l => l.toLowerCase()).find(l => l.startsWith('fr') || l.startsWith('en'));
  return first?.startsWith('fr') ? 'fr' : 'en';
}

/**
 * S-116: a business whose place is French-first (region configuration, `region.frenchFirst` of the merchant) opens
 * the Studio in French unless the person has chosen a language with the switch. Never a place named in code.
 */
export function useFrenchFirst(frenchFirst: boolean | null | undefined) {
  const { locale, setLocale } = useLocale();
  useEffect(() => {
    if (frenchFirst && locale !== 'fr' && storedLocale() === null) setLocale('fr');
  }, [frenchFirst]); // eslint-disable-line react-hooks/exhaustive-deps -- once per business, not on every switch
}
