import type { Locale } from '@northline/ui';

/** Every province and territory — names only; which of them are live comes from the api (the providers' provinces). */
const NAMES: Record<string, { en: string; fr: string }> = {
  AB: { en: 'Alberta', fr: 'Alberta' }, BC: { en: 'British Columbia', fr: 'Colombie-Britannique' },
  MB: { en: 'Manitoba', fr: 'Manitoba' }, NB: { en: 'New Brunswick', fr: 'Nouveau-Brunswick' },
  NL: { en: 'Newfoundland and Labrador', fr: 'Terre-Neuve-et-Labrador' }, NS: { en: 'Nova Scotia', fr: 'Nouvelle-Écosse' },
  NT: { en: 'Northwest Territories', fr: 'Territoires du Nord-Ouest' }, NU: { en: 'Nunavut', fr: 'Nunavut' },
  ON: { en: 'Ontario', fr: 'Ontario' }, PE: { en: 'Prince Edward Island', fr: 'Île-du-Prince-Édouard' },
  QC: { en: 'Quebec', fr: 'Québec' }, SK: { en: 'Saskatchewan', fr: 'Saskatchewan' }, YT: { en: 'Yukon', fr: 'Yukon' },
};

/** "Alberta" · "Alberta and Ontario" · "Alberta et Ontario"; empty when no province is known. */
export function regionNames(codes: readonly string[], locale: Locale): string {
  const names = codes.map(c => NAMES[c]?.[locale] ?? c);
  return names.length === 0 ? '' : new Intl.ListFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { type: 'conjunction' }).format(names);
}
