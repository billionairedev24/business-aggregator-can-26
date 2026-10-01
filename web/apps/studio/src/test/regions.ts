/**
 * Test data (S-134): the region model as `GET /api/v1/geo/regions` answers it for the launch configuration — Alberta
 * live with its markets, BC a pilot, Ontario and Québec waitlisted, the rest off. Tests may keep Calgary data; the app
 * code never does.
 */
const p = (code: string, name: string, nameFr: string, status: string, timeZone: string, privacyLaw = 'pipeda', inFr = `en ${nameFr}`, ofFr = `de ${nameFr}`) =>
  ({ code, name, nameFr, nameIn: `in ${name}`, nameOf: name, inFr, ofFr, status, timeZone, timeZones: [timeZone], privacyLaw, taxBps: 500 });

const PROVINCES = [
  p('AB', 'Alberta', 'Alberta', 'live', 'America/Edmonton', 'ab_pipa', 'en Alberta', "de l'Alberta"),
  p('BC', 'British Columbia', 'Colombie-Britannique', 'pilot', 'America/Vancouver', 'bc_pipa'),
  p('MB', 'Manitoba', 'Manitoba', 'off', 'America/Winnipeg'),
  p('NB', 'New Brunswick', 'Nouveau-Brunswick', 'off', 'America/Moncton'),
  p('NL', 'Newfoundland and Labrador', 'Terre-Neuve-et-Labrador', 'off', 'America/St_Johns'),
  p('NS', 'Nova Scotia', 'Nouvelle-Écosse', 'off', 'America/Halifax'),
  p('NT', 'Northwest Territories', 'Territoires du Nord-Ouest', 'off', 'America/Yellowknife'),
  p('NU', 'Nunavut', 'Nunavut', 'off', 'America/Iqaluit'),
  p('ON', 'Ontario', 'Ontario', 'waitlist', 'America/Toronto'),
  p('PE', 'Prince Edward Island', 'Île-du-Prince-Édouard', 'off', 'America/Halifax'),
  p('QC', 'Québec', 'Québec', 'waitlist', 'America/Toronto', 'qc_law25', 'au Québec', 'du Québec'),
  p('SK', 'Saskatchewan', 'Saskatchewan', 'off', 'America/Regina'),
  p('YT', 'Yukon', 'Yukon', 'off', 'America/Whitehorse'),
];

const MARKETS = [
  { id: 'mkt-calgary', city: 'Calgary', province: 'AB', timeZone: 'America/Edmonton', status: 'live', lat: 51.0447, lng: -114.0719 },
  { id: 'mkt-edmonton', city: 'Edmonton', province: 'AB', timeZone: 'America/Edmonton', status: 'live', lat: 53.5461, lng: -113.4938 },
  { id: 'mkt-airdrie', city: 'Airdrie', province: 'AB', timeZone: 'America/Edmonton', status: 'live', lat: 51.2917, lng: -114.0144 },
];

/** The body of `GET /api/v1/geo/regions?lang=` in that language. */
export function regionsBody(url: string) {
  const fr = /[?&]lang=fr\b/.test(url);
  return {
    platformTimeZone: 'America/Edmonton',
    defaultProvince: 'AB',
    provinces: PROVINCES.map(({ nameFr, inFr, ofFr, ...r }) => ({ ...r, name: fr ? nameFr : r.name, nameIn: fr ? inFr : r.nameIn, nameOf: fr ? ofFr : r.nameOf })),
    markets: MARKETS,
  };
}

export const isRegions = (url: string) => url.includes('/api/v1/geo/regions');
