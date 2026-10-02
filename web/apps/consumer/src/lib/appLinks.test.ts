import { describe, expect, it } from 'vitest';
import { AASA_PATH, ASSETLINKS_PATH, appLinkAnswer, appLinksConfig, isAppLinkPath } from '../../server/app-links.mjs';

const FINGERPRINT = '14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5';

describe('App Links / Universal Links (S-97)', () => {
  const config = appLinksConfig({
    NL_APPLE_TEAM_ID: 'ABCDE12345',
    NL_IOS_BUNDLE_IDS: 'ca.northline.app.dev, ca.northline.courier.dev',
    NL_ANDROID_PACKAGES: 'ca.northline.app.dev,ca.northline.courier.dev',
    NL_ANDROID_CERT_SHA256: FINGERPRINT.toLowerCase(),
  });

  it('serves apple-app-site-association: each app claims its own paths, both may use the site’s passkeys', () => {
    const a = appLinkAnswer(AASA_PATH, config)!;
    expect(a.status).toBe(200);
    expect(a.type).toBe('application/json');
    expect(JSON.parse(a.body)).toEqual({
      applinks: { details: [
        { appIDs: ['ABCDE12345.ca.northline.app.dev'], components: [{ '/': '/app/*', comment: "OAuth redirect and the app's links" }] },
        { appIDs: ['ABCDE12345.ca.northline.courier.dev'], components: [{ '/': '/courier/*', comment: "OAuth redirect and the app's links" }] },
      ] },
      webcredentials: { apps: ['ABCDE12345.ca.northline.app.dev', 'ABCDE12345.ca.northline.courier.dev'] },
    });
  });

  it('serves assetlinks.json with each package and the signing certificate (upper case)', () => {
    const body = JSON.parse(appLinkAnswer(ASSETLINKS_PATH, config)!.body);
    expect(body).toHaveLength(2);
    expect(body[0]).toEqual({
      relation: ['delegate_permission/common.handle_all_urls', 'delegate_permission/common.get_login_creds'],
      target: { namespace: 'android_app', package_name: 'ca.northline.app.dev', sha256_cert_fingerprints: [FINGERPRINT] },
    });
  });

  it('answers 404 rather than guess when the team id or the certificate is not configured', () => {
    const empty = appLinksConfig({});
    expect(empty.iosBundleIds).toEqual(['ca.northline.app', 'ca.northline.courier']);
    expect(appLinkAnswer(AASA_PATH, empty)!.status).toBe(404);
    expect(appLinkAnswer(ASSETLINKS_PATH, empty)!.status).toBe(404);
  });

  it('serves the redirect page uncached and without a Referer, and ignores other paths', () => {
    const page = appLinkAnswer('/app/oauth2redirect', config)!;
    expect(page.cache).toBe('no-store');
    expect(page.referrerPolicy).toBe('no-referrer');
    expect(page.body).toContain('Open the Northline app');
    expect(isAppLinkPath('/courier/oauth2redirect')).toBe(true);
    expect(isAppLinkPath('/app/other')).toBe(false);
    expect(appLinkAnswer('/sitemap.xml', config)).toBeNull();
  });
});
