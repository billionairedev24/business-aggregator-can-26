import type { FixtureArea, FixtureContext, FixtureRequest } from './context';
import { MARKETS, PROVINCES } from './geo';

/**
 * Journey B's api (S-99) on the fixture backend: the Shop's public pages, search, the cart (guest-keyed and merged at
 * sign-in like S-51), checkout with the payment stand-in (or Stripe-shaped intents), the order and its tracking, and
 * problem reports. Made-up shops and products; the market is a fixture city (Sampleville), taxes from the fixture
 * provinces' rates.
 */
const DAY = 86_400_000;
const HOUR = 3_600_000;

export const SHOPS = [
  { merchantId: 'm-bakery', name: 'Maple Lane Bakery', tier: 'master', departmentSlug: 'bakery', departmentName: 'Bakery' },
  { merchantId: 'm-butcher', name: 'Riverside Butcher', tier: 'trusted', departmentSlug: 'butcher', departmentName: 'Butcher' },
  { merchantId: 'm-greens', name: 'Leafy Lane Greens', tier: 'master', departmentSlug: 'produce', departmentName: 'Produce' },
] as const;

interface FixtureProduct {
  productId: string;
  offerId: string;
  name: string;
  merchantId: string;
  unit: string;
  priceCents: number;
  stock: number;
  tonight: boolean;
  dietary: string[];
  description?: string;
  variants?: Array<{ variantId: string; value: string }>;
}

export const PRODUCTS: FixtureProduct[] = [
  { productId: 'p-sourdough', offerId: 'o-sourdough', name: 'Country sourdough', merchantId: 'm-bakery', unit: '900 g loaf', priceCents: 750, stock: 20, tonight: true, dietary: [],
    description: 'Naturally leavened, 36-hour ferment. Sliced on request.', variants: [{ variantId: 'v-whole', value: 'Whole' }, { variantId: 'v-sliced', value: 'Sliced' }] },
  { productId: 'p-rye', offerId: 'o-rye', name: 'Sourdough rye', merchantId: 'm-bakery', unit: '700 g', priceCents: 850, stock: 8, tonight: false, dietary: [] },
  { productId: 'p-baguette', offerId: 'o-baguette', name: 'Baguette', merchantId: 'm-bakery', unit: 'each', priceCents: 450, stock: 30, tonight: true, dietary: [] },
  { productId: 'p-ribeye', offerId: 'o-ribeye', name: 'Ribeye, AAA', merchantId: 'm-butcher', unit: '340 g', priceCents: 1850, stock: 12, tonight: true, dietary: ['halal'] },
  { productId: 'p-thighs', offerId: 'o-thighs', name: 'Chicken thighs', merchantId: 'm-butcher', unit: '1 kg', priceCents: 1100, stock: 6, tonight: false, dietary: ['halal'] },
  { productId: 'p-kale', offerId: 'o-kale', name: 'Kale & chard mix', merchantId: 'm-greens', unit: 'bunch', priceCents: 425, stock: 2, tonight: true, dietary: [] },
];

interface Line { itemId: string; offerId: string; variantId?: string; qty: number }
interface Order {
  orderId: string;
  ref: string;
  state: string;
  placedAt: number;
  lines: Array<Line & { name: string; merchantId: string; unitCents: number }>;
  kind: 'pooled' | 'direct';
  feeCents: number;
  taxCents: number;
  deliveredAt?: number;
  confirmedAt?: number;
  courier?: { courierName: string; stopsBefore: number; pin: string } | null;
  reported: string[];
  /** The courier's proof (default photo); an order with an ID check at the door never has a photo to show. */
  proof?: 'photo' | 'pin';
  idCheck?: boolean;
}

export interface ShopFixtureState {
  /** Carts by owner: `user` (signed in) or the guest id. */
  carts: Map<string, Line[]>;
  /** `fake` = the api's payment stand-in (every intent authorized); `stripe` = intents to confirm with Stripe's SDK. */
  provider: 'fake' | 'stripe';
  /** What `GET /me/checkout` says about paying: `required` asks for `X-Step-Up: fixture-proof`. */
  stepUp: 'none' | 'required' | 'enrol';
  addresses: Array<{ id: string; street: string; unit?: string; city: string; province: string; postal: string; note?: string; isDefault: boolean }>;
  cards: Array<{ id: string; brand: string; last4: string; expMonth: number; expYear: number; isDefault: boolean; addedAt: string }>;
  orders: Map<string, Order>;
  checkouts: Map<string, { checkoutId: string; order: Order; placed: boolean }>;
  /** Idempotency-Key → the first answer (replayed). */
  idempotent: Map<string, unknown>;
  upcoming: boolean;
  next: number;
  /** Push installations (`PUT|DELETE /me/devices/{id}`, S-102), by installation id. */
  devices: Map<string, Record<string, unknown>>;
  /** Photos uploaded for reports (`POST /me/case-uploads`), and the reports sent. */
  uploads: string[];
  reports: Array<Record<string, unknown>>;
}

/** The door photo the fixture serves (a 1 × 1 PNG). */
export const FIXTURE_PROOF_PHOTO =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

export const FIXTURE_PROOF = 'fixture-proof';

export function newShopState(): ShopFixtureState {
  return {
    carts: new Map(),
    provider: 'fake',
    stepUp: 'none',
    addresses: [],
    cards: [{ id: 'pm_fixture_visa', brand: 'Visa', last4: '4471', expMonth: 9, expYear: 2028, isDefault: true, addedAt: '2026-09-01T00:00:00Z' }],
    orders: new Map(),
    checkouts: new Map(),
    idempotent: new Map(),
    upcoming: true,
    next: 48213,
    devices: new Map(),
    uploads: [],
    reports: [],
  };
}

const served = (city: string | null) => MARKETS.some((m) => m.stage === 'live' && m.city.toLowerCase() === (city ?? '').toLowerCase());

export function shopFixtures(ctx: FixtureContext, state: ShopFixtureState): FixtureArea {
  const midnight = () => Math.floor(ctx.now() / DAY) * DAY;
  const run = (day: 'today' | 'tomorrow') => {
    const base = midnight() + (day === 'today' ? 0 : DAY);
    const start = day === 'today' ? base + 18 * HOUR : base + 8 * HOUR;
    return {
      windowId: `w-${day}`,
      label: null,
      day,
      startsAt: new Date(start).toISOString(),
      endsAt: new Date(start + 3 * HOUR).toISOString(),
      orderBy: new Date(start - 41 * 60_000).toISOString(),
      packBy: new Date(start - 15 * 60_000).toISOString(),
      feeCents: day === 'today' ? 299 : 199,
      households: day === 'today' ? 5 : 0,
    };
  };
  const shopOf = (merchantId: string) => SHOPS.find((s) => s.merchantId === merchantId)!;
  const card = (p: FixtureProduct) => ({
    productId: p.productId, offerId: p.offerId, name: p.name, merchantId: p.merchantId, shopName: shopOf(p.merchantId).name, unit: p.unit,
    priceCents: p.priceCents, imageUrl: null, sellers: 1, run: p.tonight ? run('today') : run('tomorrow'),
  });
  const shopCard = (s: (typeof SHOPS)[number]) => ({ ...s, products: PRODUCTS.filter((p) => p.merchantId === s.merchantId).length, run: run('today') });
  const departments = SHOPS.map((s) => ({ slug: s.departmentSlug, name: s.departmentName, shops: 1 }));
  const signedIn = (req: FixtureRequest) => (req.headers.authorization ?? '').startsWith('DPoP ');
  const unauthorized = () => ctx.answer(401, { error: 'invalid_token' });
  const productOf = (offerId: string) => PRODUCTS.find((p) => p.offerId === offerId);

  function ownerOf(req: FixtureRequest): string | null {
    const guest = req.headers['x-northline-guest'];
    if (signedIn(req)) {
      // a signed-in call that still carries a guest id merges the guest's cart first (S-51)
      if (guest && state.carts.has(guest)) {
        const mine = state.carts.get('user') ?? [];
        for (const l of state.carts.get(guest)!) {
          const same = mine.find((m) => m.offerId === l.offerId && m.variantId === l.variantId);
          if (same) same.qty = Math.min(99, same.qty + l.qty);
          else mine.push(l);
        }
        state.carts.set('user', mine);
        state.carts.delete(guest);
      }
      return 'user';
    }
    return guest ?? null;
  }

  function cartView(owner: string | null) {
    const lines = owner ? (state.carts.get(owner) ?? []) : [];
    const groups = SHOPS.map((s) => ({
      merchantId: s.merchantId,
      shopName: s.name,
      items: lines
        .map((l) => ({ l, p: productOf(l.offerId)! }))
        .filter(({ p }) => p.merchantId === s.merchantId)
        .map(({ l, p }) => ({
          itemId: l.itemId, merchantId: p.merchantId, offerId: p.offerId, variantId: l.variantId ?? null, productId: p.productId, name: p.name,
          option: p.variants?.find((v) => v.variantId === l.variantId)?.value ?? null, unit: p.unit, imageUrl: null, unitCents: p.priceCents,
          qty: l.qty, lineCents: p.priceCents * l.qty, stock: p.stock, available: true, handlingDays: p.tonight ? 0 : 1,
        })),
    })).filter((g) => g.items.length > 0);
    const items = groups.flatMap((g) => g.items);
    return { itemCount: items.reduce((n, i) => n + i.qty, 0), shopCount: groups.length, subtotalCents: items.reduce((n, i) => n + i.lineCents, 0), groups };
  }

  const options = () => [
    { id: 'w-today', kind: 'pooled', ...run('today') },
    { id: 'w-tomorrow', kind: 'pooled', ...run('tomorrow') },
    { id: 'direct', kind: 'direct', windowId: null, day: null, startsAt: null, endsAt: null, orderBy: null, packBy: null, feeCents: 999, households: 0, etaMinutes: 45 },
  ];

  /** The checkout body's choices, checked like the api (422 with the api's messages). */
  function plan(body: Record<string, unknown>) {
    const errors: Array<{ field: string; rule: string; message: string }> = [];
    const kind = String(body.kind ?? '');
    const option = options().find((o) => (kind === 'direct' ? o.kind === 'direct' : o.kind === 'pooled' && o.windowId === body.windowId));
    if (!option) errors.push({ field: 'windowId', rule: 'required', message: 'Choose a delivery window.' });
    const a = (body.address ?? {}) as Record<string, string | undefined>;
    let province = 'XA';
    if (a.addressId) {
      const saved = state.addresses.find((x) => x.id === a.addressId);
      if (!saved) errors.push({ field: 'address.addressId', rule: 'unknown', message: 'Choose a delivery address.' });
      else province = saved.province;
    } else {
      if (!a.street?.trim()) errors.push({ field: 'address.street', rule: 'required', message: 'Enter the street address.' });
      if (!a.city?.trim()) errors.push({ field: 'address.city', rule: 'required', message: 'Enter the city.' });
      if (!PROVINCES.some((p) => p.code === a.province)) errors.push({ field: 'address.province', rule: 'required', message: 'Choose a Canadian province or territory.' });
      if (!/^[A-Z]\d[A-Z] ?\d[A-Z]\d$/i.test(a.postal ?? '')) errors.push({ field: 'address.postal', rule: 'format', message: 'Enter a Canadian postal code, like T2P 1B5.' });
      province = a.province ?? province;
    }
    const cart = cartView('user');
    const fee = option?.feeCents ?? 0;
    const bps = PROVINCES.find((p) => p.code === province)?.taxBps ?? 500;
    const tax = Math.round(((cart.subtotalCents + fee) * bps) / 10_000);
    return { errors, option, cart, fee, tax, type: bps === 500 ? 'gst' : 'hst', percent: bps / 100 };
  }

  function tracking(o: Order) {
    const shops = [...new Set(o.lines.map((l) => l.merchantId))].map((id) => ({ merchantId: id, name: shopOf(id).name, items: o.lines.filter((l) => l.merchantId === id).length, packed: o.state !== 'placed' }));
    const order = ['placed', 'packing', 'picked_up', 'delivered'];
    const at = Math.max(0, order.indexOf(o.state === 'confirmed' ? 'delivered' : o.state));
    const keys = ['paid', 'packing', 'pickup', 'delivered'] as const;
    const steps = keys.map((key, i) => ({ key, state: i === 0 || i < at || (o.state === 'confirmed' && i === 3) ? 'done' : i === at || (at === 0 && i === 1) ? 'current' : 'todo' }));
    const subtotal = o.lines.reduce((n, l) => n + l.unitCents * l.qty, 0);
    const r = run('today');
    return {
      orderId: o.orderId, ref: o.ref, type: 'goods', state: o.state, placedAt: new Date(o.placedAt).toISOString(),
      subtotalCents: subtotal, deliveryFeeCents: o.feeCents, taxCents: o.taxCents, totalCents: subtotal + o.feeCents + o.taxCents,
      delivery: o.kind === 'direct'
        ? { kind: 'direct', runLabel: null, day: null, startsAt: null, endsAt: null, households: 0, etaAt: new Date(o.placedAt + 45 * 60_000).toISOString() }
        : { kind: 'pooled', runLabel: null, day: 'today', startsAt: r.startsAt, endsAt: r.endsAt, households: 5, etaAt: null },
      shops, steps,
      deliveredAt: o.deliveredAt ? new Date(o.deliveredAt).toISOString() : null,
      deliveryProof: o.deliveredAt ? (o.proof ?? 'photo') : null,
      confirmedAt: o.confirmedAt ? new Date(o.confirmedAt).toISOString() : null,
      canConfirm: !!o.deliveredAt && !o.confirmedAt,
      paysShopsAt: o.deliveredAt ? new Date(o.deliveredAt + 7 * DAY).toISOString() : null,
      courier: o.courier ? { state: 'en_route', runLabel: null, courierName: o.courier.courierName, eta: new Date(midnight() + 18 * HOUR + 52 * 60_000).toISOString(), stopsBefore: o.courier.stopsBefore, lat: null, lng: null, positionAt: null, pin: o.courier.pin } : null,
    };
  }

  function newOrder(lines: Order['lines'], kind: 'pooled' | 'direct', fee: number, tax: number): Order {
    const n = state.next++;
    return { orderId: `ord-${n}`, ref: `NL-${n}`, state: 'placed', placedAt: ctx.now(), lines, kind, feeCents: fee, taxCents: tax, reported: [] };
  }

  return (req) => {
    const { path, method } = req;
    const q = req.url.searchParams;

    if (method === 'GET' && path === '/public/home') {
      const city = q.get('city') ?? '';
      if (!city) return ctx.answer(422, { errors: [{ field: 'city', rule: 'required', message: 'Choose a city.' }] });
      const ok = served(city);
      return ctx.answer(200, {
        city, providers: ok ? 2 : 0, shops: ok ? SHOPS.length : 0, kitchensOpen: 0, categories: {}, cuisines: {},
        trusted: ok
          ? [
              { merchantId: 'm-cleaners', name: 'Tidy Nook Cleaners', slug: 'tidy-nook-cleaners', tier: 'master', brandColor: null, category: { id: 'service.cleaning-and-property.house-cleaning', name: 'Home cleaning' }, rating: 4.9, reviews: 120 },
              { merchantId: 'm-bar', name: 'Copper & Soda', slug: 'copper-and-soda', tier: 'trusted', brandColor: null, category: { id: 'service.events-and-hospitality.cocktail-and-mocktail-bar', name: 'Cocktail & mocktail bar' }, rating: 4.8, reviews: 44 },
            ]
          : [],
      });
    }
    if (method === 'GET' && path === '/public/shop') {
      const market = q.get('market') ?? MARKETS[0].city;
      const ok = served(market);
      return ctx.answer(200, {
        market, served: ok, run: ok ? run('today') : null, shopCount: ok ? SHOPS.length : 0,
        departments: ok ? departments : [], shops: ok ? SHOPS.map(shopCard) : [], popular: ok ? PRODUCTS.slice(0, 4).map(card) : [],
      });
    }
    let m = /^\/public\/shop\/departments\/([^/]+)$/.exec(path);
    if (method === 'GET' && m) {
      const shop = SHOPS.find((s) => s.departmentSlug === m![1]);
      if (!shop) return ctx.answer(404, { code: 'not_found', detail: 'No such department.' });
      const market = q.get('market') ?? MARKETS[0].city;
      const products = PRODUCTS.filter((p) => p.merchantId === shop.merchantId);
      return ctx.answer(200, {
        slug: shop.departmentSlug, name: shop.departmentName, groupName: 'Food & grocery', market, served: served(market), run: run('today'), siblings: departments,
        shopCount: 1, onRunCount: products.filter((p) => p.tonight).length, productCount: products.length, shops: [shopCard(shop)], products: products.map(card),
      });
    }
    m = /^\/public\/shop\/products\/([^/]+)$/.exec(path);
    if (method === 'GET' && m) {
      const p = PRODUCTS.find((x) => x.productId === m![1] || x.offerId === m![1]);
      if (!p) return ctx.answer(404, { code: 'not_found', detail: 'No such product.' });
      const market = q.get('market') ?? MARKETS[0].city;
      const ok = served(market);
      const s = shopOf(p.merchantId);
      return ctx.answer(200, {
        productId: p.productId, name: p.name, brand: null, description: p.description ?? null, bullets: [], unit: p.unit,
        departmentSlug: s.departmentSlug, departmentName: s.departmentName, market, served: ok,
        offers: ok
          ? [{
              offerId: p.offerId, merchantId: p.merchantId, shopName: s.name, tier: s.tier, rating: 4.8, ratingCount: 211, priceCents: p.priceCents, compareAtCents: null,
              condition: null, stock: p.stock, lowStock: p.stock <= 3, returnsPolicy: null, variantTheme: 'option',
              variants: (p.variants ?? []).map((v) => ({ ...v, priceCents: p.priceCents, stock: p.stock, images: [] })),
              runs: p.tonight ? [run('today')] : [run('tomorrow')], images: [],
              more: PRODUCTS.filter((x) => x.merchantId === p.merchantId && x.productId !== p.productId).map((x) => ({ productId: x.productId, name: x.name, priceCents: x.priceCents })),
            }]
          : [],
        direct: ok ? { etaMinutes: 45, feeCents: 999 } : null,
      });
    }
    if (method === 'GET' && path === '/search') {
      const text = (q.get('q') ?? '').toLowerCase();
      const max = q.get('maxPrice') ? Number(q.get('maxPrice')) : Infinity;
      const size = Number(q.get('size') ?? 24);
      const from = Number(q.get('after') ?? 0);
      let hits = PRODUCTS.filter(
        (p) =>
          (!text || p.name.toLowerCase().includes(text)) &&
          (q.get('delivery') !== 'tonight' || p.tonight) &&
          p.priceCents <= max &&
          (q.get('tier') !== 'master' || shopOf(p.merchantId).tier === 'master') &&
          (!q.get('dietary') || q.get('dietary')!.split(',').every((d) => p.dietary.includes(d))),
      );
      if (q.get('sort') === 'price_asc') hits = [...hits].sort((a, b) => a.priceCents - b.priceCents);
      if (q.get('sort') === 'price_desc') hits = [...hits].sort((a, b) => b.priceCents - a.priceCents);
      const page = hits.slice(from, from + size);
      return ctx.answer(200, {
        items: page.map((p) => {
          const s = shopOf(p.merchantId);
          return {
            id: p.offerId, kind: 'product', name: p.name, description: null, merchant: { id: s.merchantId, name: s.name, type: 'seller', slug: null, tier: s.tier },
            category: { id: `shop.food-and-grocery.${s.departmentSlug}`, name: s.departmentName }, priceCents: p.priceCents, rating: 4.8, trustTier: s.tier,
            soldOut: false, onTonightsRun: p.tonight, imageKey: null,
          };
        }),
        total: hits.length,
        next: from + size < hits.length ? String(from + size) : null,
      });
    }

    // ── the cart ─────────────────────────────────────────────────────────────────────────────────────────────────
    if (path === '/cart' && method === 'GET') return ctx.answer(200, cartView(ownerOf(req)));
    if (path === '/cart/items' && method === 'POST') {
      const owner = ownerOf(req);
      if (!owner) return ctx.answer(422, { errors: [{ field: 'guest', rule: 'required', message: 'Your browsing session expired. Reload the page.' }] });
      const p = productOf(String(req.body.offerId ?? ''));
      if (!p) return ctx.answer(422, { errors: [{ field: 'offerId', rule: 'unavailable', message: "This item isn't available any more." }] });
      const qty = Number(req.body.qty);
      if (!(qty >= 1 && qty <= 99)) return ctx.answer(422, { errors: [{ field: 'qty', rule: 'range', message: 'Choose a quantity from 1 to 99.' }] });
      const lines = state.carts.get(owner) ?? [];
      const variantId = req.body.variantId ? String(req.body.variantId) : undefined;
      const same = lines.find((l) => l.offerId === p.offerId && l.variantId === variantId);
      if (same) same.qty = Math.min(99, same.qty + qty);
      else lines.push({ itemId: `ci-${p.offerId}-${variantId ?? 'x'}`, offerId: p.offerId, variantId, qty });
      state.carts.set(owner, lines);
      return ctx.answer(201, cartView(owner));
    }
    m = /^\/cart\/items\/([^/]+)$/.exec(path);
    if (m && (method === 'PATCH' || method === 'DELETE')) {
      const owner = ownerOf(req);
      const lines = owner ? (state.carts.get(owner) ?? []) : [];
      const line = lines.find((l) => l.itemId === decodeURIComponent(m![1]!));
      if (!line) return ctx.answer(404, { code: 'not_found' });
      const qty = method === 'DELETE' ? 0 : Number(req.body.qty);
      if (qty === 0) lines.splice(lines.indexOf(line), 1);
      else line.qty = qty;
      return ctx.answer(200, cartView(owner));
    }

    // ── signed-in only from here ─────────────────────────────────────────────────────────────────────────────────
    if (!path.startsWith('/me/')) return undefined;
    const known = ['/me/upcoming', '/me/checkout', '/me/checkout/quote', '/me/checkouts', '/me/payment-methods'].includes(path) || /^\/me\/(checkouts|orders|problems|case-uploads|devices)\b/.test(path);
    if (!known) return undefined;
    if (!signedIn(req)) return unauthorized();
    ownerOf(req);

    if (method === 'GET' && path === '/me/upcoming') {
      const items = state.upcoming
        ? [...state.orders.values()].filter((o) => o.state !== 'confirmed').map((o) => ({ id: o.orderId, title: `Order ${o.ref}`, subtitle: null, state: 'On the way', tone: 'accent', href: `/orders/${o.orderId}` }))
        : [];
      return ctx.answer(200, { items });
    }
    if (method === 'GET' && path === '/me/payment-methods') {
      return ctx.answer(200, { provider: state.provider, publishableKey: state.provider === 'stripe' ? 'pk_test_fixture' : null, items: state.cards });
    }
    if (method === 'GET' && path === '/me/checkout') {
      const market = q.get('market') ?? MARKETS[0].city;
      const ok = served(market);
      return ctx.answer(200, {
        cart: cartView('user'), addresses: state.addresses, options: ok ? options() : [],
        payment: { provider: state.provider, publishableKey: state.provider === 'stripe' ? 'pk_test_fixture' : null }, stepUp: state.stepUp, market, served: ok,
      });
    }
    if (method === 'POST' && path === '/me/checkout/quote') {
      const p = plan(req.body);
      if (p.errors.length) return ctx.answer(422, { errors: p.errors });
      if (p.cart.itemCount === 0) return ctx.answer(409, { code: 'cart_empty', detail: 'Your cart is empty.' });
      return ctx.answer(200, {
        subtotalCents: p.cart.subtotalCents, deliveryFeeCents: p.fee, taxCents: p.tax, taxes: [{ type: p.type, percent: p.percent, cents: p.tax }],
        totalCents: p.cart.subtotalCents + p.fee + p.tax, market: MARKETS[0].city,
      });
    }
    if (method === 'POST' && path === '/me/checkouts') {
      const key = req.headers['idempotency-key'];
      if (key && state.idempotent.has(`start:${key}`)) return ctx.answer(201, state.idempotent.get(`start:${key}`), { 'Idempotent-Replayed': 'true' });
      if (state.stepUp === 'required' && req.headers['x-step-up'] !== FIXTURE_PROOF) {
        return ctx.answer(403, { code: 'step_up_required', detail: "Confirm it's you with your passkey or authenticator app to pay." });
      }
      if (state.stepUp === 'enrol') return ctx.answer(403, { code: 'second_factor_required', detail: 'Add a passkey to pay: payments sit behind a second factor.' });
      const p = plan(req.body);
      if (p.errors.length) return ctx.answer(422, { errors: p.errors });
      if (p.cart.itemCount === 0) return ctx.answer(409, { code: 'cart_empty', detail: 'Your cart is empty.' });
      const items = p.cart.groups.flatMap((g) => g.items);
      if (items.some((i) => i.qty > i.stock)) return ctx.answer(409, { code: 'out_of_stock', detail: 'Something in your cart just sold out. Check your cart and try again.' });
      const order = newOrder(
        items.map((i) => ({ itemId: i.itemId, offerId: i.offerId, qty: i.qty, name: i.name, merchantId: i.merchantId, unitCents: i.unitCents })),
        p.option!.kind as 'pooled' | 'direct',
        p.fee,
        p.tax,
      );
      const checkoutId = `co-${order.orderId}`;
      state.checkouts.set(checkoutId, { checkoutId, order, placed: false });
      const stripe = state.provider === 'stripe';
      const intent = (n: number, amount: number) => ({
        paymentIntent: `pi_fixture_${order.orderId}_${n}`,
        clientSecret: `pi_fixture_${order.orderId}_${n}_secret_x`,
        status: stripe ? 'requires_payment_method' : 'authorized',
        amountCents: amount,
      });
      const started = {
        checkoutId, orderId: order.orderId, ref: order.ref, totalCents: p.cart.subtotalCents + p.fee + p.tax, expiresAt: new Date(ctx.now() + 15 * 60_000).toISOString(),
        payment: { provider: state.provider, publishableKey: stripe ? 'pk_test_fixture' : null },
        intents: [...items.map((i, n) => intent(n, i.lineCents)), ...(p.fee > 0 ? [intent(items.length, p.fee)] : [])],
      };
      if (key) state.idempotent.set(`start:${key}`, started);
      return ctx.answer(201, started);
    }
    m = /^\/me\/checkouts\/([^/]+)\/place$/.exec(path);
    if (method === 'POST' && m) {
      const c = state.checkouts.get(decodeURIComponent(m[1]!));
      if (!c) return ctx.answer(404, { code: 'not_found' });
      if (!c.placed) {
        c.placed = true;
        state.orders.set(c.order.orderId, c.order);
        state.carts.set('user', []);
      }
      return ctx.answer(201, { orderId: c.order.orderId, ref: c.order.ref });
    }
    m = /^\/me\/orders\/([^/]+)(\/confirm)?$/.exec(path);
    if (m) {
      const o = state.orders.get(decodeURIComponent(m[1]!));
      if (!o) return ctx.answer(404, { code: 'not_found', detail: 'We couldn’t find this order.' });
      if (method === 'POST' && m[2]) {
        if (!o.deliveredAt) return ctx.answer(409, { code: 'not_delivered', detail: 'It isn’t delivered yet.' });
        o.confirmedAt ??= ctx.now();
        o.state = 'confirmed';
      }
      return ctx.answer(200, tracking(o));
    }
    m = /^\/me\/problems\/([^/]+)\/([^/]+)$/.exec(path);
    if (method === 'GET' && m) {
      const o = state.orders.get(decodeURIComponent(m[2]!));
      if (!o || m[1] !== 'order') return ctx.answer(404, { code: 'not_found' });
      const status = (ref: string) => (o.reported.includes(ref) ? 'reported' : o.deliveredAt ? 'open' : 'not_yet');
      const items = o.lines.map((l) => ({
        ref: l.itemId, title: l.name, qty: l.qty, amountCents: l.unitCents * l.qty, taxCents: Math.round(l.unitCents * l.qty * 0.05),
        merchantId: l.merchantId, merchantName: shopOf(l.merchantId).name, status: status(l.itemId), reportBy: null,
      }));
      const overall = items.some((i) => i.status === 'open') ? 'open' : items.every((i) => i.status === 'reported') ? 'reported' : 'not_yet';
      return ctx.answer(200, {
        kind: 'order', id: o.orderId, ref: o.ref, title: `Order ${o.ref}`, date: new Date(o.placedAt).toISOString(), items,
        reasons: ['missing', 'damaged', 'wrong_item', 'poor_quality', 'late'], status: overall, reportBy: null, card: { brand: 'Visa', last4: '4471' },
      });
    }
    // mobile gaps part 1: the door photo's signed link (404: none to show — PIN, ID check), report photos, push devices
    m = /^\/me\/orders\/([^/]+)\/proof-photo$/.exec(path);
    if (method === 'GET' && m) {
      const o = state.orders.get(decodeURIComponent(m[1]!));
      if (!o) return ctx.answer(404, { code: 'not_found' });
      if (!o.deliveredAt || (o.proof ?? 'photo') !== 'photo' || o.idCheck) return ctx.answer(404, { code: 'not_found', detail: 'No proof photo.' });
      return ctx.answer(200, { url: FIXTURE_PROOF_PHOTO, expiresAt: new Date(ctx.now() + 5 * 60_000).toISOString() });
    }
    if (method === 'POST' && path === '/me/case-uploads') {
      const id = `up-${state.uploads.length + 1}`;
      state.uploads.push(id);
      return ctx.answer(201, { id, fileName: `${id}.jpg`, contentType: 'image/jpeg', size: 1000 });
    }
    m = /^\/me\/devices\/([^/]+)$/.exec(path);
    if (m && method === 'PUT') {
      state.devices.set(m[1]!, req.body);
      return ctx.answer(200, { installationId: m[1], ...req.body });
    }
    if (m && method === 'DELETE') {
      state.devices.delete(m[1]!);
      return ctx.answer(204, undefined);
    }
    if (method === 'POST' && path === '/me/problems') {
      state.reports.push(req.body);
      const o = state.orders.get(String(req.body.id ?? ''));
      const refs = (req.body.items as string[] | undefined) ?? [];
      if (!o) return ctx.answer(404, { code: 'not_found' });
      if (refs.length === 0) return ctx.answer(422, { errors: [{ field: 'items', rule: 'required', message: 'Pick at least one item.' }] });
      o.reported.push(...refs);
      const lines = o.lines.filter((l) => refs.includes(l.itemId));
      const total = lines.reduce((n, l) => n + Math.round(l.unitCents * l.qty * 1.05), 0);
      return ctx.answer(201, {
        caseId: 'case-2201', caseCode: 'RF-2201', submittedAt: new Date(ctx.now()).toISOString(), totalCents: total, card: { brand: 'Visa', last4: '4471' },
        refunds: [{ id: 'rf-1', number: 'RF-2201', amountCents: total, taxCents: 0, merchantName: shopOf(lines[0]!.merchantId).name, respondBy: new Date(ctx.now() + DAY).toISOString() }],
      });
    }
    return undefined;
  };
}

/** Tests and demos: an order in a given state (`picked_up` with a courier, `delivered`…). */
export function seedOrder(
  state: ShopFixtureState,
  now: number,
  orderState: 'placed' | 'packing' | 'picked_up' | 'delivered',
  id = 'ord-1001',
  extra: Pick<Order, 'proof' | 'idCheck'> = {},
): string {
  const p = PRODUCTS[0]!;
  const k = PRODUCTS[5]!;
  state.orders.set(id, {
    orderId: id,
    ref: 'NL-1001',
    state: orderState,
    placedAt: now - 2 * HOUR,
    lines: [
      { itemId: 'li-1', offerId: p.offerId, qty: 2, name: p.name, merchantId: p.merchantId, unitCents: p.priceCents },
      { itemId: 'li-2', offerId: k.offerId, qty: 1, name: k.name, merchantId: k.merchantId, unitCents: k.priceCents },
    ],
    kind: 'pooled',
    feeCents: 299,
    taxCents: 101,
    deliveredAt: orderState === 'delivered' ? now - 10 * 60_000 : undefined,
    courier: orderState === 'picked_up' ? { courierName: 'Robin', stopsBefore: 0, pin: '4827' } : null,
    reported: [],
    ...extra,
  });
  return id;
}
