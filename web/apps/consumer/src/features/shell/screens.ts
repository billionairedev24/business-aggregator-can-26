/**
 * Every consumer screen of design 06 (docs/SCREENS.md § Consumer web), its route and the story that builds it
 * (docs/CONSUMER_WEB_PLAN.md § Routes). The shell reads it to highlight Services / Shop / Food, to show the guest
 * banner, and to hide the header's account buttons on the sign-in pages. A screen's workstream replaces its route's
 * <ScreenPending> — it does not edit this table unless the route itself moves.
 */
export type ScreenKey =
  | 'home' | 'location' | 'search'
  | 'shop' | 'category' | 'product' | 'cart' | 'confirmed'
  | 'food' | 'restaurant' | 'foodCheckout' | 'foodTrack'
  | 'services' | 'svcCategory' | 'providers' | 'provider' | 'book' | 'quote' | 'quoteRequest' | 'quoteCompare'
  | 'orders' | 'account' | 'problem' | 'signIn' | 'register' | 'sell';

export interface Screen {
  /** Route path (TanStack Router syntax). */
  path: string;
  /** Backlog story that builds it. */
  story: string;
  /** Which of Services / Shop / Food it belongs to (header link marked current). */
  section?: 'services' | 'shop' | 'food';
  /** Design 06 `signedOutBanner`: guests see "You're browsing as a guest…" above it. */
  guestBanner?: true;
  /** The sign-in pages: no Sign in / Create account buttons in the header (design 06 `signedOut` hides them on `auth`). */
  auth?: true;
}

export const SCREENS: Record<ScreenKey, Screen> = {
  home: { path: '/', story: 'S-46' },
  location: { path: '/location', story: 'S-47' },
  search: { path: '/search', story: 'S-48' },
  shop: { path: '/shop', story: 'S-49', section: 'shop' },
  category: { path: '/shop/$department', story: 'S-49', section: 'shop' },
  product: { path: '/products/$productId', story: 'S-50', section: 'shop' },
  cart: { path: '/cart', story: 'S-51', section: 'shop', guestBanner: true },
  confirmed: { path: '/orders/$orderId', story: 'S-52' },
  food: { path: '/food', story: 'S-57', section: 'food' },
  restaurant: { path: '/food/$kitchen', story: 'S-57', section: 'food' },
  foodCheckout: { path: '/food/checkout', story: 'S-57', section: 'food', guestBanner: true },
  foodTrack: { path: '/food/orders/$orderId', story: 'S-57', section: 'food' },
  services: { path: '/services', story: 'S-53', section: 'services' },
  svcCategory: { path: '/services/$category', story: 'S-53', section: 'services' },
  providers: { path: '/services/$category/providers', story: 'S-53', section: 'services' },
  provider: { path: '/providers/$slug', story: 'S-54', section: 'services' },
  book: { path: '/providers/$slug/book', story: 'S-55', section: 'services', guestBanner: true },
  quote: { path: '/quotes/$quoteId', story: 'S-56', section: 'services', guestBanner: true },
  /** design 06 `book` in quote mode started from a category ("Describe the job, get 3 quotes"), no provider yet. */
  quoteRequest: { path: '/services/$category/quote', story: 'S-56', section: 'services', guestBanner: true },
  /** design 06 `book` quote mode, "N quotes received": the request's providers and their quotes side by side. */
  quoteCompare: { path: '/quotes/requests/$requestId', story: 'S-56', section: 'services', guestBanner: true },
  orders: { path: '/account/orders', story: 'S-58', guestBanner: true },
  account: { path: '/account', story: 'S-58 · S-59', guestBanner: true },
  /** "Something's wrong" (consumer app `refund`, design 06's case flow): report a problem with an order or a job. */
  problem: { path: '/account/problem/$kind/$id', story: 'S-60', guestBanner: true },
  signIn: { path: '/sign-in', story: 'S-62', auth: true },
  register: { path: '/register', story: 'S-62', auth: true },
  sell: { path: '/sell', story: 'S-61' },
};

/** The screen a pathname shows (the most specific route match), for the shell. */
export function screenFor(pathname: string): ScreenKey | undefined {
  const clean = pathname.replace(/\/+$/, '') || '/';
  let best: { key: ScreenKey; score: number } | undefined;
  for (const [key, screen] of Object.entries(SCREENS) as [ScreenKey, Screen][]) {
    const parts = screen.path.split('/').filter(Boolean);
    const segs = clean.split('/').filter(Boolean);
    if (parts.length !== segs.length) continue;
    let score = 0, ok = true;
    parts.forEach((p, i) => { if (p.startsWith('$')) return; if (p === segs[i]) score += 1; else ok = false; });
    if (ok && (!best || score > best.score)) best = { key, score };
  }
  return best?.key;
}
