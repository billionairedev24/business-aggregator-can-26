// Universal links (iOS) and App Links (Android) for the Northline apps (S-102, docs/runbooks/push.md § Deep links).
//
// The consumer host serves the two association files the phones fetch to trust the apps with its links:
//   /.well-known/apple-app-site-association   iOS — which paths each app (team id + bundle id) may open
//   /.well-known/assetlinks.json              Android — which apps (package + signing certificate SHA-256) may
//                                             verify; the paths themselves are claimed in the app's manifest
// and redirects the app paths the web app has no page of its own for yet (a phone without the app lands on the
// account pages instead of a 404):
//   /bookings/<id> → /account/orders · /cases/<ref> → /account/orders?view=cases
// Only on the site's own host — never on pages.<zone> or a merchant's custom domain. Without NL_APPLE_TEAM_ID /
// NL_ANDROID_APP_CERTS the corresponding file is 404: there is nothing to claim yet.
//
//   NL_APPLE_TEAM_ID        Apple developer team id (10 characters)
//   NL_IOS_APPS             bundle ids, comma-separated: consumer app first, then the courier app
//                           (default ca.northline.app,ca.northline.courier)
//   NL_ANDROID_APP_CERTS    package=SHA256:FINGERPRINT pairs, ';'-separated (several fingerprints: ',' between them)

/** The consumer app's link paths (what a push or an email opens) and the OAuth redirect (S-29). */
export const CONSUMER_PATHS = ['/orders/*', '/food/orders/*', '/bookings/*', '/quotes/*', '/cases/*', '/app/*'];
/** The courier app's paths: its run and its OAuth redirect (S-87). */
export const COURIER_PATHS = ['/courier/*'];

const AASA = '/.well-known/apple-app-site-association';
const ASSET_LINKS = '/.well-known/assetlinks.json';

export const isAppLinkPath = pathname =>
  pathname === AASA || pathname === ASSET_LINKS || /^\/bookings\/[^/]+\/?$/.test(pathname) || /^\/cases\/[^/]+\/?$/.test(pathname);

export function appleAppSiteAssociation(teamId, bundleIds) {
  const [consumer, courier] = bundleIds;
  const details = [];
  if (consumer) details.push({ appIDs: [`${teamId}.${consumer}`], components: CONSUMER_PATHS.map(path => ({ '/': path })) });
  if (courier) details.push({ appIDs: [`${teamId}.${courier}`], components: COURIER_PATHS.map(path => ({ '/': path })) });
  return { applinks: { details } };
}

export function assetLinks(apps) {
  return apps.map(({ packageName, fingerprints }) => ({
    relation: ['delegate_permission/common.handle_all_urls'],
    target: { namespace: 'android_app', package_name: packageName, sha256_cert_fingerprints: fingerprints },
  }));
}

/** `ca.northline.app=AB:CD…,EF:…;ca.northline.courier=12:34…` → [{ packageName, fingerprints }]. */
export function parseAndroidApps(text) {
  return (text ?? '')
    .split(';')
    .map(entry => entry.trim())
    .filter(Boolean)
    .map(entry => {
      const [packageName, certs = ''] = entry.split('=');
      return { packageName: packageName.trim(), fingerprints: certs.split(',').map(c => c.trim().toUpperCase()).filter(Boolean) };
    })
    .filter(app => app.packageName && app.fingerprints.length);
}

/**
 * The answer for an app-link path on the site's host, or null for any other host (pages.<zone>, a merchant's domain).
 * @returns {null | { status: number, headers: Record<string, string>, body: string }}
 */
export function createAppLinks({ appleTeamId, iosApps, androidApps } = {}) {
  const teamId = (appleTeamId ?? '').trim();
  const bundles = (iosApps ?? 'ca.northline.app,ca.northline.courier').split(',').map(b => b.trim()).filter(Boolean);
  const android = parseAndroidApps(androidApps);
  const json = value => ({
    status: 200,
    headers: { 'content-type': 'application/json', 'cache-control': 'public, max-age=3600' },
    body: JSON.stringify(value),
  });
  const notFound = { status: 404, headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' }, body: 'Not found' };
  const redirect = location => ({ status: 302, headers: { location, 'cache-control': 'no-store' }, body: '' });

  return (hostKind, pathname) => {
    if (hostKind.kind !== 'site') return null;
    if (pathname === AASA) return teamId ? json(appleAppSiteAssociation(teamId, bundles)) : notFound;
    if (pathname === ASSET_LINKS) return android.length ? json(assetLinks(android)) : notFound;
    if (/^\/bookings\/[^/]+\/?$/.test(pathname)) return redirect('/account/orders');
    if (/^\/cases\/[^/]+\/?$/.test(pathname)) return redirect('/account/orders?view=cases');
    return null;
  };
}
