import theme from '@northline/tokens/theme.json';
import { createContext, useContext, useMemo, type ReactNode } from 'react';

import { deviceLocale, formatMessage, type Locale, type Params } from '../../i18n';
import { kitEn, kitFrCA, type KitKey } from './messages';

interface Kit {
  locale: Locale;
  t(key: KitKey, params?: Params): string;
}

const BRAND = { brand: theme.brand.name, brandShort: theme.brand.shortName } as const;

export function kitTranslator(locale: Locale): Kit {
  const catalog = locale === 'fr-CA' ? kitFrCA : kitEn;
  return { locale, t: (key, params) => formatMessage(locale, catalog[key] ?? kitEn[key] ?? key, { ...BRAND, ...params }) };
}

const Context = createContext<Kit | null>(null);

/**
 * The kit's language: the apps put it inside their own I18nProvider with the person's locale, so the kit's labels
 * ("Remove filter: Vegan", "4.8 out of 5 stars") follow the app's language switch. Without it, the device's language.
 */
export function KitProvider({ locale, children }: { locale: Locale; children: ReactNode }) {
  const value = useMemo(() => kitTranslator(locale), [locale]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useKit(): Kit {
  return useContext(Context) ?? kitTranslator(deviceLocale());
}
