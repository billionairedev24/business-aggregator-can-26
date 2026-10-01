import { useCallback } from 'react';
import { useLocale, type Locale } from '@northline/ui';

/**
 * French for client-side validation messages (S-40). The zod schemas keep the server's exact English (so a 422 and a
 * client check read the same, validation-rules.md); a feature's French dictionary is keyed by that English and must
 * match docs/spec/validation-messages.fr-CA.tsv, the wording the api answers with in French (checked by
 * validation.test.ts). Messages that are already French (a 422 sent with `Accept-Language: fr-CA`) or unknown pass
 * through.
 */
export type FrenchMessages = Readonly<Record<string, string>>;

export const localizeMessage = (message: string | undefined, locale: Locale, fr: FrenchMessages): string | undefined =>
  message && locale === 'fr' ? fr[message] ?? message : message;

/** `(message) => message in the current locale`, for a feature's dictionary. */
export function useLocalizeMessage(fr: FrenchMessages) {
  const { locale } = useLocale();
  return useCallback((message: string | undefined) => localizeMessage(message, locale, fr), [locale, fr]);
}

/** Every value of a field → message map in the current locale. */
export const localizeAll = (errors: Record<string, string>, localize: (m: string | undefined) => string | undefined): Record<string, string> =>
  Object.fromEntries(Object.entries(errors).map(([k, v]) => [k, localize(v) ?? v]));
