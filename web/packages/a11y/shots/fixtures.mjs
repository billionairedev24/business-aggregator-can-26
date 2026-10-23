// Demo-like answers for `make ui-shots` (NL_MOCK_SHOTS=1; scripts/responder.mjs puts them before the recorded fixtures):
// a catalogue with photos (the photo library's own — the ones `make demo-data` uploads — served as the catalogue's media by
// shots/photos.ts), more shops and providers, a
// fallback market so the location control names a place, a Studio dashboard. Test personas and test places only —
// the same ones the apps' own tests use. Each override is `(method, path) → { status?, body } | undefined`.
import { readFileSync } from 'node:fs';
import { pattern } from '../scripts/reduce.mjs';
import { state } from '../scripts/overrides.mjs';

const recorded = JSON.parse(readFileSync(new URL('../fixtures/consumer.json', import.meta.url), 'utf8'));
const studioRecorded = JSON.parse(readFileSync(new URL('../fixtures/studio.json', import.meta.url), 'utf8'));
/** A recorded Studio answer by its pattern (`GET /api/v1/merchants/:id/orders`), copied so an override can change it. */
const rec = key => structuredClone(studioRecorded.pattern[key]?.body ?? studioRecorded.exact[key]?.body);
/** A listing photo: a photo library slot (`demo/listing/bakery.sourdough`) as a media id (`/` → `~`), see shots/photos.ts. */
const media = slot => `/api/v1/public/catalogue/media/${slot.replaceAll('/', '~')}`;
const RUN = { windowId: 'W1', label: 'R-611', day: 'today', startsAt: '2026-10-20T00:00:00Z', endsAt: '2026-10-20T03:00:00Z', orderBy: '2026-10-19T23:20:00Z', feeCents: 299, households: 5 };
const TOMORROW = { ...RUN, windowId: 'W2', day: 'tomorrow', startsAt: '2026-10-20T14:00:00Z', endsAt: '2026-10-20T17:00:00Z', orderBy: '2026-10-20T13:05:00Z', households: 0 };

// ── the catalogue ────────────────────────────────────────────────────────────────────────────────────────────────
const shop = (name, tier = 'trusted', slug = null) => ({ id: `m-${name}`, name, type: 'seller', slug, tier });
const pro = (name, slug, tier = 'trusted') => ({ id: `m-${name}`, name, type: 'provider', slug, tier });
const PRODUCTS = [
  ['P1', 'Country sourdough', 'demo/listing/bakery.sourdough', 750, 'Glenmore Bakery', 'Bakery', 4.8, 211],
  ['P2', 'Free-range eggs, dozen', 'demo/listing/grocer.eggs', 650, 'Bow Valley Dairy', 'Grocery', 4.6, 58],
  ['P3', 'Butter croissants ×4', 'demo/listing/bakery.croissants', 1200, 'Sidewalk Citizen', 'Bakery', 4.9, 132],
  ['P4', 'Wildflower honey, 500 g', 'demo/listing/grocer.honey', 1400, 'Prairie Hive', 'Pantry', 4.7, 40],
  ['P5', 'Basmati rice, 1 kg', 'demo/listing/grocer.rice', 699, 'Green Basket Grocer', 'Pantry', 4.5, 22],
  ['P6', 'Honeycrisp apples, 1 kg bag', 'demo/listing/grocer.apples', 899, 'Rooted Farm', 'Produce', 4.4, 17],
  ['P7', 'House blend whole bean, 340 g', 'demo/listing/coffee.house', 1795, 'Monogram Roasters', 'Coffee', 4.9, 304],
  ['P8', 'Strawberry jam', null, 900, 'Prairie Hive', 'Pantry', 4.3, 9],
  ['P9', 'Classic baguette', 'demo/listing/bakery.baguette', 450, 'Glenmore Bakery', 'Bakery', 4.6, 61],
  ['P10', 'Earl Grey loose-leaf tea, 250 g', 'demo/listing/coffee.tea', 1250, 'Monogram Roasters', 'Coffee', 4.2, 12],
  ['P11', 'Cinnamon buns ×6', 'demo/listing/bakery.cinnamon', 1500, 'Sidewalk Citizen', 'Bakery', 4.8, 88],
  ['P12', 'Apple crumble pie', 'demo/listing/bakery.pie', 1800, 'Glenmore Bakery', 'Bakery', 5, 6],
];
const SERVICES = [
  ['S1', 'Brake pads and rotors', 'category/service.automotive.brakes-and-suspension', 7900, 'Prairie Wrench', 'prairie-wrench', 'Mobile mechanic', 4.9, 312],
  ['S2', 'Deep home cleaning', 'demo/listing/cleaning.deep', 12900, 'Spark & Sweep', 'spark-sweep', 'Home cleaning', 4.8, 95],
  ['S3', 'Haircut and style', 'demo/listing/barber.cut', 4500, 'Studio Nine Barbers', 'studio-nine', 'Barber & hair', 4.7, 210],
  ['S4', 'Leaky tap repair', 'category/service.home-trades.plumber', 9500, 'Bow River Plumbing', 'bow-river-plumbing', 'Plumber', 4.6, 41],
  ['S5', 'Math tutoring, grade 9–12', 'demo/listing/tutoring.math', 5500, 'Ada Learning', 'ada-learning', 'Tutor', 5, 18],
  ['S6', 'Lawn and garden tidy-up', 'category/service.cleaning-and-property.lawn-and-yard', 8500, 'Green Thumb Co.', 'green-thumb', 'Gardening', 4.5, 33],
];
const productHit = ([id, name, img, cents, merchant, cat, rating, reviews], i) => ({
  id: `O${id.slice(1)}`, kind: 'product', name, description: null, merchant: shop(merchant), category: { id: `shop.${cat.toLowerCase()}`, name: cat },
  priceCents: cents, pricingMode: 'fixed', rating, reviewCount: reviews, trustTier: 'trusted', distanceKm: null, instantBook: false, fulfilment: ['pooled'],
  openNow: false, soldOut: false, onTonightsRun: i % 3 !== 2, prepMinutes: null, dietary: [], allergens: [],
  // one card without a photo: the designed fallback, in context
  imageKey: i === 7 || !img ? null : `media:${img}`,
});
const serviceHit = ([id, name, img, cents, merchant, slug, cat, rating, reviews], i) => ({
  id, kind: 'service', name, description: null, merchant: pro(merchant, slug), category: { id: `service.${slug}`, name: cat },
  priceCents: cents, pricingMode: i === 3 ? 'quote' : 'fixed', rating, reviewCount: reviews, trustTier: 'trusted', distanceKm: 2 + i * 1.3,
  instantBook: i % 2 === 0, fulfilment: [], openNow: true, soldOut: false, onTonightsRun: false, prepMinutes: null, dietary: [], allergens: [],
  imageKey: i === 4 ? null : `media:${img}`,
});
const CATALOGUE = [...PRODUCTS.map(productHit), ...SERVICES.map(serviceHit)];

function search(path) {
  const q = new URL(path, 'http://x').searchParams;
  const words = (q.get('q') ?? '').toLowerCase().split(/\s+/).filter(Boolean);
  let items = CATALOGUE.filter(h => !q.get('kind') || h.kind === q.get('kind'));
  if (words.length) items = items.filter(h => words.some(w => `${h.name} ${h.category.name} ${h.merchant.name}`.toLowerCase().includes(w.replace(/s$/, ''))));
  if (words.includes('sourdough')) items = [...items, ...CATALOGUE.filter(h => h.kind === 'product' && !items.includes(h)).slice(0, 7)];
  return { items, total: items.length, facets: null, next: null };
}

const tile = ([id, name, img, cents, merchant], i) => ({
  productId: id, offerId: `o-${id}`, name, merchantId: `M${(i % 4) + 1}`, shopName: merchant, unit: null, priceCents: cents,
  imageUrl: i === 7 || !img ? null : media(img), sellers: 1 + (i % 2), run: i % 3 === 2 ? TOMORROW : RUN,
});
const SHOPS = [
  { merchantId: 'M1', name: 'Glenmore Bakery', tier: 'master', departmentSlug: 'bakery', departmentName: 'Bakery', products: 14, run: RUN },
  { merchantId: 'M2', name: 'Sidewalk Citizen', tier: 'trusted', departmentSlug: 'bakery', departmentName: 'Bakery', products: 9, run: TOMORROW },
  { merchantId: 'M3', name: 'Prairie Hive', tier: 'trusted', departmentSlug: 'pantry', departmentName: 'Pantry', products: 6, run: RUN },
  { merchantId: 'M4', name: 'Monogram Roasters', tier: 'master', departmentSlug: 'coffee', departmentName: 'Coffee', products: 11, run: RUN },
  { merchantId: 'M5', name: 'Rooted Farm', tier: 'registered', departmentSlug: 'produce', departmentName: 'Produce', products: 23, run: null },
  { merchantId: 'M6', name: 'Market Collective of Independent Makers', tier: 'trusted', departmentSlug: 'gifts', departmentName: 'Gifts', products: 40, run: RUN },
];
const DEPARTMENTS = [['bakery', 'Bakery', 2], ['pantry', 'Pantry', 1], ['coffee', 'Coffee', 1], ['produce', 'Produce', 1], ['gifts', 'Gifts', 1], ['butcher', 'Butcher', 1]]
  .map(([slug, name, shops]) => ({ slug, name, shops }));

// the whole service taxonomy (db/seed/categories.json), so the Services landing shows every group as it will live
const seed = JSON.parse(readFileSync(new URL('../../../../db/seed/categories.json', import.meta.url), 'utf8'));
const slug = name => name.toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const GROUP_KIND = { automotive: 'visit', 'home-trades': 'visit', 'cleaning-and-property': 'home', 'events-and-hospitality': 'event', 'personal-care-and-wellness': 'appointment', pets: 'home', 'education-and-coaching': 'home', professional: 'consult', 'tech-and-digital': 'visit', 'childcare-and-family': 'home' };
const SERVICES_LANDING = (() => {
  const groups = seed.service.map(g => {
    const key = slug(g.group);
    return { id: `service.${key}`, key, names: { en: g.group }, note: g.note || null, items: g.items.map(([name], i) => ({ slug: slug(name), names: { en: name }, kind: GROUP_KIND[key] ?? 'visit', providers: (name.length * 7 + i * 3) % 23 > 5 ? (name.length * 7 + i * 3) % 23 : 0 })) };
  });
  const items = groups.flatMap(g => g.items);
  return { liveCategories: items.filter(i => i.providers > 0).length, providers: items.reduce((n, i) => n + i.providers, 0), provinces: ['AB'], groups };
})();

const body = key => recorded.exact[key]?.body;
const live = (m, p, prefix) => m === 'GET' && p.split('?')[0] === prefix;

// ── the Studio ───────────────────────────────────────────────────────────────────────────────────────────────────
const weeks = Array.from({ length: 12 }, (_, i) => ({ start: new Date(Date.UTC(2026, 6, 27 + i * 7)).toISOString().slice(0, 10), servicesCents: 120000 + i * 18000 + (i % 3) * 9000, partsCents: 30000 + i * 4000 }));
const DASHBOARD = {
  today: '2026-10-19',
  jobsToday: [
    { id: 'J1', startsAt: '2026-10-19T16:00:00Z', title: 'Brake pads and rotors', customerName: 'Amara O.', area: 'Beltline', access: 'Street parking', escrowHeldCents: 18900, state: 'confirmed', memberName: 'Ravi', mine: true },
    { id: 'J2', startsAt: '2026-10-19T19:30:00Z', title: 'Oil change', customerName: 'Dev P.', area: 'Mission', access: null, escrowHeldCents: 8900, state: 'confirmed', memberName: 'Ravi', mine: true },
    { id: 'J3', startsAt: '2026-10-19T21:00:00Z', title: 'Battery test and replacement', customerName: 'Lena W.', area: 'Kensington', access: 'Driveway', escrowHeldCents: 21400, state: 'requested', memberName: 'Jas', mine: false },
  ],
  run: [],
  counts: { visitsToday: 142, quoteRequestsOpen: 3, quoteRespondBy: '2026-10-20T18:00:00Z', toPack: 0, runCutoff: null, runLabel: null, jobsThisMonth: 38, ordersThisMonth: 0, itemsThisMonth: 0 },
  earnings: { netThisMonthCents: 684500, netLastMonthCents: 592000, releasingCents: 121000, releasingAt: '2026-10-21T16:00:00Z', weeks },
  reputation: { rating: 4.9, reviews: 312, qualityScore: 92, quality: { onTime: 98, photos: 81, response: 95 }, refundRateBps: 30 },
  cases: [{ kind: 'dispute', customerName: 'Jordan K.', subject: 'Squeal after brake job' }],
  lowStock: [{ name: 'Brake pads (front, ceramic)', stock: 2 }],
  compliance: [{ checkType: 'insurance', registry: null, status: 'expiring', expiresAt: '2026-11-02T00:00:00Z', pausesAt: '2026-11-09T00:00:00Z' }],
  coaching: { jobs: 38, withoutPhotos: 7, nextTierReview: '2026-11-01' },
};

/** The seller's dashboard: tonight's run to pack, orders this month. */
const SELLER_DASHBOARD = {
  ...DASHBOARD,
  jobsToday: [],
  run: [
    { orderId: 'O1', ref: 'NL-48213', runLabel: 'R-611', customerName: 'A. Osei', area: 'Beltline', items: [{ title: 'Wiper blades', qty: 2 }], packed: false },
    { orderId: 'O2', ref: 'NL-48219', runLabel: 'R-611', customerName: 'K. Nguyen', area: 'Bridgeland', items: [{ title: 'Brake pads (front, ceramic)', qty: 1 }, { title: 'Washer fluid', qty: 2 }], packed: false },
    { orderId: 'O3', ref: 'NL-48201', runLabel: 'R-611', customerName: 'M. Tran', area: 'Inglewood', items: [{ title: 'Cabin air filter', qty: 1 }], packed: true },
  ],
  counts: { ...DASHBOARD.counts, quoteRequestsOpen: 0, toPack: 2, runCutoff: '2026-10-19T23:20:00Z', runLabel: 'R-611', ordersThisMonth: 64, itemsThisMonth: 151 },
  reputation: { ...DASHBOARD.reputation, reviews: 188 },
  cases: [{ kind: 'refund', customerName: 'A. Osei', subject: 'Wrong size wiper blade' }],
};

/** The kitchen's dashboard: no visits or runs; its permit renewal and one refund to look at. */
const KITCHEN_DASHBOARD = {
  ...DASHBOARD, jobsToday: [], run: [],
  counts: { ...DASHBOARD.counts, visitsToday: 0, quoteRequestsOpen: 0, quoteRespondBy: null, toPack: 0, runCutoff: null, runLabel: null, jobsThisMonth: 0, ordersThisMonth: 412, itemsThisMonth: 980 },
  lowStock: [], cases: [{ kind: 'refund', customerName: 'Dev P.', subject: 'Missing spring rolls' }],
  compliance: [{ checkType: 'ahs_permit', registry: null, status: 'valid', expiresAt: '2026-11-30T18:00:00Z', pausesAt: null }],
};

/**
 * S-174: the dashboard's day happens today, whatever day the shots run: the visits at 9:00, 1:30 and 3:00 local, the run
 * closing at 5:20 p.m., quotes due in three hours (a Prairie day, as the calendar's: local = UTC − 6).
 */
function todayDashboard(base) {
  const d = new Date(); const day = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const local = (h, m = 0) => new Date(day + (h + 6) * 3600000 + m * 60000).toISOString();
  const hours = [[9, 0], [13, 30], [15, 0]];
  return {
    ...base, today: new Date(day).toISOString().slice(0, 10),
    jobsToday: base.jobsToday.map((j, i) => ({ ...j, startsAt: local(...hours[i % hours.length]) })),
    counts: { ...base.counts, quoteRespondBy: base.counts.quoteRespondBy ? new Date(Date.now() + 3 * 3600000).toISOString() : null, runCutoff: base.counts.runCutoff ? local(17, 20) : null },
  };
}

/** The sales report the dashboard reads: the last 30 days by day (today so far last), or 12 months by month. */
function salesReport(path) {
  const yearly = path.includes('period=12mo');
  const b = rec('GET /api/v1/merchants/:id/reports');
  const d = new Date(); const day = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const daily = [41000, 38500, 45200, 49800, 69000, 74100, 35600, 42500, 40200, 47100, 51600, 70200, 81000, 64250];
  const series = yearly
    ? Array.from({ length: 12 }, (_, i) => ({ start: new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 11 + i, 1)).toISOString().slice(0, 10), currentCents: 1200000 + i * 60000 + (i % 4) * 90000, previousCents: 1000000 + i * 40000 }))
    : Array.from({ length: 30 }, (_, i) => ({ start: new Date(day - (29 - i) * DAY).toISOString().slice(0, 10), currentCents: i >= 16 ? daily[i - 16] : Math.round(daily[(i + 12) % 14] * 0.92), previousCents: 42000 + (i % 7) * 2500 }));
  return { ...b, period: yearly ? '12mo' : '30d', granularity: yearly ? 'month' : 'day', series, count: yearly ? 9800 : 899, grossCents: series.reduce((n, x) => n + x.currentCents, 0), averageTicketCents: 1691, grossChangePct: 6,
    byListing: [{ name: 'Washer fluid, −40 °C, 4 L', grossCents: 34800 }, { name: 'Wiper blades, 22 in. pair', grossCents: 58900 }, { name: 'Synthetic oil 5W-30, 5 L', grossCents: 94578 }, { name: 'Brake pads (front, ceramic)', grossCents: 76800 }, { name: 'Brake pads and rotors', grossCents: 582000 }, { name: null, grossCents: 40100 }] };
}

// ── the calendar: a week of jobs relative to the week asked for (`from`, the zone's midnight), so the shots stay full
// whatever day they run. [hour, minute, minutes, title, state, customer, area, cents, member]
const WEEK = {
  1: [[9, 0, 90, 'Brake pads and rotors', 'confirmed', 'Amara O.', 'Beltline', 18900, 'Ravi'], [13, 30, 45, 'Oil change', 'confirmed', 'Dev P.', 'Mission', 8900, 'Ravi'], [15, 0, 60, 'Battery test and replacement', 'requested', 'Lena W.', 'Kensington', 21400, 'Jas']],
  2: [[10, 0, 90, 'Pre-purchase inspection', 'confirmed', 'Sam R.', 'Bridgeland', 16000, 'Ravi'], [14, 0, 60, 'Winter tire swap', 'confirmed', 'Priya K.', 'Altadore', 9900, 'Jas']],
  3: [[8, 30, 60, 'Brake inspection', 'confirmed', 'A. Osei', 'Inglewood', 8900, 'Ravi'], [11, 0, 120, 'Alternator replacement', 'requested', 'M. Tran', 'Inglewood', 41200, 'Ravi']],
  4: [[9, 0, 45, 'Oil change', 'confirmed', 'K. Nguyen', 'Sunnyside', 8900, 'Jas'], [13, 0, 60, 'Check engine light diagnostic', 'confirmed', 'D. Kowalski', 'Hillhurst', 12000, 'Ravi']],
  5: [[10, 0, 90, 'Brake pads and rotors', 'confirmed', 'J. Martin', 'Ramsay', 18900, 'Ravi']],
  6: [[9, 30, 60, 'Winter tire swap', 'confirmed', 'S. Bouchard', 'Tuxedo', 9900, 'Jas']],
};
const DAY = 86400000;
const PROVIDER_ID = '01J9ZD3V00000000000000PWM1';
const seenJobs = new Map();
const jobId = (d, i) => `J${d.toISOString().slice(5, 10).replace('-', '')}${i}`;
function weekJobs(path) {
  const q = new URL(path, 'http://x').searchParams;
  const from = Date.parse(q.get('from') ?? ''), to = Date.parse(q.get('to') ?? '');
  if (!Number.isFinite(from) || !Number.isFinite(to)) return [];
  const out = [];
  for (let t = from; t < to; t += DAY) {
    const day = new Date(t + DAY / 4); // the local calendar day (from is the zone's midnight in UTC)
    (WEEK[day.getUTCDay()] ?? []).forEach(([h, m, min, title, state, customerName, area, priceCents, memberName], i) => {
      const start = from + Math.round((t - from) / DAY) * DAY + (h * 60 + m) * 60000;
      out.push({ id: jobId(day, i), ref: `BK-${7700 + day.getUTCDate() * 3 + i}`, title, startsAt: new Date(start).toISOString(), endsAt: new Date(start + min * 60000).toISOString(), state,
        memberUserId: memberName === 'Ravi' ? '01J9ZD3V00000000000000RAV1' : '01J9ZD3V00000000000000JAS1', memberName, customerName, area, priceCents });
    });
  }
  out.forEach(j => seenJobs.set(j.id, j));
  return out;
}
/** The detail sheet of a job the calendar listed. */
function jobDetail(id) {
  const j = seenJobs.get(id);
  if (!j) return undefined;
  const tax = Math.round(j.priceCents * 0.05), fee = Math.round(j.priceCents * 0.12);
  return {
    ...j, customer: { name: j.customerName, reliability: 4.8, pastJobs: 3 }, addressLine: `${j.area} · street parking`, access: 'Gate code shared in the app', vehicle: '2016 Honda Civic · silver',
    customerNote: 'Grinding noise from the front left when braking.', escrow: 'held',
    escrowMoney: { state: 'held', heldCents: j.priceCents + tax, taxCents: tax, feeCents: fee, netCents: j.priceCents - fee },
    timeline: [{ type: 'booked', at: '2026-10-12T17:20:00Z', actorName: j.customerName, note: null, mediaId: null }, { type: 'confirmed', at: '2026-10-12T17:21:00Z', actorName: j.memberName, note: null, mediaId: null }],
    approvals: [],
  };
}
function weekCells(path) {
  const q = new URL(path, 'http://x').searchParams;
  const first = Date.parse(`${q.get('from')}T12:00:00Z`), days = Number(q.get('days') ?? 7);
  if (!Number.isFinite(first)) return { openSlots: [], quoteHolds: [] };
  // the zone's offset is unknown here: place the cells at the jobs' local hours via the same UTC-6 a Prairie week uses
  const at = (dayIndex, h, m = 0) => new Date(first - 12 * 3600000 + dayIndex * DAY + (h + 6) * 3600000 + m * 60000).toISOString();
  const openSlots = [], quoteHolds = [];
  for (let i = 0; i < days; i++) {
    const wd = new Date(first + i * DAY).getUTCDay();
    if (wd === 4) openSlots.push({ startsAt: at(i, 15) }, { startsAt: at(i, 16) });
    if (wd === 5) openSlots.push({ startsAt: at(i, 14) });
    if (wd === 3) quoteHolds.push({ quoteId: 'q1', requestId: 'r1', ref: 'QT-3104', customerName: 'M. Tran', startsAt: at(i, 15), durationMin: 120 });
  }
  return { openSlots, quoteHolds };
}

function quoteRequests() {
  const [r1] = rec('GET /api/v1/merchants/:id/quote-requests').items;
  const soon = new Date(Date.now() + 5 * 3600000).toISOString();
  return { items: [
    { ...r1, respondBy: soon },
    { ...r1, id: 'r2', ref: 'QT-3109', title: 'Squeal when braking, 2019 RAV4', customerName: 'J. Martin', reliability: 4.9, area: 'Ramsay', body: 'Started this week, worse in the morning.', quote: null, respondBy: soon },
    { ...r1, id: 'r3', ref: 'QT-3098', title: 'Timing belt, 2012 Outback', customerName: 'S. Bouchard', reliability: 4.6, area: 'Tuxedo', body: 'Due at 160,000 km.', quote: { ...r1.quote, id: 'q3', requestId: 'r3', ref: 'QT-3098', state: 'viewed', totalCents: 68250, sentAt: '2026-10-17T18:00:00Z' } },
  ] };
}

// ── availability: a working week, a day of start times (some taken by jobs and their buffer)
function availabilityHours() {
  const b = rec('GET /api/v1/merchants/:id/availability/hours');
  const week = { mon: [['08:00', '17:00']], tue: [['08:00', '17:00']], wed: [['08:00', '12:00'], ['13:00', '17:00']], thu: [['08:00', '17:00']], fri: [['08:00', '16:00']], sat: [['09:00', '14:00']], sun: [] };
  b.members[0].days = week;
  b.lastSavedAt = new Date(Date.now() - 3 * DAY).toISOString();
  return b;
}
const previewSlots = () => {
  const taken = new Set(['09:00', '09:30', '10:00', '10:30', '13:30', '14:00']);
  const slots = [];
  for (let m = 8 * 60; m <= 16 * 60; m += 30) { const hhmm = `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`; slots.push({ start: hhmm, free: !taken.has(hhmm) }); }
  return { slots, jobs: 2, busyBlocks: 0, intervalMin: 30, bufferMin: 20 };
};

// ── listings: the parts shop's products and the mechanic's services, with photos
const SELLER_ID = '01J9ZD3V00000000000000PWP1';
const listing = (id, kind, name, over) => ({ id, kind, name, sku: null, meta: '', priceCents: null, stock: null, sales30d: 0, vetting: 'approved', status: 'live', vettingFlags: [], revetReasons: [],
  submittedAt: null, categoryId: null, pricingMode: kind === 'service' ? 'fixed' : null, updatedAt: '2026-10-14T15:00:00Z', bundle: false, imageUrl: null, ...over });
const PRODUCTS_SOLD = [
  listing('p1', 'product', 'Wiper blades, 22 in. pair', { sku: 'WB-22', priceCents: 1900, stock: 9, sales30d: 31, imageUrl: media('category/service.automotive.windshield-repair') }),
  listing('p2', 'product', 'Washer fluid, −40 °C, 4 L', { sku: 'WF-40', priceCents: 600, stock: 4, sales30d: 58, imageUrl: null }),
  listing('p3', 'product', 'Brake pads (front, ceramic)', { sku: 'BP-FC-118', priceCents: 6400, stock: 2, sales30d: 12, imageUrl: media('category/service.automotive.brakes-and-suspension') }),
  listing('p4', 'product', 'Cabin air filter', { sku: 'CAF-2031', priceCents: 2450, stock: 16, sales30d: 9, imageUrl: media('category/shop.hardware-and-auto.auto-parts') }),
  listing('p5', 'product', 'Synthetic oil 5W-30, 5 L', { sku: 'OIL-530', priceCents: 4299, stock: 11, sales30d: 22, imageUrl: media('category/service.automotive.oil-change-and-fluids') }),
  listing('p6', 'product', 'LED headlight bulbs H11', { sku: 'LED-H11', priceCents: 5900, stock: 0, sales30d: 4, status: 'hidden', imageUrl: null }),
  listing('p7', 'product', 'Winter emergency kit', { sku: 'KIT-W1', priceCents: 7900, stock: 6, sales30d: 0, vetting: 'pending', status: 'hidden', submittedAt: new Date(Date.now() - 40 * 60000).toISOString(), imageUrl: media('category/service.automotive.towing-and-roadside') }),
  listing('b9', 'product', 'Brake job bundle', { sku: 'BUN-BR', priceCents: 8900, stock: 2, sales30d: 3, bundle: true }),
];
const SERVICES_SOLD = [
  listing('s1', 'service', 'Brake pads and rotors', { meta: '90 min · at your place', priceCents: 18900, sales30d: 14, imageUrl: media('category/service.automotive.brakes-and-suspension') }),
  listing('s2', 'service', 'Brake inspection', { meta: '45 min · at your place', priceCents: 8900, sales30d: 21, imageUrl: media('category/service.automotive.mobile-mechanic') }),
  listing('s3', 'service', 'Oil change, synthetic', { meta: '45 min · at your place', priceCents: 8900, sales30d: 26, imageUrl: media('category/service.automotive.oil-change-and-fluids') }),
  listing('s4', 'service', 'Winter tire swap', { meta: '60 min · at your place', priceCents: 9900, sales30d: 17, imageUrl: media('category/service.automotive.tire-change-and-storage') }),
  listing('s5', 'service', 'Pre-purchase inspection', { meta: '90 min · at the seller’s', priceCents: 16000, sales30d: 6, imageUrl: media('category/service.automotive.pre-purchase-inspection') }),
  listing('s6', 'service', 'Alternator replacement', { meta: 'Quoted after diagnosis', pricingMode: 'quote', sales30d: 3, vetting: 'draft', status: 'hidden' }),
];

/** The parts shop's orders: tonight's run (relative to now, so the headline reads "tonight"), pickups, today's deliveries. */
function sellerOrders() {
  const d = new Date(); const day = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const local = (h, m = 0) => new Date(day + (h + 6) * 3600000 + m * 60000).toISOString(); // a Prairie evening, as the calendar's
  const run = { windowStartsAt: local(18), cutoffAt: local(17, 20), runLabel: 'R-611', placedAt: local(9, 12), deliveredAt: null, issueNote: null };
  const line = (id, title, qty, unitCents, state) => ({ id, title, qty, unitCents, state });
  const order = (id, ref, customerName, area, lines, status, over = {}) => ({ id, ref, customerName, area, lines, totalCents: lines.reduce((s, l) => s + l.qty * l.unitCents, 0), ...run, status, ...over });
  const items = [
    order('o1', 'NL-48213', 'A. Osei', 'Beltline', [line('o1a', 'Wiper blades, 22 in. pair', 2, 1900, 'pending')], 'to_pack'),
    order('o4', 'NL-48219', 'K. Nguyen', 'Bridgeland', [line('o4a', 'Brake pads (front, ceramic)', 1, 6400, 'pending'), line('o4b', 'Washer fluid, −40 °C, 4 L', 2, 600, 'pending')], 'to_pack'),
    order('o5', 'NL-48224', 'L. Moreau', 'Mission', [line('o5a', 'Synthetic oil 5W-30, 5 L', 1, 4299, 'pending')], 'to_pack'),
    order('o2', 'NL-48201', 'M. Tran', 'Inglewood', [line('o2a', 'Cabin air filter', 1, 2450, 'packed')], 'awaiting_pickup', { courierPickup: { runLabel: 'R-611', courierAssigned: true, eta: local(17, 40), arrivedAt: null, pickedUpAt: null } }),
    order('o6', 'NL-48207', 'S. Bouchard', 'Tuxedo', [line('o6a', 'Washer fluid, −40 °C, 4 L', 3, 600, 'packed')], 'awaiting_pickup'),
    order('o7', 'NL-48177', 'P. Singh', 'Ramsay', [line('o7a', 'Wiper blades, 22 in. pair', 1, 1900, 'packed')], 'delivered', { deliveredAt: local(12, 5) }),
    order('o8', 'NL-48171', 'J. Martin', 'Altadore', [line('o8a', 'LED headlight bulbs H11', 1, 5900, 'packed')], 'delivered', { deliveredAt: local(11, 40) }),
    order('o3', 'NL-48188', 'D. Kowalski', 'Hillhurst', [line('o3a', 'Wiper blades, 22 in. pair', 1, 1900, 'packed')], 'issue', { issueNote: 'wrong size', deliveredAt: local(10, 30) }),
  ];
  return { items, counts: { toPack: 3, awaitingPickup: 2, deliveredToday: 2, issues: 1, nextCutoff: run.cutoffAt, nextRunLabel: 'R-611' } };
}

/** The inbox, minutes and hours old (the recorded one is weeks old by now). */
const ago = min => new Date(Date.now() - min * 60000).toISOString();
function threads() {
  const b = rec('GET /api/v1/merchants/:id/threads');
  [12, 55, 60 * 26, 60 * 50].forEach((m, i) => { if (b.items[i]) b.items[i].lastMessageAt = ago(m); });
  b.items.splice(2, 0, { ...b.items[2], id: 't5', counterpartName: 'Lena W.', lastMessage: 'Can you do the battery this afternoon instead?', lastMessageAt: ago(180), unread: false });
  return b;
}
function thread() {
  const b = rec('GET /api/v1/merchants/:id/threads/:id');
  b.thread.lastMessageAt = ago(12);
  [60 * 20, 60 * 19, 12].forEach((m, i) => { if (b.messages[i]) b.messages[i].at = ago(m); });
  return b;
}

/** Earnings: the overview and a ledger of the last fortnight's jobs (sale + 5 % tax held; fee 9 %). */
function earnings() {
  const b = rec('GET /api/v1/merchants/:id/earnings');
  return { ...b, nextPayoutAt: new Date(Date.now() + 4 * DAY).toISOString() };
}
function ledger() {
  const rows = [
    ['Brake pads and rotors', 'Amara O.', 18000, 'held', -0.3, 1.4], ['Oil change, synthetic', 'Dev P.', 8476, 'held', -0.2, 1.6], ['Pre-purchase inspection', 'Sam R.', 15238, 'held', -1, 0.6],
    ['Winter tire swap', 'Priya K.', 9429, 'held', -1.2, 0.4], ['Brake inspection', 'A. Osei', 8476, 'disputed', -6, null], ['Check engine light diagnostic', 'D. Kowalski', 11429, 'released', -4, null],
    ['Battery replacement', 'Lena W.', 20381, 'released', -5, null], ['Oil change, synthetic', 'K. Nguyen', 8476, 'released', -6, null], ['Winter tire swap', 'S. Bouchard', 9429, 'released', -7, null],
    ['Brake pads and rotors', 'J. Martin', 18000, 'refunded', -9, null],
  ];
  return { items: rows.map(([label, customerName, gross, state, daysAgo, releaseIn], i) => {
    const tax = Math.round(gross * 0.05), fee = Math.round(gross * 0.09);
    return { id: `e${i + 1}`, kind: 'service', label, orderNumber: `BK-${7790 - i * 3}`, occurredAt: new Date(Date.now() + daysAgo * DAY).toISOString(), customerName,
      heldCents: gross + tax, grossCents: gross, taxCents: tax, feeCents: fee, netCents: gross - fee, state,
      releaseAt: releaseIn == null ? null : new Date(Date.now() + releaseIn * DAY).toISOString(), releasedAt: state === 'released' ? new Date(Date.now() + (daysAgo + 2) * DAY).toISOString() : null };
  }) };
}

/** Payouts: Friday payouts going back two months, one instant. */
function payoutOverview() { return { ...rec('GET /api/v1/merchants/:id/payouts/overview'), nextPayoutAt: new Date(Date.now() + 4 * DAY).toISOString() }; }
function payoutHistory() {
  const [p] = rec('GET /api/v1/merchants/:id/payouts').items;
  const amounts = [214060, 191240, 176820, 203310, 158900, 162450, 149980, 171230];
  return { items: amounts.map((a, i) => ({ ...p, id: `p${i + 1}`, reference: `po_${(9000 + i * 37).toString(36)}`, kind: i === 2 ? 'instant' : 'scheduled', state: i === 0 ? 'in_transit' : 'paid',
    createdAt: new Date(Date.now() - (3 + i * 7) * DAY).toISOString(), arrivesAt: new Date(Date.now() - (1 + i * 7) * DAY).toISOString(), amountCents: a, feeCents: i === 2 ? Math.round(a / 100) : 0, netCents: i === 2 ? a - Math.round(a / 100) : a, itemCount: 9 + (i * 5) % 8 })) };
}

/** Sales report: 13 weeks against the 13 before, the listings that earn most. */
function report() {
  const b = rec('GET /api/v1/merchants/:id/reports');
  const start = Date.now() - 91 * DAY;
  b.series = Array.from({ length: 13 }, (_, i) => ({ start: new Date(start + i * 7 * DAY).toISOString().slice(0, 10), currentCents: 120000 + i * 9000 + (i % 3) * 14000, previousCents: 105000 + i * 5000 + ((i + 1) % 3) * 9000 }));
  b.byListing = [{ name: 'Brake pads and rotors', grossCents: 582000 }, { name: 'Oil change, synthetic', grossCents: 401000 }, { name: 'Winter tire swap', grossCents: 336000 }, { name: 'Pre-purchase inspection', grossCents: 224000 }, { name: null, grossCents: 401000 }];
  return b;
}

/** Reviews of the last weeks. */
function reviews() {
  const b = rec('GET /api/v1/merchants/:id/reviews');
  const [r1, r2, r3] = b.items;
  b.items = [
    { ...r1, id: 'r0', authorName: 'Priya K.', jobLabel: 'winter tire swap', text: 'Booked at 8, done in my driveway by 10. Torqued and re-checked the wheels after 50 km like he said he would.', createdAt: ago(60 * 20), reply: null },
    { ...r1, createdAt: ago(60 * 24 * 3) }, { ...r2, createdAt: ago(60 * 24 * 6) }, { ...r3, createdAt: ago(60 * 24 * 11) },
  ];
  return b;
}

/** The mechanic's public page, approved and not yet published. */
function storefront() {
  const b = rec('GET /api/v1/merchants/:id/storefront');
  return { ...b, merchantId: PROVIDER_ID, slug: 'prairie-wrench', url: 'northline.ca/prairie-wrench', tagline: 'Mobile mechanic · comes to your driveway',
    business: { ...b.business, displayName: 'Prairie Wrench', tier: 'trusted', status: 'active', about: 'Licensed mobile mechanic since 2019 — brakes, batteries, tires and pre-purchase checks at your home or work. Fixed prices, photos of every job.' } };
}

/** The kitchen's dinner menu: mains, sides and drinks with photos. */
const KITCHEN_ID = '01J9ZD3V00000000000000PDB1';
function menu() {
  const b = rec('GET /api/v1/merchants/:id/menus/:id');
  const [base] = b.sections[0].items;
  const item = (id, sectionId, name, description, priceCents, over = {}) => ({ ...base, id, sectionId, name, description, priceCents, hasPhoto: true, soldOut: false, allergens: [], dietary: [], ...over });
  b.sections = [
    { id: 's1', name: 'Pho', sort: 0, items: [
      item('i1', 's1', 'Pho dac biet', 'Rare beef, brisket, tendon and beef balls in a 12-hour broth', 1700, { dietary: ['gluten_free'], soldToday: 18 }),
      item('i3', 's1', 'Pho ga', 'Poached chicken, ginger and scallion', 1550, { dietary: ['gluten_free'], soldToday: 9 }),
      item('i4', 's1', 'Pho chay', 'Tofu, mushrooms and greens in a vegetable broth', 1450, { dietary: ['vegan', 'gluten_free'] }),
    ] },
    { id: 's2', name: 'Rolls & sides', sort: 1, items: [
      item('i5', 's2', 'Fresh spring rolls ×2', 'Shrimp, herbs and vermicelli, peanut sauce', 750, { allergens: ['peanuts', 'shellfish'], soldToday: 12 }),
      item('i6', 's2', 'Crispy imperial rolls ×3', 'Pork and taro, nuoc cham', 800, { soldOut: true, soldToday: 7 }),
    ] },
    { id: 's3', name: 'Drinks', sort: 2, items: [item('i2', 's3', 'Fresh lime soda', 'Salted or sweet', 500, { modifierGroups: [], soldToday: 6 }), item('i7', 's3', 'Vietnamese iced coffee', 'Condensed milk', 600, { allergens: ['milk'], modifierGroups: [] })] },
  ];
  return b;
}

/** The wiper blades in the editor: a filled-in draft with three photos. */
function productDetail() {
  const b = rec('GET /api/v1/merchants/:id/listings/:id');
  const img = (id, seed) => ({ id, url: media(seed), width: 1200, height: 1200, onWhite: true });
  return { ...b, title: 'Wiper blades, 22 in. pair', brand: 'Bosch', mpn: 'ICON-22A', sku: 'WB-22', description: 'Beam blades that stay flexible to −40 °C. Fits most sedans and small SUVs; hook adapter included.',
    bullets: ['Winter-rated rubber', 'Fits 9×3 and 9×4 hooks', 'Sold as a pair'], images: [img('m1', 'category/service.automotive.windshield-repair'), img('m2', 'category/shop.hardware-and-auto.auto-parts'), img('m3', 'category/shop.hardware-and-auto')],
    completeness: { percent: 83, done: 5, total: 6, missing: [] } };
}

/** An application at the business step (a valid ULID: the wizard drops an `m` that is not one) and one in review (the gate). */
const ONB = '01J9ZD3V00000000000000NB01', GATE = '01J9ZD3V00000000000000GATE';
const chk = (id, key, checkType, action, status, registry = null) => ({ id, key, checkType, action, registry, status, reference: null, document: null, expiresOn: null, updatedAt: '2026-10-17T16:00:00Z' });
const application = (merchantId, over) => ({
  merchantId, type: 'provider', status: 'applicant', step: 'business', province: 'AB', workEmail: null, businessTermsAccepted: true, displayName: 'Prairie Wrench', city: 'Calgary', business: null,
  checklist: [chk('V1', 'kyc', 'kyc', 'identity', 'todo'), chk('V2', 'registry', 'registry', 'instant', 'todo'), chk('V3', 'insurance', 'insurance', 'upload', 'todo'), chk('V4', 'bank', 'bank', 'instant', 'todo'), chk('V5', 'mfa', 'mfa', 'instant', 'todo')],
  checksComplete: 0, submittedAt: null, approvedAt: null, ...over,
});
/**
 * The Business step in four provinces (2026-10-19, province legal details): what the api serves for each
 * (web/apps/studio/src/test/legal-forms.json, written and checked by the server's LegalFormsApiTest), each with the
 * structure whose provincial identifiers show the difference.
 */
const LEGAL_FORMS = JSON.parse(readFileSync(new URL('../../../apps/studio/src/test/legal-forms.json', import.meta.url), 'utf8'));
export const ONB_PROVINCES = { AB: '01J9ZD3V00000000000000NB02', ON: '01J9ZD3V00000000000000NB03', BC: '01J9ZD3V00000000000000NB04', QC: '01J9ZD3V00000000000000NB05' };
const STRUCTURE_SHOWN = { AB: 'corp_prov', ON: 'corp_ex', BC: 'sole', QC: 'corp_prov' };
const CITY = { AB: 'Calgary', ON: 'Toronto', BC: 'Vancouver', QC: 'Montréal' };
const savedBusiness = structure => ({ displayName: 'Prairie Wrench', legalName: 'Prairie Wrench Mobile Mechanics Ltd.', structure, gstNumber: null, legalDetails: {}, principals: [], categories: [], profile: {}, documents: [] });
const APPLICATIONS = {
  [ONB]: application(ONB, {}),
  ...Object.fromEntries(Object.entries(ONB_PROVINCES).map(([province, id]) => [id, application(id, { province, city: CITY[province], business: savedBusiness(STRUCTURE_SHOWN[province]) })])),
  [GATE]: application(GATE, {
    status: 'pending', step: 'review', submittedAt: '2026-10-18T15:10:00Z', checksComplete: 5,
    checklist: [chk('V1', 'kyc', 'kyc', 'identity', 'verified'), chk('V2', 'registry', 'registry', 'instant', 'verified'), chk('V3', 'insurance', 'insurance', 'upload', 'submitted'), { ...chk('V4', 'bank', 'bank', 'instant', 'verified'), reference: 'TD ··3391' }, chk('V5', 'mfa', 'mfa', 'instant', 'verified')],
  }),
};


// ---- the console: the recorded answers (the apps' tests, "as of" 2026-09-08 18:00 UTC) moved to the day the shots run,
// so waiting times read "3 h", not "41 d", and the overview's day is today; plus demo-like rows where a list was thin.
const consoleRecorded = JSON.parse(readFileSync(new URL('../fixtures/console.json', import.meta.url), 'utf8'));
const RECORDED_AT = Date.parse('2026-09-08T18:00:00Z');
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const DAY_ONLY = /^\d{4}-\d{2}-\d{2}$/;
/** Every instant and calendar day in `body`, moved by the time since the recording (days by whole days). */
function rebase(body) {
  const shift = Date.now() - RECORDED_AT;
  const days = Math.round(shift / DAY);
  const walk = v => {
    if (typeof v === 'string') {
      if (INSTANT.test(v)) return new Date(Date.parse(v) + shift).toISOString();
      if (DAY_ONLY.test(v)) return new Date(Date.parse(`${v}T12:00:00Z`) + days * DAY).toISOString().slice(0, 10);
      return v;
    }
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, walk(x)]));
    return v;
  };
  return walk(body);
}
const consoleRec = (m, p) => {
  const hit = consoleRecorded.exact[`${m} ${p}`] ?? consoleRecorded.exact[`${m} ${p.split('?')[0]}`] ?? consoleRecorded.pattern[`${m} ${pattern(p)}`];
  return hit ? structuredClone(hit.body) : undefined;
};
const consoleCheck = (id, key, state, registry = null) => ({ id, key, type: key.split(':')[0], registry, status: state === 'passed' ? 'verified' : 'submitted', state, reference: null, expiresAt: null });
/** Applications: a fuller queue — two in review, two waiting for the decision, one with info requested, one approved. */
function applications() {
  const b = consoleRec('GET', '/api/v1/console/verification/applications');
  const base = b.items[0];
  const more = [
    { merchantId: 'M4', businessName: 'Chinook Home Care', legalName: 'Chinook Home Care Ltd.', categories: ['House cleaning'], city: 'Calgary', province: 'AB', risk: 'low', stage: 'review', submittedAt: '2026-09-08T15:10:00Z',
      checks: [consoleCheck('c3k', 'kyc', 'passed'), consoleCheck('c3r', 'registry', 'review'), consoleCheck('c3i', 'insurance', 'passed')] },
    { merchantId: 'M5', businessName: 'Northside Auto Care', legalName: 'Northside Auto Care Inc.', categories: ['Mobile mechanic'], city: 'Edmonton', province: 'AB', risk: 'medium', stage: 'decision', submittedAt: '2026-09-07T21:40:00Z',
      checks: [consoleCheck('c4k', 'kyc', 'passed'), consoleCheck('c4l', 'licence:AMVIC', 'passed', 'AMVIC'), consoleCheck('c4i', 'insurance', 'passed')],
      review: { id: 'RV5', reviewedBy: 'U9', reviewerName: 'Dev Kaur', recommendation: 'approve', reviewedAt: '2026-09-08T16:30:00Z', checkKeys: [], note: null } },
    { merchantId: 'M6', businessName: 'Glenmore Bakery', legalName: 'Glenmore Bakery Ltd.', type: 'seller', categories: ['Bakery'], city: 'Calgary', province: 'AB', risk: 'low', status: 'applicant', decision: 'info_requested', decidedAt: '2026-09-07T18:00:00Z', stage: null, submittedAt: '2026-09-06T14:00:00Z',
      checks: [consoleCheck('c5k', 'kyc', 'passed'), consoleCheck('c5h', 'registry', 'review')] },
  ].map(o => ({ ...base, type: 'provider', status: 'pending', decision: null, decidedAt: null, review: null, structure: 'corp_ab', ...o }));
  b.items = [...b.items, ...more];
  b.pending = (b.pending ?? 2) + 2;
  return rebase(b);
}
/** M1 (stage 2, Decide): its own sheet, with the first person's review — the recorded detail is M2's (stage 1, Review). */
function applicationM1() {
  const d = consoleRec('GET', '/api/v1/console/verification/applications/M2');
  const app = consoleRec('GET', '/api/v1/console/verification/applications').items.find(a => a.merchantId === 'M1');
  const review = { id: 'RV1', reviewedBy: 'U9', reviewerName: 'Dev Kaur', recommendation: 'approve', reviewedAt: '2026-09-08T16:30:00Z', checkKeys: [], note: 'Insurance certificate is on its way; the registry and identity match.', decisionId: null };
  return rebase({ ...d, application: { ...app, stage: 'decision', review }, owners: [{ ...d.owners[0], checkId: null, principalName: 'Kim Ng', status: 'verified', nameMatch: 'match', dobMatch: 'match' }],
    registryReviews: [], reviews: [review] });
}
const AUDIT = [
  ['2026-09-08T17:52:00Z', 'Priya Natarajan', 'admin', 'region.market_stage_changed', 'market', 'red-deer', null],
  ['2026-09-08T17:31:00Z', 'Dev Kaur', 'trust_safety', 'merchant.suspended', 'merchant', 'm4', 'Kensington Garage on Wheels'],
  ['2026-09-08T16:30:00Z', 'Dev Kaur', 'trust_safety', 'merchant.application_reviewed', 'merchant', 'M1', 'Sable & Soda Bar Co.'],
  ['2026-09-08T15:05:00Z', 'Marc Leblanc', 'finance', 'payments.reconciliation_resolved', 'day', '2026-09-05', null],
  ['2026-09-08T14:40:00Z', 'Marc Leblanc', 'finance', 'support.refund_approved', 'refund_request', 'RR7', 'Bridgeland Butcher'],
  ['2026-09-08T13:12:00Z', 'Priya Natarajan', 'admin', 'console.role_granted', 'user', 'u2', null],
  ['2026-09-08T11:48:00Z', null, null, 'trust.flag_raised', 'flag', 'F1', 'Kensington Garage on Wheels'],
  ['2026-09-08T10:20:00Z', 'Dev Kaur', 'trust_safety', 'catalogue.listing_rejected', 'listing', 'L5', 'Glenmore Bakery'],
].map(([at, actorName, role, action, targetType, targetId, businessName], i) => ({ id: `au${i}`, at, actorId: actorName ? `u${i}` : 'system', actorName, role, action, targetType, targetId, businessName }));
const LICENCES = [['LC1', 'Prairie Cellars', 'alcohol', 'AB', 'RLS-778812', 3], ['LC2', 'Kensington Corner Store', 'tobacco', 'AB', 'TP-20931', 7], ['LC3', 'Fraser Valley Cider', 'alcohol', 'BC', 'LCRB-30412', 26]];
const REBASED = ['overview', 'reports', 'verification', 'vetting', 'disputes', 'support', 'orders', 'fulfilment', 'sellers', 'trust', 'payments', 'finance', 'oncall', 'pilot'];
const PROMOS = [
  { id: 'pc1', code: 'FIRSTRUN', description: 'First grocery run', kind: 'amount', amountCents: 1000, minSpendCents: 3500, startsAt: '2026-09-01T06:00:00Z', endsAt: '2026-10-31T06:00:00Z', perCustomerLimit: 1, totalLimit: 2000, fundedBy: 'northline', appliesTo: ['goods', 'food'], active: true, state: 'live', redeemed: 412, discountCents: 412000 },
  { id: 'pc2', code: 'BRAKES15', description: null, kind: 'percent', percent: 15, maxDiscountCents: 4000, minSpendCents: 0, startsAt: '2026-09-05T06:00:00Z', endsAt: '2026-09-30T06:00:00Z', perCustomerLimit: 1, totalLimit: null, fundedBy: 'merchant', merchantId: 'm1', merchantName: 'Prairie Wrench', appliesTo: ['service'], active: true, state: 'live', redeemed: 38, discountCents: 91200 },
  { id: 'pc3', code: 'SUMMERBBQ', description: null, kind: 'percent', percent: 10, maxDiscountCents: 2000, minSpendCents: 5000, startsAt: '2026-07-01T06:00:00Z', endsAt: '2026-08-31T06:00:00Z', perCustomerLimit: 2, totalLimit: 500, fundedBy: 'northline', appliesTo: ['goods'], active: false, state: 'ended', redeemed: 500, discountCents: 610000 },
];

export const SHOTS = {
  consumer: [
    (m, p) => (live(m, p, '/api/v1/search') ? { body: search(p) } : undefined),
    (m, p) => (live(m, p, '/api/v1/public/services') ? { body: SERVICES_LANDING } : undefined),
    // the cart: one line with its photo, the others on the designed fallback
    (m, p) => {
      if (!live(m, p, '/api/v1/cart')) return undefined;
      const b = structuredClone(body('GET /api/v1/cart'));
      for (const g of b.groups) for (const i of g.items) if (i.name === 'Country sourdough') i.imageUrl = media('demo/listing/bakery.sourdough');
      return { body: b };
    },
    // a provider's published page with its sections, an about text, real-sounding reviews and FAQ
    (m, p) => (m === 'POST' && /^\/api\/v1\/public\/storefronts\/[^/]+\/visits/.test(p) ? { status: 204, body: null } : undefined),
    (m, p) => (live(m, p, '/api/v1/storefronts/prairie-wrench') ? { body: {
      ...body('GET /api/v1/storefronts/prairie-wrench'),
      sections: ['hero', 'about', 'services', 'reviews', 'faq', 'area', 'cta'].map(kind => ({ kind, settings: kind === 'faq' ? { pairs: [
        { q: 'Do you work in parkades?', a: 'Yes — up to 2.1 m clearance. Tell us the level and stall when you book.' },
        { q: 'Can I supply my own parts?', a: 'We fit your parts at the labour price; the warranty then covers our work only.' },
        { q: 'What if the job needs more than the quote?', a: 'We stop and ask. Extra parts or time always need your approval in the app.' },
      ] } : {} })),
      business: { ...body('GET /api/v1/storefronts/prairie-wrench').business, about: 'Red Seal technicians who bring the shop to your driveway. Brakes, batteries, diagnostics and seasonal tire swaps — fixed prices for common jobs, written reports with photos, parts at cost.' },
    } } : undefined),
    (m, p) => {
      if (!live(m, p, '/api/v1/public/providers/prairie-wrench')) return undefined;
      const b = structuredClone(body('GET /api/v1/public/providers/prairie-wrench?lang=en'));
      const texts = ['Showed up on time, explained everything and the price matched the quote to the cent.', 'Did my brakes in the parkade at work. Photos of the old pads in the report — great touch.', 'Fast diagnosis on a battery light. Honest about what could wait.'];
      b.reviews.items = b.reviews.items.map((r, i) => ({ ...r, text: texts[i] ?? r.text, author: ['Amara O.', 'Dev P.', 'Jordan K.'][i] ?? r.author }));
      b.services = [...b.services, { id: 's3', name: 'Battery test & replacement', included: null, pricingMode: 'fixed', priceCents: 14900, durationMin: 45, instantBook: true, categorySlug: 'battery-boost-and-replacement', kind: 'visit' },
        { id: 's4', name: 'Seasonal tire swap', included: null, pricingMode: 'fixed', priceCents: 9900, durationMin: 60, instantBook: true, categorySlug: 'tire-change-and-storage', kind: 'visit' }];
      b.nextAvailable = '2026-10-21T16:00:00Z';
      return { body: b };
    },
    // a fallback market (the region configuration's), so the location control names a place for a first visit
    (m, p) => (live(m, p, '/api/v1/geo/markets') ? { body: {
      fallback: { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live', lat: 51.0447, lng: -114.0719 },
      items: [{ code: 'AB', name: 'Alberta', stage: 'live', taxBps: 500, markets: [
        { id: 'mkt-calgary', city: 'Calgary', province: 'AB', stage: 'live' }, { id: 'mkt-edmonton', city: 'Edmonton', province: 'AB', stage: 'live' },
        { id: 'mkt-red-deer', city: 'Red Deer', province: 'AB', stage: 'live' }, { id: 'mkt-lethbridge', city: 'Lethbridge', province: 'AB', stage: 'waitlist' }] },
      { code: 'BC', name: 'British Columbia', stage: 'pilot', taxBps: 1200, markets: [{ id: 'mkt-vancouver', city: 'Vancouver', province: 'BC', stage: 'pilot' }] }],
    } } : undefined),
    (m, p) => (live(m, p, '/api/v1/public/shop') ? { body: { ...body('GET /api/v1/public/shop?lang=en'), shopCount: SHOPS.length, departments: DEPARTMENTS, shops: SHOPS, popular: PRODUCTS.slice(0, 8).map(tile) } } : undefined),
    (m, p) => (m === 'GET' && /^\/api\/v1\/public\/shop\/departments\/[^/?]+/.test(p) ? { body: {
      ...body('GET /api/v1/public/shop/departments/bakery?lang=en'), siblings: DEPARTMENTS, shops: SHOPS.slice(0, 3), shopCount: 3,
      products: PRODUCTS.map(tile).filter((_, i) => [0, 2, 8, 10, 7].includes(i)),
    } } : undefined),
    (m, p) => {
      if (m !== 'GET' || !/^\/api\/v1\/public\/shop\/products\/[^/?]+/.test(p)) return undefined;
      const b = structuredClone(body('GET /api/v1/public/shop/products/P1?lang=en'));
      b.offers = b.offers.map((o, i) => ({ ...o, images: i === 0 ? [media('demo/listing/bakery.sourdough'), media('demo/merchant/bakery'), media('category/shop.food-and-grocery.bakery')] : [] }));
      return { body: b };
    },
    (m, p) => (live(m, p, '/api/v1/public/services/mobile-mechanic/providers') ? { body: {
      ...body('GET /api/v1/public/services/mobile-mechanic/providers?lang=en'),
      items: [...body('GET /api/v1/public/services/mobile-mechanic/providers?lang=en').items, ...['Northside Auto Care', 'Chinook Mobile Mechanics'].map((name, i) => ({
        merchantId: `m${3 + i}`, slug: name.toLowerCase().replace(/[^a-z]+/g, '-'), name, tier: 'trusted',
        brandColor: body('GET /api/v1/public/services/mobile-mechanic/providers?lang=en').items[0].brandColor, blurb: 'Comes to your driveway · fixed prices, receipts on every job.',
        rating: 4.6 - i * 0.2, reviewCount: 40 + i * 13, onTimePct: 95, disputePct: 0.5, rebookPct: 60, fromCents: 8900 + i * 1000, pricingMode: 'fixed', instantBook: i === 0, nextAvailable: '2026-10-21T16:00:00Z', zones: [],
      }))],
    } } : undefined),
  ],
  studio: [
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/jobs`) ? { body: { items: weekJobs(p) } } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/calendar-cells`) ? { body: weekCells(p) } : undefined),
    (m, p) => { const id = new RegExp(`^/api/v1/merchants/${PROVIDER_ID}/jobs/([^/?]+)$`).exec(p)?.[1]; const d = m === 'GET' && id ? jobDetail(id) : undefined; return d ? { body: d } : undefined; },
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/availability/hours`) ? { body: availabilityHours() } : undefined),
    (m, p) => (m === 'POST' && p.split('?')[0] === `/api/v1/merchants/${PROVIDER_ID}/availability/preview` ? { body: previewSlots() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/availability/services`) ? { body: { items: [{ id: 's1', name: 'Brake inspection', durationMin: 45 }, { id: 's2', name: 'Brake pads and rotors', durationMin: 90 }, { id: 's3', name: 'Oil change', durationMin: 45 }] } } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/orders`) ? { body: sellerOrders() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/listings/p1`) ? { body: productDetail() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/listings/p1/french`) ? { body: { rule: 'warn', title: 'Balais d’essuie-glace, 22 po, paire', description: null, missing: true } } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/listings/imports`) ? { body: { items: [
      { id: 'b3', fileName: 'winter-stock-oct.xlsx', template: 'auto_parts', rowCount: 64, createCount: 12, updateCount: 52, errorCount: 0, errors: [], status: 'imported', createdAt: '2026-10-16T15:00:00Z', importedAt: '2026-10-16T15:02:00Z' },
      { id: 'b2', fileName: 'price-update.csv', template: 'auto_parts', rowCount: 118, createCount: 0, updateCount: 118, errorCount: 0, errors: [], status: 'imported', createdAt: '2026-10-02T15:00:00Z', importedAt: '2026-10-02T15:01:00Z' },
      { id: 'b1', fileName: 'wipers-sept.xlsx', template: 'auto_parts', rowCount: 256, createCount: 212, updateCount: 38, errorCount: 1, errors: [], status: 'imported', createdAt: '2026-09-29T15:00:00Z', importedAt: '2026-09-29T15:04:00Z' },
    ] } } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/listings`) ? { body: { items: PRODUCTS_SOLD } } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/listings`) ? { body: { items: SERVICES_SOLD } } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/threads`) ? { body: threads() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/threads/t1`) ? { body: thread() } : undefined),
    (m, p) => (m === 'POST' && /\/threads\/[^/]+\/read$/.test(p) ? { status: 204, body: null } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/earnings`) ? { body: earnings() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/earnings/ledger`) ? { body: ledger() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/payouts/overview`) ? { body: payoutOverview() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/payouts`) ? { body: payoutHistory() } : undefined),
    (m, p) => (m === 'GET' && /^\/api\/v1\/merchants\/[^/]+\/reports$/.test(p.split('?')[0]) && /period=(30d|12mo)/.test(p) ? { body: salesReport(p) } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/reports`) ? { body: report() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/threads`) ? { body: threads() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${SELLER_ID}/payouts/overview`) ? { body: payoutOverview() } : undefined),
    (m, p) => (m === 'GET' && p.startsWith(`/api/v1/merchants/${PROVIDER_ID}/reviews?`) ? { body: reviews() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/storefront`) ? { body: storefront() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${KITCHEN_ID}/menus/mn1`) ? { body: menu() } : undefined),
    (m, p) => (live(m, p, `/api/v1/merchants/${PROVIDER_ID}/quote-requests`) ? { body: quoteRequests() } : undefined),
    (m, p) => { const id = /^\/api\/v1\/merchants\/([^/]+)\/onboarding$/.exec(p)?.[1]; return m === 'GET' && APPLICATIONS[id] ? { body: APPLICATIONS[id] } : undefined; },
    (m, p) => { const id = /^\/api\/v1\/merchants\/([^/]+)\/onboarding\/legal-forms$/.exec(p)?.[1]; const province = APPLICATIONS[id]?.province; return m === 'GET' && LEGAL_FORMS[province] ? { body: LEGAL_FORMS[province] } : undefined; },
    (m, p) => (m === 'GET' && /^\/api\/v1\/merchants\/[^/]+\/dashboard$/.test(p) ? { body: todayDashboard(p.includes('PWP1') ? SELLER_DASHBOARD : p.includes(KITCHEN_ID) ? KITCHEN_DASHBOARD : DASHBOARD) } : undefined),
    // the live stream (EventSource) has nothing to say in a screenshot
    (m, p) => (m === 'GET' && /^\/api\/v1\/merchants\/[^/]+\/live$/.test(p) ? { status: 204, body: null } : undefined),
    (m, p) => (m === 'GET' && /^\/api\/v1\/merchants\/[^/]+\/availability\/time-off/.test(p) ? { body: { items: [] } } : undefined),
  ],
  console: [
    (m, p) => (live(m, p, '/api/v1/console/promotions/codes') ? { body: { items: rebase(PROMOS) } } : undefined),
    // the signed-in person's email on My profile (the recorded session has none)
    (m, p) => (m === 'GET' && p === '/bff/session' && state.console ? { body: { user: { id: '01J9ZD3V00000000000000PNA1', firstName: 'Priya', lastName: 'Natarajan', initials: 'PN', email: 'priya.natarajan@example.test', locale: 'en-CA' }, acr: 'mfa' } } : undefined),
    (m, p) => (m === 'GET' && p.split('?')[0] === '/api/v1/console/verification/applications' ? { body: applications() } : undefined),
    (m, p) => (m === 'GET' && p === '/api/v1/console/verification/applications/M1' ? { body: applicationM1() } : undefined),
    // Applications › Licences and › Age checks (the age-restricted sales views; the a11y sweep never recorded them)
    (m, p) => (live(m, p, '/api/v1/console/vetting/licences') ? { body: { items: LICENCES.map(([id, business, ageClass, province, number, submitted]) => ({
      licence: { id, merchantId: `m-${id}`, ageClass, province, licenceNumber: number, documentId: `doc-${id}`, expiresOn: '2027-03-31', status: 'pending', submittedAt: new Date(Date.now() - submitted * 3600000).toISOString(), decidedAt: null, rejectReason: null, note: null },
      businessName: business, businessProvince: province })) } } : undefined),
    (m, p) => (live(m, p, '/api/v1/console/vetting/age-checks') ? { body: {
      from: new Date(Date.now() - 30 * DAY).toISOString(), to: new Date().toISOString(), province: null,
      verifications: { verified: 1284, failed: 37 }, failures: { document_expired: 21, selfie_face_mismatch: 16 }, handoffs: { passed: 1162, refused: 19 },
      refusals: { nobody_of_age: 9, no_id: 6, mismatch: 3, underage: 1 }, byPlace: { door: 940, counter: 241 },
      recent: [['H1', 'NL-48231', 'door', 'nobody_of_age', 2], ['H2', 'NL-48217', 'counter', 'no_id', 5], ['H3', 'NL-48190', 'door', 'mismatch', 9], ['H4', 'NL-48166', 'door', 'no_id', 26]]
        .map(([checkId, orderId, place, reason, h]) => ({ checkId, orderId, orderType: 'goods', province: 'AB', requiredAge: 18, actorRole: place === 'door' ? 'courier' : 'seller', place, reason, at: new Date(Date.now() - h * 3600000).toISOString() })),
    } } : undefined),
    (m, p) => (live(m, p, '/api/v1/console/vetting/age-rules') ? { body: { items: [
      { province: 'AB', ageClass: 'alcohol', minimumAge: 18, deliveryAllowed: true, pickupAllowed: true, deliveryFrom: '10:00:00', deliveryUntil: '02:00:00', source: 'Provincial liquor regulator', confirmed: false },
      { province: 'AB', ageClass: 'tobacco', minimumAge: 18, deliveryAllowed: true, pickupAllowed: true, deliveryFrom: null, deliveryUntil: null, source: 'Provincial tobacco rules', confirmed: false },
      { province: 'BC', ageClass: 'alcohol', minimumAge: 19, deliveryAllowed: true, pickupAllowed: true, deliveryFrom: '09:00:00', deliveryUntil: '23:00:00', source: 'Provincial liquor regulator', confirmed: false },
    ] } } : undefined),
    // sellers & providers: shops and a kitchen beside the recorded providers
    (m, p) => {
      if (m !== 'GET' || p.split('?')[0] !== '/api/v1/console/sellers') return undefined;
      const b = consoleRec('GET', '/api/v1/console/sellers');
      const cat = (id, en) => ({ id, names: { en } });
      const row = (id, name, c, type, tier, quality, gmv, disputes, flags = [], status = 'active', province = 'AB') => ({ id, name, category: c, type, province, city: 'Calgary', tier, status, quality, gmv90Cents: gmv, disputeRate: disputes, flags });
      b.items = [...b.items,
        row('m5', 'Glenmore Bakery', cat('c5', 'Bakery'), 'seller', 'trusted', 88, 1460000, 0.004),
        row('m6', 'Bridgeland Butcher', cat('c6', 'Butcher'), 'seller', 'trusted', 84, 1210000, 0.009),
        row('m7', 'Pho Dau Bo', cat('c7', 'Vietnamese'), 'kitchen', 'registered', 81, 640000, 0.006, [{ kind: 'check_expiring', checkType: 'inspection', days: 12 }]),
        row('m8', 'Chinook Home Care', cat('c8', 'House cleaning'), 'provider', 'registered', null, 0, null, [], 'pending'),
        row('m9', 'Sable & Soda', cat('c9', 'Events & bar'), 'provider', 'trusted', 82, 2140000, 0.012, [], 'paused')];
      return { body: rebase(b) };
    },
    // tonight's runs: the stuck one is 14 minutes past its next stop's ETA (the recording had it at the minute)
    (m, p) => {
      if (m !== 'GET' || p.split('?')[0] !== '/api/v1/console/fulfilment/runs') return undefined;
      const b = rebase(consoleRec(m, p));
      for (const r of b.items) r.nextEta = new Date(Date.now() + (r.late ? -14 : 25 + r.stopsDone * 6) * 60000).toISOString();
      return { body: b };
    },
    // the support queue and the team: a few more rows than the tests needed
    (m, p) => {
      if (m !== 'GET' || p.split('?')[0] !== '/api/v1/console/support/tickets') return undefined;
      const b = consoleRec(m, p);
      const tk = (id, code, requesterType, requesterName, subject, priority, state, agentName, minutes, lang = 'en') => ({ ...b.items[0], id, code, requesterType, requesterName, subject, priority, state,
        agentId: agentName ? 'U9' : null, agentName, lang, createdAt: new Date(RECORDED_AT - minutes * 60000).toISOString(), slaDueAt: new Date(RECORDED_AT + 60 * 60000).toISOString() });
      b.items = [...b.items,
        tk('T3', 'HD-4468', 'seller', 'Glenmore Bakery', 'Payout shows on hold since Friday', 'priority', 'in_progress', 'Dev K.', 95),
        tk('T4', 'HD-4466', 'customer', 'A. Osei', 'Brake job booked twice — refund one?', 'normal', 'waiting', 'Dev K.', 180),
        tk('T5', 'HD-4463', 'kitchen', 'Pho Dau Bo', 'Menu photo rejected, not sure why', 'normal', 'new', null, 240, 'fr'),
        tk('T6', 'HD-4459', 'customer', 'J. Tremblay', 'Code promo refusé au paiement', 'normal', 'resolved', 'Dev K.', 420, 'fr')];
      return { body: rebase(b) };
    },
    (m, p) => {
      if (!live(m, p, '/api/v1/console/team')) return undefined;
      const b = consoleRec(m, p);
      b.members = [{ id: 'u0', name: 'Priya Natarajan', email: 'priya.natarajan@example.test', roles: ['admin', 'staff'] }, ...b.members,
        { id: 'u3', name: 'Sam Okafor', email: 'sam@example.test', roles: ['dispatch', 'staff'] }, { id: 'u4', name: 'Léa Gagnon', email: 'lea@example.test', roles: ['support', 'staff'] },
        { id: 'u5', name: 'Noor Haddad', email: 'noor@example.test', roles: ['support_lead', 'privacy', 'staff'] }];
      b.roles = [...b.roles,
        { role: 'trust_safety', people: 1, screens: ['overview', 'disputes', 'sellers', 'verify', 'vetting', 'trust', 'support', 'team'], actions: ['suspend', 'decide', 'verify', 'vet'] },
        { role: 'dispatch', people: 1, screens: ['overview', 'orders', 'delivery'], actions: ['dispatch'] },
        { role: 'support', people: 2, screens: ['overview', 'orders', 'support', 'sellers'], actions: ['support'] }];
      return { body: b };
    },
    // trust & safety: the five open flags the overview counts
    (m, p) => {
      if (m !== 'GET' || p.split('?')[0] !== '/api/v1/console/trust/flags/queue') return undefined;
      const b = consoleRec(m, p);
      const flag = (id, rule, targetType, businessName, explanation, at) => ({ ...b.items[0], id, rule, targetType, businessName, explanation, createdAt: at });
      b.items.splice(2, 0,
        flag('F4', 'floor_breach', 'merchant', 'Bow River Mechanics', 'Quality 78 for 3 weeks · Trusted floor 80', '2026-09-08T09:00:00Z'),
        flag('F5', 'no_show', 'customer', null, '2 no-shows in 30 days · bookings with Prairie Wrench', '2026-09-07T22:15:00Z'),
        flag('F6', 'review_screened', 'review', 'Glenmore Bakery', 'A review with a phone number, masked before publishing', '2026-09-07T19:40:00Z'));
      return { body: rebase(b) };
    },
    (m, p) => (m === 'GET' && p.split('?')[0] === '/api/v1/console/audit' ? { body: rebase({ items: AUDIT, next: null }) } : undefined),
    // a place with nothing in it (the empty state) and a screen whose data failed (the error state): ui-shots routes
    (m, p) => (m === 'GET' && p.startsWith('/api/v1/console/disputes?') && p.includes('province=QC') ? { body: { summary: { forAgent: 0, inSellerWindow: 0, closedThisWeek: 0 }, items: [] } } : undefined),
    (m, p) => (m === 'GET' && p.startsWith('/api/v1/console/reports?') && p.includes('province=QC') ? { status: 503, body: { type: 'about:blank', title: 'Service Unavailable', status: 503 } } : undefined),
    // everything else the console asks: the recorded answer, moved to today (the session and roles stay the overrides')
    // (only the screens recorded around that day: the privacy and retention tests used dates of their own)
    (m, p) => (m === 'GET' && REBASED.some(r => p.startsWith(`/api/v1/console/${r}`))
      ? (b => (b === undefined ? undefined : { body: rebase(b) }))(consoleRec(m, p)) : undefined),
  ],
};
