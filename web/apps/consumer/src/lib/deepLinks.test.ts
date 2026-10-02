import { describe, expect, it } from 'vitest';
import { deepLinkAnswer, isDeepLinkPath, webPageOf } from '../../server/deep-links.mjs';

/** S-102: an app deep link opened where the app isn't installed lands on the same thing on the web. */
describe('app deep links on the web', () => {
  it.each([
    ['/app/orders/01J9ZD3V00000000000000ORD1', '/orders/01J9ZD3V00000000000000ORD1'],
    ['/app/food/orders/O1', '/food/orders/O1'],
    ['/app/quotes/qt_1', '/quotes/qt_1'],
    ['/app/bookings/B1', '/account/orders'],
    ['/app/cases/RF-2214', '/account/orders?view=cases'],
    ['/courier/run', '/'],
  ])('%s → %s', (path, page) => {
    expect(webPageOf(path)).toBe(page);
    expect(deepLinkAnswer({ kind: 'site' }, path)).toEqual({ status: 302, headers: { location: page, 'cache-control': 'no-store' } });
  });

  it('leaves everything else alone: the OAuth redirects, other paths, odd ids, other hosts', () => {
    for (const path of ['/app/oauth2redirect', '/courier/oauth2redirect', '/orders/O1', '/app/orders/O1/x', '/app/orders/a%2Fb', '/app']) {
      expect(isDeepLinkPath(path)).toBe(false);
    }
    expect(deepLinkAnswer({ kind: 'pages', host: 'pages.northline.test' }, '/app/orders/O1')).toBeNull();
    expect(deepLinkAnswer({ kind: 'custom', host: 'book.example.ca', slug: 'x' }, '/app/orders/O1')).toBeNull();
  });
});
