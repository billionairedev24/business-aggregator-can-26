// App Links / Universal Links for the native apps (S-97; the files S-29 left open): the consumer site proves which
// apps may open its links and the OAuth redirects that land on it.
//
//   /.well-known/apple-app-site-association   iOS: "TEAMID.bundle.id" claims /app/* (consumer app) or /courier/*
//                                             (courier app); webcredentials lets an app use the site's passkeys
//   /.well-known/assetlinks.json              Android: package + signing-certificate SHA-256 may handle all of the
//                                             site's links its manifest asks for (and share sign-in credentials)
//   /app/oauth2redirect, /courier/oauth2redirect
//                                             the page behind the claimed redirect, for when no app opened it; the
//                                             consumer app's in-app sign-in also lands here (its fetch reads the code
//                                             from the URL, the page itself is never shown)
//
// Configuration (the chart sets them per environment: values*.yaml apps.consumer.env; docs/runbooks/mobile.md):
//   NL_APPLE_TEAM_ID         the Apple Developer team id; empty = no apple-app-site-association (404)
//   NL_IOS_BUNDLE_IDS        comma-separated bundle ids (default: the production ones)
//   NL_ANDROID_PACKAGES      comma-separated application ids (default: the production ones)
//   NL_ANDROID_CERT_SHA256   comma-separated SHA-256 fingerprints of the apps' signing certificates (Play app
//                            signing key, AA:BB:…); empty = no assetlinks.json (404)
export const AASA_PATH = '/.well-known/apple-app-site-association';
export const ASSETLINKS_PATH = '/.well-known/assetlinks.json';
export const REDIRECT_PATHS = ['/app/oauth2redirect', '/courier/oauth2redirect'];
export const DEFAULT_APP_IDS = ['ca.northline.app', 'ca.northline.courier'];

const list = (value, fallback = []) => {
  const items = (value ?? '').split(',').map(s => s.trim()).filter(Boolean);
  return items.length ? items : fallback;
};

/** The site paths an app claims: the courier app its /courier/ paths, the consumer app (every other id) /app/. */
export const pathPrefixFor = id => (/\.courier(\.|$)/.test(id) ? '/courier' : '/app');

export function isAppLinkPath(pathname) {
  return pathname === AASA_PATH || pathname === ASSETLINKS_PATH || REDIRECT_PATHS.includes(pathname);
}

export function appLinksConfig(env = process.env) {
  return {
    appleTeamId: (env.NL_APPLE_TEAM_ID ?? '').trim(),
    iosBundleIds: list(env.NL_IOS_BUNDLE_IDS, DEFAULT_APP_IDS),
    androidPackages: list(env.NL_ANDROID_PACKAGES, DEFAULT_APP_IDS),
    androidCertSha256: list(env.NL_ANDROID_CERT_SHA256).map(f => f.toUpperCase()),
  };
}

export function appleAppSiteAssociation({ appleTeamId, iosBundleIds }) {
  const ids = iosBundleIds.map(id => `${appleTeamId}.${id}`);
  return {
    applinks: {
      details: iosBundleIds.map(id => ({
        appIDs: [`${appleTeamId}.${id}`],
        components: [{ '/': `${pathPrefixFor(id)}/*`, comment: 'OAuth redirect and the app\'s links' }],
      })),
    },
    webcredentials: { apps: ids },
  };
}

export function assetLinks({ androidPackages, androidCertSha256 }) {
  return androidPackages.map(pkg => ({
    relation: ['delegate_permission/common.handle_all_urls', 'delegate_permission/common.get_login_creds'],
    target: { namespace: 'android_app', package_name: pkg, sha256_cert_fingerprints: androidCertSha256 },
  }));
}

const REDIRECT_PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>Northline</title></head>
<body style="font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;line-height:1.5">
<h1 style="font-size:1.4rem">Open the Northline app to finish signing in.</h1>
<p>If the app didn't open, go back to it and try again.</p>
<p lang="fr-CA">Ouvrez l’app Northline pour terminer la connexion. Si elle ne s’est pas ouverte, revenez-y et réessayez.</p>
</body></html>
`;

/** The answer for an app-link path, or null when it isn't one. Unconfigured files answer 404 (never a guess). */
export function appLinkAnswer(pathname, config) {
  const json = body => ({ status: 200, type: 'application/json', cache: 'public, max-age=3600', body: JSON.stringify(body) });
  const missing = { status: 404, type: 'text/plain; charset=utf-8', cache: 'no-store', body: 'Not configured.' };
  if (pathname === AASA_PATH) return config.appleTeamId ? json(appleAppSiteAssociation(config)) : missing;
  if (pathname === ASSETLINKS_PATH) return config.androidCertSha256.length ? json(assetLinks(config)) : missing;
  // the URL carries an authorization code: never cached, never sent on as a Referer
  if (REDIRECT_PATHS.includes(pathname)) return { status: 200, type: 'text/html; charset=utf-8', cache: 'no-store', body: REDIRECT_PAGE, referrerPolicy: 'no-referrer' };
  return null;
}
