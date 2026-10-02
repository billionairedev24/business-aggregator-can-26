import type { ApiClient, Locale } from '@northline/mobile-kit';

/**
 * Journey B's api (S-99): the consumer web's endpoints, called directly with the app's DPoP tokens
 * (docs/MOBILE_PLAN.md § B). Public reads (`/public/**`, `/search`, `/cart` for guests) go out with `auth: 'optional'`;
 * the guest id (`X-Northline-Guest`) is on every call, so a guest's cart is theirs and merges at sign-in (S-51).
 * Shapes follow the api's records (the consumer web's zod schemas in web/apps/consumer/src/features/*\/api.ts).
 */

// ── public shop (S-46, S-49, S-50) ───────────────────────────────────────────────────────────────────────────────
export type Day = 'today' | 'tomorrow' | 'later';
export interface Run { windowId: string; label?: string | null; day: Day; startsAt: string; endsAt: string; orderBy: string; feeCents: number; households: number }
export interface DepartmentTile { slug: string; name: string; shops: number }
export interface ShopCard { merchantId: string; name: string; tier: string; departmentSlug: string; departmentName: string; products: number; run?: Run | null }
export interface ProductCard {
  productId: string; offerId: string; name: string; merchantId: string; shopName: string; unit?: string | null;
  priceCents: number; imageUrl?: string | null; sellers: number; run?: Run | null;
}
export interface Landing { market: string; served: boolean; run?: Run | null; shopCount: number; departments: DepartmentTile[]; shops: ShopCard[]; popular: ProductCard[] }
export interface Department {
  slug: string; name: string; groupName: string; market: string; served: boolean; run?: Run | null; siblings: DepartmentTile[];
  shopCount: number; onRunCount: number; productCount: number; shops: ShopCard[]; products: ProductCard[];
}
export interface TrustedProvider { merchantId: string; name: string; slug?: string | null; tier: string; category?: { id: string; name: string } | null; rating: number; reviews: number }
export interface HomeSummary { city: string; providers: number; shops: number; kitchensOpen: number; categories: Record<string, number>; trusted: TrustedProvider[] }
export interface Variant { variantId: string; value: string; priceCents: number; stock: number; images?: string[] }
export interface Offer {
  offerId: string; merchantId: string; shopName: string; tier: string; rating: number; ratingCount: number; priceCents: number;
  compareAtCents?: number | null; condition?: string | null; stock: number; lowStock: boolean; returnsPolicy?: string | null;
  variantTheme: string; variants: Variant[]; runs: Array<Run & { packBy: string }>; images: string[];
  more: Array<{ productId: string; name: string; priceCents: number }>;
}
export interface ProductPage {
  productId: string; name: string; brand?: string | null; description?: string | null; bullets: string[]; unit?: string | null;
  departmentSlug: string; departmentName: string; market: string; served: boolean; offers: Offer[];
  direct?: { etaMinutes: number; feeCents: number } | null;
}
/** "Your week" (S-58): the person's next seven days; `href` is a consumer-web path (mapped to app routes). */
export interface Upcoming { id: string; title: string; subtitle?: string | null; state: string; tone: 'accent' | 'neutral' | 'accent-2'; href: string }
/** `GET /me/account-summary` (S-58/S-59; owned by S-101): Home reads `plus` only. */
export interface AccountSummary { plus?: boolean | null; points?: { balance: number; valueCents: number } | null }
export interface Me { firstName: string; lastName: string }

// ── search (S-44) ────────────────────────────────────────────────────────────────────────────────────────────────
export type Sort = 'relevance' | 'price_asc' | 'price_desc' | 'rating';
export interface SearchItem {
  id: string; kind: 'service' | 'product' | 'food' | 'merchant'; name: string; description?: string | null;
  merchant: { id: string; name: string; type: string; slug?: string | null; tier?: string | null };
  category?: { id: string; name?: string | null } | null; priceCents?: number | null; rating?: number | null;
  trustTier?: string | null; soldOut?: boolean | null; onTonightsRun?: boolean | null; imageKey?: string | null;
}
export interface SearchPage { items: SearchItem[]; total: number; next?: string | null }
export interface SearchQuery {
  q: string; market?: string; lat?: number; lng?: number; sort: Sort;
  tonight?: boolean; maxPrice?: number; tier?: 'master'; dietary?: string[];
}

// ── cart and checkout (S-51) ─────────────────────────────────────────────────────────────────────────────────────
export interface CartLine {
  itemId: string; merchantId: string; offerId: string; variantId?: string | null; productId: string; name: string;
  option?: string | null; unit?: string | null; imageUrl?: string | null; unitCents: number; qty: number; lineCents: number;
  stock: number; available: boolean; handlingDays?: number | null;
}
export interface Cart { itemCount: number; shopCount: number; subtotalCents: number; groups: Array<{ merchantId: string; shopName: string; items: CartLine[] }> }
export const EMPTY_CART: Cart = { itemCount: 0, shopCount: 0, subtotalCents: 0, groups: [] };

export interface SavedAddress { id: string; street: string; unit?: string | null; city: string; province: string; postal: string; note?: string | null; isDefault: boolean }
export interface DeliveryOption {
  id: string; kind: 'pooled' | 'direct'; windowId?: string | null; day?: Day | null; startsAt?: string | null; endsAt?: string | null;
  orderBy?: string | null; packBy?: string | null; feeCents: number; households: number; etaMinutes?: number | null;
}
export type Provider = 'stripe' | 'fake';
export interface PaymentConfig { provider: Provider; publishableKey?: string | null }
export interface CheckoutSetup {
  cart: Cart; addresses: SavedAddress[]; options: DeliveryOption[]; payment: PaymentConfig;
  stepUp: 'none' | 'required' | 'enrol'; market: string; served: boolean;
}
export interface TaxLine { type: string; percent: number; cents: number }
export interface Quote { subtotalCents: number; deliveryFeeCents: number; taxCents: number; taxes: TaxLine[]; totalCents: number; market: string }
export interface AddressInput { addressId?: string; street?: string; unit?: string; city?: string; province?: string; postal?: string; note?: string }
export type Substitution = 'similar' | 'refund' | 'ask';
export interface CheckoutBody { kind: 'pooled' | 'direct'; windowId: string | null; address: AddressInput; substitution: Substitution }
export interface Intent { paymentIntent: string; clientSecret?: string | null; status: string; amountCents: number }
export interface Started { checkoutId: string; orderId: string; ref: string; totalCents: number; expiresAt: string; payment: PaymentConfig; intents: Intent[] }
export interface Placed { orderId: string; ref: string }
export interface SavedCard { id: string; brand: string; last4: string; expMonth: number; expYear: number; isDefault: boolean }
export interface Cards { provider: Provider; publishableKey?: string | null; items: SavedCard[] }

// ── the order (S-52, S-78, S-88) and problems (S-60) ─────────────────────────────────────────────────────────────
export type StepKey = 'paid' | 'packing' | 'pickup' | 'delivered';
export type StepState = 'done' | 'current' | 'todo';
export interface CourierProgress { state: string; runLabel?: string | null; courierName?: string | null; eta?: string | null; stopsBefore: number; pin?: string | null }
export interface OrderTracking {
  orderId: string; ref?: string | null; type: string; state: string; placedAt: string;
  subtotalCents: number; deliveryFeeCents: number; taxCents: number; totalCents: number;
  delivery: { kind: 'pooled' | 'direct' | 'pickup'; runLabel?: string | null; day?: Day | null; startsAt?: string | null; endsAt?: string | null; households: number; etaAt?: string | null };
  shops: Array<{ merchantId: string; name: string; items: number; packed: boolean }>;
  steps: Array<{ key: StepKey; state: StepState }>;
  deliveredAt?: string | null; deliveryProof?: 'photo' | 'signature' | 'pin' | null; confirmedAt?: string | null;
  canConfirm?: boolean; paysShopsAt?: string | null; courier?: CourierProgress | null;
}
export type ProblemStatus = 'open' | 'reported' | 'closed' | 'not_yet' | 'not_paid';
export interface ProblemItem { ref: string; title: string; qty: number; amountCents: number; taxCents: number; merchantId: string; merchantName: string; status: ProblemStatus; reportBy?: string | null }
export interface ProblemContext {
  kind: 'order' | 'food' | 'booking'; id: string; ref?: string | null; title: string; date: string; items: ProblemItem[];
  reasons: string[]; status: ProblemStatus; reportBy?: string | null; card?: { brand: string; last4: string } | null;
}
export interface Reported {
  caseId: string; caseCode: string; submittedAt: string; totalCents: number; card?: { brand: string; last4: string } | null;
  refunds: Array<{ id: string; number: string; amountCents: number; taxCents: number; merchantName: string; respondBy?: string | null }>;
}

const qs = (p: Record<string, string | number | boolean | undefined | null>) =>
  Object.entries(p)
    .filter(([, v]) => v !== undefined && v !== null && v !== '' && v !== false)
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&');
const lang = (l: Locale) => (l === 'fr-CA' ? 'fr' : 'en');
const id = encodeURIComponent;

export function searchPath(s: SearchQuery, after?: string): string {
  return `/search?${qs({
    q: s.q.trim(),
    market: s.market,
    kind: 'product',
    delivery: s.tonight ? 'tonight' : undefined,
    maxPrice: s.maxPrice,
    tier: s.tier,
    dietary: s.dietary?.length ? s.dietary.join(',') : undefined,
    lat: s.lat?.toFixed(5),
    lng: s.lng?.toFixed(5),
    sort: s.sort === 'relevance' ? undefined : s.sort,
    size: 24,
    after,
  })}`;
}

/** Calls of Journey B; each answers the api's JSON (a 204 or empty body is `null`, handled by the caller). */
export const shopApi = (api: ApiClient) => ({
  home: (city: string) => api.get<HomeSummary>(`/public/home?${qs({ city })}`, { auth: 'optional' }),
  landing: (market: string | undefined, l: Locale) => api.get<Landing>(`/public/shop?${qs({ market, lang: lang(l) })}`, { auth: 'optional' }),
  department: (slug: string, market: string | undefined, l: Locale) =>
    api.get<Department>(`/public/shop/departments/${id(slug)}?${qs({ market, lang: lang(l) })}`, { auth: 'optional' }),
  product: (productId: string, market: string | undefined, l: Locale) =>
    api.get<ProductPage>(`/public/shop/products/${id(productId)}?${qs({ market, lang: lang(l) })}`, { auth: 'optional' }),
  search: (s: SearchQuery, after?: string) => api.get<SearchPage>(searchPath(s, after), { auth: 'optional' }),
  me: () => api.get<Me>('/me'),
  upcoming: () => api.get<{ items: Upcoming[] }>('/me/upcoming'),
  accountSummary: () => api.get<AccountSummary>('/me/account-summary'),

  cart: (l: Locale) => api.get<Cart>(`/cart?${qs({ lang: lang(l) })}`, { auth: 'optional' }),
  addToCart: (item: { offerId: string; variantId?: string; qty: number }, l: Locale) =>
    api.post<Cart>(`/cart/items?${qs({ lang: lang(l) })}`, { auth: 'optional', json: item }),
  changeLine: (itemId: string, qty: number, l: Locale) =>
    qty === 0
      ? api.delete<Cart>(`/cart/items/${id(itemId)}?${qs({ lang: lang(l) })}`, { auth: 'optional' })
      : api.patch<Cart>(`/cart/items/${id(itemId)}?${qs({ lang: lang(l) })}`, { auth: 'optional', json: { qty } }),

  checkout: (market: string | undefined, l: Locale) => api.get<CheckoutSetup>(`/me/checkout?${qs({ market, lang: lang(l) })}`),
  quote: (body: CheckoutBody, l: Locale) => api.post<Quote>(`/me/checkout/quote?${qs({ lang: lang(l) })}`, { json: body }),
  /** Money-moving: one Idempotency-Key per Pay, kept for every retry; `X-Step-Up` when the api asked for one. */
  start: (body: CheckoutBody, key: string, l: Locale, proof?: string) =>
    api.post<Started>(`/me/checkouts?${qs({ lang: lang(l) })}`, { json: body, idempotencyKey: key, headers: proof ? { 'X-Step-Up': proof } : undefined }),
  place: (checkoutId: string, key: string) => api.post<Placed>(`/me/checkouts/${id(checkoutId)}/place`, { idempotencyKey: key }),
  cards: () => api.get<Cards>('/me/payment-methods'),

  order: (orderId: string) => api.get<OrderTracking>(`/me/orders/${id(orderId)}`),
  confirm: (orderId: string) => api.post<OrderTracking>(`/me/orders/${id(orderId)}/confirm`),
  problem: (kind: string, refId: string) => api.get<ProblemContext>(`/me/problems/${id(kind)}/${id(refId)}`),
  report: (r: { kind: string; id: string; items: string[]; reason: string; note?: string; attachmentIds: string[] }) =>
    api.post<Reported>('/me/problems', { json: r }),
});

export type ShopApi = ReturnType<typeof shopApi>;
