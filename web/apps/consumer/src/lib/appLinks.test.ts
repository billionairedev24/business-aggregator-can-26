import { describe, expect, it } from 'vitest';
import { appleAppSiteAssociation, createAppLinks, isAppLinkPath, parseAndroidApps } from '../../server/app-links.mjs';

const SITE = { kind: 'site' } as const;
const CERT = '14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5';

describe('app links (S-102)', () => {
  const links = createAppLinks({
    appleTeamId: 'TEAM123456',
    androidApps: `ca.northline.app=${CERT.toLowerCase()};ca.northline.courier=${CERT}`,
  });

  it('serves the apple-app-site-association with the consumer and courier paths', () => {
    const answer = links(SITE, '/.well-known/apple-app-site-association');
    expect(answer?.status).toBe(200);
    expect(answer?.headers['content-type']).toBe('application/json');
    const aasa = JSON.parse(answer!.body);
    expect(aasa.applinks.details[0].appIDs).toEqual(['TEAM123456.ca.northline.app']);
    expect(aasa.applinks.details[0].components).toContainEqual({ '/': '/orders/*' });
    expect(aasa.applinks.details[0].components).toContainEqual({ '/': '/bookings/*' });
    expect(aasa.applinks.details[0].components).toContainEqual({ '/': '/quotes/*' });
    expect(aasa.applinks.details[0].components).toContainEqual({ '/': '/cases/*' });
    expect(aasa.applinks.details[1]).toEqual({ appIDs: ['TEAM123456.ca.northline.courier'], components: [{ '/': '/courier/*' }] });
  });

  it('serves assetlinks.json with each package and its certificate fingerprint', () => {
    const answer = links(SITE, '/.well-known/assetlinks.json');
    const statements = JSON.parse(answer!.body);
    expect(statements).toHaveLength(2);
    expect(statements[0]).toEqual({
      relation: ['delegate_permission/common.handle_all_urls'],
      target: { namespace: 'android_app', package_name: 'ca.northline.app', sha256_cert_fingerprints: [CERT] },
    });
  });

  it('answers 404 until the apps are configured, and nothing on pages or merchant domains', () => {
    const empty = createAppLinks({});
    expect(empty(SITE, '/.well-known/apple-app-site-association')?.status).toBe(404);
    expect(empty(SITE, '/.well-known/assetlinks.json')?.status).toBe(404);
    expect(links({ kind: 'pages', host: 'pages.northline.test' }, '/.well-known/apple-app-site-association')).toBeNull();
    expect(links({ kind: 'custom', host: 'book.example.ca', slug: 'x' }, '/bookings/1')).toBeNull();
  });

  it('sends app-only paths to the account pages on the web', () => {
    expect(links(SITE, '/bookings/01J9ZD3V00000000000000BK01')).toMatchObject({ status: 302, headers: { location: '/account/orders' } });
    expect(links(SITE, '/cases/RF-2214')).toMatchObject({ status: 302, headers: { location: '/account/orders?view=cases' } });
    expect(isAppLinkPath('/orders/1')).toBe(false); // the web has its own order page
    expect(isAppLinkPath('/bookings/1/extra')).toBe(false);
  });

  it('reads the Android settings leniently and the iOS default bundle ids', () => {
    expect(parseAndroidApps(' a.b=AA:BB , CC:DD ; broken ; c.d= ')).toEqual([{ packageName: 'a.b', fingerprints: ['AA:BB', 'CC:DD'] }]);
    expect(appleAppSiteAssociation('T', ['x']).applinks.details).toHaveLength(1);
  });
});
