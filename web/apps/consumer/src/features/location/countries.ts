import type { Locale } from '@northline/ui';

/**
 * S-192: every country (ISO 3166-1 alpha-2), for the location dialog's "or deliver to another country" select and the
 * footer's "language · country". Names come from the browser's and the server's own `Intl.DisplayNames` in the page's
 * language — no list of names here, and no country assumed: which ones come first is configuration
 * (`NL_COMMON_COUNTRIES`, lib/config.ts). Kosovo's user-assigned XK is left out (no ISO code).
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

/** The default "common countries first" when the deployment sets none (NL_COMMON_COUNTRIES). */
export const DEFAULT_COMMON = ['US', 'CA', 'GB', 'AU', 'IN', 'DE', 'FR', 'MX'] as const;

const names = new Map<string, Intl.DisplayNames | null>();
function displayNames(locale: Locale): Intl.DisplayNames | null {
  if (!names.has(locale)) {
    try { names.set(locale, new Intl.DisplayNames([locale === 'fr' ? 'fr-CA' : 'en-CA'], { type: 'region' })); } catch { names.set(locale, null); }
  }
  return names.get(locale) ?? null;
}

/** "Canada", "États-Unis" — the country's name in the page's language (its code when the runtime has no names). */
export function countryName(code: string, locale: Locale): string {
  try { return displayNames(locale)?.of(code.toUpperCase()) ?? code; } catch { return code; }
}

export const isCountryCode = (code: string | null | undefined): code is string => !!code && COUNTRY_CODES.includes(code.toUpperCase());

/** The select's options: the common countries first (in their configured order), then every other one by name. */
export function countryOptions(locale: Locale, common: readonly string[] = DEFAULT_COMMON): { first: { code: string; name: string }[]; rest: { code: string; name: string }[] } {
  const firstCodes = common.map(c => c.toUpperCase()).filter(isCountryCode).filter((c, i, a) => a.indexOf(c) === i);
  const collator = new Intl.Collator(locale === 'fr' ? 'fr-CA' : 'en-CA');
  const named = (code: string) => ({ code, name: countryName(code, locale) });
  return {
    first: firstCodes.map(named),
    rest: COUNTRY_CODES.filter(c => !firstCodes.includes(c)).map(named).sort((a, b) => collator.compare(a.name, b.name)),
  };
}
