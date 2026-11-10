/**
 * S-192: every country (ISO 3166-1 alpha-2) for the location sheet's "or deliver to another country", the same list
 * as the consumer web's (`web/apps/consumer/src/features/location/countries.ts`). Names come from the runtime's
 * `Intl.DisplayNames` in the app's language (the code where the engine has no names) — no list of names here, and no
 * country assumed; which come first is the general list below.
 */
export const COUNTRY_CODES = (
  'AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD ' +
  'CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF ' +
  'GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN ' +
  'KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA ' +
  'NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI ' +
  'SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI ' +
  'VN VU WF WS YE YT ZA ZM ZW'
).split(' ');

/** Listed first (a general list, not a home country). */
export const COMMON_COUNTRIES = ['US', 'CA', 'GB', 'AU', 'IN', 'DE', 'FR', 'MX'] as const;

/** "Canada", "États-Unis" — the country's name in the app's language, else its code. */
export function countryName(code: string, locale: string): string {
  try {
    const names = new Intl.DisplayNames([locale === 'fr-CA' || locale === 'fr' ? 'fr-CA' : 'en-CA'], { type: 'region' });
    return names.of(code.toUpperCase()) ?? code;
  } catch {
    return code;
  }
}

/** The common countries first, then every other one by name. */
export function countryOptions(locale: string): { code: string; name: string }[] {
  const named = (code: string) => ({ code, name: countryName(code, locale) });
  const rest = COUNTRY_CODES.filter((c) => !(COMMON_COUNTRIES as readonly string[]).includes(c))
    .map(named)
    .sort((a, b) => a.name.localeCompare(b.name, locale));
  return [...COMMON_COUNTRIES.map(named), ...rest];
}
