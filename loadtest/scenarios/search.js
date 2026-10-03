// S-119 search and browse: what the consumer web and app send while people look around — a fifth open a landing page
// (home, the Food page's kitchens, a service category's providers), the rest search: browsing a kind with no text,
// typed queries, suggestions as they type; English and French (35 % fr, the market's share with headroom for Québec,
// S-116); a tenth go on to page 2, a quarter add a filter (fewer hot-cache hits), a third search near where they are.
// Anonymous, like most of them.
import { check } from 'k6';
import { data, flow, get, json, lang, pick } from '../lib/api.js';
import { searchRateLimited } from '../lib/slo.js';

const WORDS = {
  en: ['pho', 'brake', 'oil change', 'sourdough', 'bakery', 'mechanic', 'inspection', 'coffee', 'spring rolls', 'vegan',
    'butcher', 'greens', 'tires', 'diagnostic', 'bread', 'noodle soup', 'salad', 'cheese', 'milk', 'pantry'],
  fr: ['pho', 'frein', 'vidange', 'pain au levain', 'boulangerie', 'mécanicien', 'inspection', 'café', 'rouleaux',
    'végétalien', 'boucherie', 'légumes', 'pneus', 'diagnostic', 'pain', 'soupe', 'salade', 'fromage', 'lait', 'épicerie'],
};
const KINDS = ['service', 'product', 'food', 'merchant'];
const FILTERS = [
  { minRating: 4 }, { maxPrice: 2000 }, { minPrice: 1000 }, { tier: 'trusted' }, { tier: 'master' },
  { instantBook: true }, { dietary: 'vegan' }, { openNow: true },
];

function query(params) {
  return Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&');
}

/** This VU's service category slugs (the services landing lists them), fetched once. */
let categories = null;

function browse(l) {
  const { market, center } = data.meta;
  const city = encodeURIComponent(market);
  const roll = Math.random();
  if (roll < 0.4) {
    return get(`/api/v1/public/home?city=${city}`, null, { name: '/api/v1/public/home', flow: 'search', lang: l });
  }
  if (roll < 0.7) {
    const near = center ? `&lat=${center.lat}&lng=${center.lng}` : '';
    return get(`/api/v1/public/kitchens?city=${city}${near}`, null,
      { name: '/api/v1/public/kitchens', flow: 'search', lang: l });
  }
  if (categories === null) {
    const landing = json(get(`/api/v1/public/services?lang=${l}`, null,
      { name: '/api/v1/public/services', flow: 'search', lang: l }));
    categories = landing ? landing.groups.flatMap(g => g.items.filter(i => i.providers > 0).map(i => i.slug)) : [];
  }
  if (!categories.length) return get(`/api/v1/public/services?lang=${l}`, null, { name: '/api/v1/public/services', flow: 'search', lang: l });
  return get(`/api/v1/public/services/${pick(categories)}/providers?city=${city}&lang=${l}`, null,
    { name: '/api/v1/public/services/{slug}/providers', flow: 'search', lang: l });
}

export function search() {
  const l = lang();
  if (Math.random() < 0.2) {
    const res = browse(l);
    flow(check(res, { 'landing page 200': r => r.status === 200 }), 'landing page', res);
    return;
  }
  const { province, center } = data.meta;
  const roll = Math.random();
  const params = { market: province, lang: l };
  if (Math.random() < 0.33 && center) {
    params.lat = center.lat + (Math.random() - 0.5) * 0.1;
    params.lng = center.lng + (Math.random() - 0.5) * 0.15;
    if (Math.random() < 0.5) params.sort = 'distance';
  }
  if (Math.random() < 0.25) Object.assign(params, pick(FILTERS));

  let res;
  if (roll < 0.15) {
    const word = pick(WORDS[l]);
    params.q = word.slice(0, 2 + Math.floor(Math.random() * (word.length - 1)));
    delete params.sort;
    res = get(`/api/v1/search/suggest?${query(params)}`, null, { name: '/api/v1/search/suggest', flow: 'search', lang: l });
  } else {
    if (roll < 0.5) params.q = pick(WORDS[l]);
    else params.kind = pick(KINDS);
    res = get(`/api/v1/search?${query(params)}`, null, { name: '/api/v1/search', flow: 'search', lang: l });
    const page = json(res);
    if (res.status === 200 && page && page.next && Math.random() < 0.1) {
      res = get(`/api/v1/search?${query(Object.assign({}, params, { after: page.next }))}`, null,
        { name: '/api/v1/search (page 2)', flow: 'search', lang: l });
    }
  }
  if (res.status === 429) searchRateLimited.add(1);
  flow(check(res, { 'search 200': r => r.status === 200 }), 'search', res);
}
