/**
 * Northline's deep links (S-102, docs/runbooks/push.md § Deep links). A push, an email or a web page opens one of
 *
 *   https://<consumer host>/orders/<id>           ca.northline.app://orders/<id>          → the order (tracking)
 *   https://<consumer host>/food/orders/<id>      ca.northline.app://food/orders/<id>     → the food order
 *   https://<consumer host>/bookings/<id>         ca.northline.app://bookings/<id>        → the booking (ETA, sign-off)
 *   https://<consumer host>/quotes/<id>           ca.northline.app://quotes/<id>          → the quote received
 *   https://<consumer host>/cases/<number>        ca.northline.app://cases/<number>       → the refund case
 *   https://<consumer host>/courier/run           ca.northline.courier://run              → the courier's run
 *
 * The https links are universal links / App Links (the consumer host serves the association files); the custom schemes
 * work without them. Links carry ids only.
 */
export type DeepLink =
  | { screen: 'order'; orderId: string; food: boolean }
  | { screen: 'booking'; bookingId: string }
  | { screen: 'quote'; quoteId: string }
  | { screen: 'case'; caseNumber: string }
  | { screen: 'courierRun' };

export const CONSUMER_SCHEME = 'ca.northline.app';
export const COURIER_SCHEME = 'ca.northline.courier';

const ID = /^[A-Za-z0-9_-]{1,64}$/;

/**
 * The screen a link opens, or null for anything else (another host, an unknown path, a malformed id) — a link from a
 * notification is never trusted further than this.
 *
 * @param hosts the consumer hosts this build accepts (`northline.ca`, `staging.northline.ca` …)
 */
export function parseDeepLink(link: string, hosts: readonly string[]): DeepLink | null {
  let url: URL;
  try {
    url = new URL(link);
  } catch {
    return null;
  }
  let segments: string[];
  const scheme = url.protocol.replace(/:$/, '');
  if (scheme === 'https' || scheme === 'http') {
    if (!hosts.includes(url.host)) return null;
    segments = url.pathname.split('/').filter(Boolean);
  } else if (scheme === CONSUMER_SCHEME || scheme === COURIER_SCHEME) {
    // ca.northline.app://orders/1 → host "orders", path "/1"; ca.northline.app:/orders/1 → path "/orders/1"
    segments = [url.host, ...url.pathname.split('/')].filter(Boolean);
    if (scheme === COURIER_SCHEME) return segments[0] === 'run' && segments.length === 1 ? { screen: 'courierRun' } : null;
  } else {
    return null;
  }
  const [first, second, third] = segments.map((s) => decodeURIComponent(s));
  const id = (value: string | undefined, length: number) => (segments.length === length && value && ID.test(value) ? value : null);
  switch (first) {
    case 'orders': {
      const orderId = id(second, 2);
      return orderId ? { screen: 'order', orderId, food: false } : null;
    }
    case 'food': {
      const orderId = second === 'orders' ? id(third, 3) : null;
      return orderId ? { screen: 'order', orderId, food: true } : null;
    }
    case 'bookings': {
      const bookingId = id(second, 2);
      return bookingId ? { screen: 'booking', bookingId } : null;
    }
    case 'quotes': {
      const quoteId = id(second, 2);
      return quoteId ? { screen: 'quote', quoteId } : null;
    }
    case 'cases': {
      const caseNumber = id(second, 2);
      return caseNumber ? { screen: 'case', caseNumber } : null;
    }
    case 'courier':
      return second === 'run' && segments.length === 2 ? { screen: 'courierRun' } : null;
    default:
      return null;
  }
}

/** The app route (expo-router path) of a link's screen. */
export function routeOf(link: DeepLink): string {
  switch (link.screen) {
    case 'order':
      return link.food ? `/food/orders/${encodeURIComponent(link.orderId)}` : `/orders/${encodeURIComponent(link.orderId)}`;
    case 'booking':
      return `/bookings/${encodeURIComponent(link.bookingId)}`;
    case 'quote':
      return `/quotes/${encodeURIComponent(link.quoteId)}`;
    case 'case':
      return `/cases/${encodeURIComponent(link.caseNumber)}`;
    case 'courierRun':
      return '/run';
  }
}
