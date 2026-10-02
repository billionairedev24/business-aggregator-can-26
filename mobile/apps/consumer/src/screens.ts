/**
 * Every screen of design 01 (`Consumer Screen.dc.html`'s `screen` options) and where it lives in the app: the route
 * (an expo-router path; `[param]` segments), the story that builds it, whether it is a tab, whether it needs a
 * signed-in person, and the api it reads or writes (docs/MOBILE_PLAN.md § Screens explains each). A test checks that
 * each one has its route file and that MOBILE_PLAN lists it; stubs read their title and story from here.
 */
export type Journey = 'A' | 'B' | 'C' | 'D';
export type ScreenKey =
  | 'welcome' | 'signup' | 'otp' | 'mfa' | 'location'
  | 'home' | 'search' | 'product' | 'cart' | 'checkout' | 'pay' | 'confirmed' | 'track' | 'delivered' | 'refund'
  | 'services' | 'providers' | 'provider' | 'book_service' | 'book_slot' | 'book_review' | 'booked' | 'notifications' | 'eta' | 'signoff' | 'review'
  | 'orders' | 'quote' | 'account' | 'security' | 'wallet';

export interface ScreenDef {
  journey: Journey;
  story: 'S-98' | 'S-99' | 'S-100' | 'S-101';
  /** The route file under app/ (without extension). */
  file: string;
  /** A path that opens it (sample ids for `[param]`). */
  sample: string;
  /** One of the bottom tabs (no header, the tab bar shows). */
  tab?: boolean;
  /** Only for a signed-in person (guests see the sign-in prompt). */
  personal?: boolean;
  /** The api calls it makes (paths under `/api/v1` unless they start with `/api/auth` or `/oauth2`). */
  api: readonly string[];
}

export const SCREENS: Readonly<Record<ScreenKey, ScreenDef>> = {
  // A — account & security (S-98)
  welcome: { journey: 'A', story: 'S-98', file: 'welcome', sample: '/welcome', api: ['GET /geo/regions'] },
  signup: { journey: 'A', story: 'S-98', file: 'sign-up', sample: '/sign-up', api: ['GET /oauth2/authorize', 'POST /api/auth/register'] },
  otp: { journey: 'A', story: 'S-98', file: 'verify', sample: '/verify', api: ['POST /api/auth/register/verify', 'POST /api/auth/register/resend', 'POST /api/auth/sign-in/code', 'POST /api/auth/sign-in/code/verify'] },
  mfa: { journey: 'A', story: 'S-98', file: 'second-factor', sample: '/second-factor', api: ['POST /api/auth/register/totp', 'POST /api/auth/register/totp/verify', 'POST /api/auth/register/complete', 'POST /oauth2/token'] },
  location: { journey: 'A', story: 'S-98', file: 'location', sample: '/location', api: ['GET /geo/markets', 'GET /geo/reverse', 'GET /geo/autocomplete', 'GET /geo/places/{placeId}', 'POST /geo/waitlist'] },
  // B — shop & get it delivered (S-99)
  home: { journey: 'B', story: 'S-99', file: '(tabs)/home', sample: '/home', tab: true, api: ['GET /public/home?city=', 'GET /public/shop?market=', 'GET /me/upcoming', 'GET /me/account-summary'] },
  search: { journey: 'B', story: 'S-99', file: 'search', sample: '/search', api: ['GET /search', 'GET /search/suggest', 'POST /search/interpret'] },
  product: { journey: 'B', story: 'S-99', file: 'product/[id]', sample: '/product/01J9PRODUCT', api: ['GET /public/shop/products/{id}?market=', 'POST /cart/items'] },
  cart: { journey: 'B', story: 'S-99', file: '(tabs)/cart', sample: '/cart', tab: true, api: ['GET /cart', 'PATCH /cart/items/{id}', 'DELETE /cart/items/{id}'] },
  checkout: { journey: 'B', story: 'S-99', file: 'checkout', sample: '/checkout', personal: true, api: ['GET /me/checkout?market=', 'POST /me/checkout/quote'] },
  pay: { journey: 'B', story: 'S-99', file: 'pay', sample: '/pay', personal: true, api: ['POST /me/checkouts', 'POST /me/checkouts/{id}/place', 'GET /me/payment-methods'] },
  confirmed: { journey: 'B', story: 'S-99', file: 'orders/[id]/confirmed', sample: '/orders/01J9ORDER/confirmed', personal: true, api: ['GET /me/orders/{id}'] },
  track: { journey: 'B', story: 'S-99', file: 'orders/[id]/track', sample: '/orders/01J9ORDER/track', personal: true, api: ['GET /me/orders/{id}', 'GET /me/orders/{id}/events (SSE)'] },
  delivered: { journey: 'B', story: 'S-99', file: 'orders/[id]/delivered', sample: '/orders/01J9ORDER/delivered', personal: true, api: ['GET /me/orders/{id}'] },
  refund: { journey: 'B', story: 'S-99', file: 'problem/[kind]/[id]', sample: '/problem/order/01J9ORDER', personal: true, api: ['GET /me/problems/{kind}/{id}', 'POST /me/problems', 'POST /me/case-uploads', 'POST /me/help/triage'] },
  // C — find & book a service (S-100)
  services: { journey: 'C', story: 'S-100', file: '(tabs)/services', sample: '/services', tab: true, api: ['GET /public/services'] },
  providers: { journey: 'C', story: 'S-100', file: 'services/[category]', sample: '/services/mobile-mechanic', api: ['GET /public/services/{slug}', 'GET /public/services/{slug}/providers?lat&lng&city'] },
  provider: { journey: 'C', story: 'S-100', file: 'providers/[slug]', sample: '/providers/prairie-wrench', api: ['GET /public/providers/{slug}', 'GET /public/providers/{slug}/reviews', 'PUT /me/favourites/{businessId}'] },
  book_service: { journey: 'C', story: 'S-100', file: 'book/[slug]/service', sample: '/book/prairie-wrench/service', api: ['GET /public/providers/{slug}', 'POST /me/quote-requests'] },
  book_slot: { journey: 'C', story: 'S-100', file: 'book/[slug]/time', sample: '/book/prairie-wrench/time', api: ['GET /public/providers/{slug}/slots', 'POST /me/bookings/holds', 'DELETE /me/bookings/holds/{id}'] },
  book_review: { journey: 'C', story: 'S-100', file: 'book/[slug]/review', sample: '/book/prairie-wrench/review', personal: true, api: ['POST /me/bookings/checkout', 'POST /me/bookings/holds/{id}/confirm'] },
  booked: { journey: 'C', story: 'S-100', file: 'bookings/[id]/booked', sample: '/bookings/01J9BOOKING/booked', personal: true, api: ['GET /me/bookings/{id}'] },
  notifications: { journey: 'C', story: 'S-100', file: 'notifications', sample: '/notifications', personal: true, api: ['GET /me/activity', 'GET|PUT /me/notifications'] },
  eta: { journey: 'C', story: 'S-100', file: 'bookings/[id]/eta', sample: '/bookings/01J9BOOKING/eta', personal: true, api: ['GET /me/bookings/{id}'] },
  signoff: { journey: 'C', story: 'S-100', file: 'bookings/[id]/sign-off', sample: '/bookings/01J9BOOKING/sign-off', personal: true, api: ['GET /me/bookings/{id}', 'POST /me/problems'] },
  review: { journey: 'C', story: 'S-100', file: 'bookings/[id]/review', sample: '/bookings/01J9BOOKING/review', personal: true, api: ['GET /me/bookings/{id}', 'PUT /me/favourites/{businessId}'] },
  // D — account (S-101)
  orders: { journey: 'D', story: 'S-101', file: '(tabs)/orders', sample: '/orders', tab: true, personal: true, api: ['GET /me/activity?view=active|past|cases'] },
  quote: { journey: 'D', story: 'S-101', file: 'quotes/[id]', sample: '/quotes/01J9QUOTE', personal: true, api: ['GET /me/quotes/{id}', 'POST /me/quotes/{id}/accept', 'POST /me/quotes/{id}/accept/confirm', 'POST /me/quotes/{id}/decline'] },
  account: { journey: 'D', story: 'S-101', file: '(tabs)/account', sample: '/account', tab: true, api: ['GET /me/account-summary', 'GET /me/profile', 'GET /me/preferences'] },
  security: { journey: 'D', story: 'S-101', file: 'security', sample: '/security', personal: true, api: ['GET /me/profile', 'POST /oauth2/revoke'] },
  wallet: { journey: 'D', story: 'S-101', file: 'wallet', sample: '/wallet', personal: true, api: ['GET /me/wallet', 'GET|POST /me/plus', 'GET /me/payment-methods'] },
};

export const SCREEN_KEYS = Object.keys(SCREENS) as ScreenKey[];

/** The bottom tabs, in the design's order: Home · Services · Cart · Orders · You. */
export const TABS = ['home', 'services', 'cart', 'orders', 'account'] as const satisfies readonly ScreenKey[];
