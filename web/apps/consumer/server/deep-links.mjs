// The apps' deep links on the web (S-102, docs/runbooks/push.md § Deep links). Pushes and emails link to the consumer
// host's /app/… and /courier/… paths, which the phones hand to the Northline apps (the association files claim them).
// On a phone or computer without the app the link reaches this server instead: each path goes to the web page of the
// same thing, never to a 404. Only on the site's own host — not pages.<zone> or a merchant's domain.
//
//   /app/orders/<id>        → /orders/<id>            /app/food/orders/<id> → /food/orders/<id>
//   /app/quotes/<id>        → /quotes/<id>            /app/bookings/<id>    → /account/orders (bookings are listed there)
//   /app/cases/<number>     → /account/orders?view=cases                     /courier/run          → /
// (/app/oauth2redirect and /courier/oauth2redirect are the OAuth redirects, not deep links: app-links, S-97.)
const ID = '[A-Za-z0-9_-]{1,64}';
const ROUTES = [
  [new RegExp(`^/app/orders/(${ID})/?$`), id => `/orders/${id}`],
  [new RegExp(`^/app/food/orders/(${ID})/?$`), id => `/food/orders/${id}`],
  [new RegExp(`^/app/quotes/(${ID})/?$`), id => `/quotes/${id}`],
  [new RegExp(`^/app/bookings/(${ID})/?$`), () => '/account/orders'],
  [new RegExp(`^/app/cases/(${ID})/?$`), () => '/account/orders?view=cases'],
  [/^\/courier\/run\/?$/, () => '/'],
];

/** The web page of an app deep link, or null when the path isn't one. */
export function webPageOf(pathname) {
  for (const [pattern, target] of ROUTES) {
    const match = pattern.exec(pathname);
    if (match) return target(match[1]);
  }
  return null;
}

export const isDeepLinkPath = pathname => webPageOf(pathname) !== null;

/** A 302 to the web page, on the site's host only; null otherwise. */
export function deepLinkAnswer(hostKind, pathname) {
  if (hostKind.kind !== 'site') return null;
  const location = webPageOf(pathname);
  return location ? { status: 302, headers: { location, 'cache-control': 'no-store' } } : null;
}
