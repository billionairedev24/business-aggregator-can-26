// S-119 checkout: the three money-moving journeys of the consumer site and app, as their screens call the api, each
// paid with the customer's saved card (the fake gateway locally, stripe-mock or Stripe test mode on staging — Stripe
// confirms the card in the browser, so the api sees the same calls either way). The calls the S-113 checkout SLO
// measures (`POST /api/v1/me/checkouts`, `…/{id}/place`, `/api/v1/me/bookings/checkout`) feed slo_checkout_latency
// and slo_checkout_errors; food orders (`/api/v1/me/food-orders…`) are measured the same way under their own tag.
//
//   food     a pickup order from one of the KDS kitchens: quote → start (card authorized) → confirm (placed). These are
//            the tickets the kds scenario's screens receive.
//   goods    1–3 products of different shops into the cart → checkout page → quote → start → place
//   booking  the provider's calendar → hold a free slot → pay into escrow → confirm
import { check } from 'k6';
import { customer as nextCustomer, data, flow, get, json, key, pick, post } from '../lib/api.js';
import { checkoutErrors, checkoutLatency } from '../lib/slo.js';
import { kitchensWithScreens } from '../lib/kitchens.js';

/** Customers whose card this VU already saved (the fake gateway keeps cards in memory, per api process). */
const withCard = new Set();

function slo(res, call) {
  checkoutLatency.add(res.timings.duration, { call });
  checkoutErrors.add(res.status >= 500 || res.status === 0, { call });
}

/** Payment methods page: save a card once (SetupIntent → confirmed by "Stripe" → attached), then list the cards. */
function savedCard(customer) {
  if (!withCard.has(customer)) {
    const intent = json(post('/api/v1/me/payment-methods/setup-intents', {}, customer,
      { name: '/api/v1/me/payment-methods/setup-intents', flow: 'checkout' }));
    if (intent && intent.setupIntentId) {
      post('/api/v1/me/payment-methods', { setupIntentId: intent.setupIntentId }, customer,
        { name: '/api/v1/me/payment-methods', flow: 'checkout' });
    }
    withCard.add(customer);
  }
  const cards = get('/api/v1/me/payment-methods', customer, { name: '/api/v1/me/payment-methods (list)', flow: 'checkout' });
  return check(cards, { 'saved card listed': r => r.status === 200 && (json(r) || { items: [] }).items.length > 0 });
}


export function checkoutFood() {
  const customer = nextCustomer();
  const kitchen = data.kitchens[Math.floor(Math.random() * kitchensWithScreens(data.kitchens.length))];
  if (!kitchen.items || kitchen.items.length === 0) return;
  savedCard(customer);
  const item = pick(kitchen.items);
  const order = {
    merchantId: kitchen.merchantId, mode: 'pickup', scheduledFor: null, combos: [], delivery: null,
    items: [{ itemId: item, qty: 4 + Math.floor(Math.random() * 2), optionIds: [], note: null }], // over the $15 minimum
    tip: { kind: 'none', value: 0 },
  };
  const quote = post('/api/v1/me/food-orders/quote', order, customer, { name: '/api/v1/me/food-orders/quote', flow: 'checkout' });
  const k = key('food');
  const started = post('/api/v1/me/food-orders', order, customer,
    { name: '/api/v1/me/food-orders', flow: 'checkout', headers: { 'Idempotency-Key': k } });
  slo(started, 'food_start');
  const body = json(started);
  if (!flow(check(started, { 'food order started': r => r.status === 201 }) && quote.status === 200, 'food start', started)) return;
  const placed = post(`/api/v1/me/food-orders/${body.orderId}/confirm`, null, customer,
    { name: '/api/v1/me/food-orders/{id}/confirm', flow: 'checkout', headers: { 'Idempotency-Key': `${k}-confirm` } });
  slo(placed, 'food_confirm');
  flow(check(placed, { 'food order placed': r => r.status === 200 }), 'food confirm', placed);
}

export function checkoutGoods() {
  const customer = nextCustomer();
  savedCard(customer);
  const shops = data.sellers.filter(s => s.offers && s.offers.length);
  const lines = 1 + Math.floor(Math.random() * 3);
  for (let i = 0; i < lines; i++) {
    const shop = pick(shops);
    const added = post('/api/v1/cart/items', { offerId: pick(shop.offers), qty: 1 + Math.floor(Math.random() * 2) },
      customer, { name: '/api/v1/cart/items', flow: 'checkout' });
    if (!flow(check(added, { 'added to cart': r => r.status === 201 }), 'cart add', added)) return;
  }
  const setup = json(get(`/api/v1/me/checkout?market=${encodeURIComponent(data.meta.market)}`, customer,
    { name: '/api/v1/me/checkout', flow: 'checkout' }));
  if (!flow(setup && setup.addresses && setup.addresses.length > 0, 'checkout page', null)) return;
  const address = setup.addresses.find(a => a.isDefault) || setup.addresses[0];
  const option = pick(setup.options.length ? setup.options : [{ kind: 'direct' }]);
  const form = { kind: option.kind, windowId: option.windowId || null, address: { addressId: address.id }, substitution: 'similar' };
  post('/api/v1/me/checkout/quote', form, customer, { name: '/api/v1/me/checkout/quote', flow: 'checkout' });
  const k = key('goods');
  const started = post('/api/v1/me/checkouts', form, customer,
    { name: '/api/v1/me/checkouts', flow: 'checkout', headers: { 'Idempotency-Key': k } });
  slo(started, 'checkouts');
  if (!flow(check(started, { 'checkout started': r => r.status === 201 }), 'checkout start', started)) return;
  const placed = post(`/api/v1/me/checkouts/${json(started).checkoutId}/place`, null, customer,
    { name: '/api/v1/me/checkouts/{id}/place', flow: 'checkout', headers: { 'Idempotency-Key': `${k}-place` } });
  slo(placed, 'place');
  flow(check(placed, { 'order placed': r => r.status === 201 }), 'checkout place', placed);
}

const BOOKING = {
  description: 'Grinding noise when braking, worse when cold.',
  vehicle: { year: '2018', make: 'Honda', model: 'Civic', plate: 'BKT 4471' },
  addressLine: '1204 17 Ave SW', area: 'Beltline', spot: 'Driveway', accessNote: 'Ring the bell',
  contactPhone: '+1 403 555 0123', agreePolicies: true, agreeTerms: true,
};

export function checkoutBooking() {
  const customer = nextCustomer();
  const provider = pick(data.providers.filter(p => p.services && p.services.length));
  const serviceId = pick(provider.services);
  savedCard(customer);
  const calendar = json(get(`/api/v1/public/providers/${provider.slug}/slots?serviceId=${serviceId}&days=7`, customer,
    { name: '/api/v1/public/providers/{slug}/slots', flow: 'checkout' }));
  const free = calendar ? calendar.days.flatMap(d => d.slots.filter(s => s.free).map(s => s.startsAt)) : [];
  if (!flow(free.length > 0, 'booking calendar', null)) return;
  let hold;
  for (let attempt = 0; attempt < 3; attempt++) { // another customer may take the slot first (409 slot_taken)
    hold = post('/api/v1/me/bookings/holds', { slug: provider.slug, serviceId, startsAt: pick(free) }, customer,
      { name: '/api/v1/me/bookings/holds', flow: 'checkout', expected: [201, 409] });
    if (hold.status === 201) break;
  }
  if (!flow(check(hold, { 'slot held': r => r.status === 201 }), 'booking hold', hold)) return;
  const holdId = json(hold).holdId;
  const k = key('booking');
  const paid = post('/api/v1/me/bookings/checkout', Object.assign({ holdId, serviceId }, BOOKING), customer,
    { name: '/api/v1/me/bookings/checkout', flow: 'checkout', headers: { 'Idempotency-Key': k } });
  slo(paid, 'booking_checkout');
  if (!flow(check(paid, { 'deposit authorized': r => r.status === 200 && json(r).status === 'authorized' }),
    'booking checkout', paid)) return;
  const confirmed = post(`/api/v1/me/bookings/holds/${holdId}/confirm`, null, customer,
    { name: '/api/v1/me/bookings/holds/{id}/confirm', flow: 'checkout', headers: { 'Idempotency-Key': `${k}-confirm` } });
  slo(confirmed, 'booking_confirm');
  flow(check(confirmed, { 'booking confirmed': r => r.status === 201 }), 'booking confirm', confirmed);
}
