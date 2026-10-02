import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { createConfig, HTTPS_REDIRECT_PATH, PERMISSION_TEXT, REDIRECT_URI } from '../app.config';
import { en } from '../src/i18n/en';
import { frCA } from '../src/i18n/fr-CA';
import { SCREEN_KEYS, SCREENS, TABS } from '../src/screens';

const ROOT = join(__dirname, '..');
const REPO = join(ROOT, '../../..');

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : /\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}
const source = [...files(join(ROOT, 'app')), ...files(join(ROOT, 'src'))];

describe('copy', () => {
  // the parameters a message takes: `{name}` and `{n, plural, …}`, not the words of the plural forms
  const params = (s: string) =>
    [...new Set([...s.replace(/(?:\b(?:zero|one|two|few|many|other)|=\d+)\s*\{/g, '(').matchAll(/\{(\w+)(?:,|\})/g)].map((m) => m[1]))].sort();

  it('has every English key in fr-CA, none empty, with the same parameters', () => {
    expect(Object.keys(frCA).sort()).toEqual(Object.keys(en).sort());
    for (const [key, text] of Object.entries(en)) {
      const fr = frCA[key as keyof typeof en];
      expect(text.trim()).not.toBe('');
      expect(fr.trim()).not.toBe('');
      expect({ key, params: params(fr) }).toEqual({ key, params: params(text) });
    }
  });

  it('names every design screen in both languages, with a header title for every sub-screen', () => {
    const keys = Object.keys(en);
    for (const key of SCREEN_KEYS) {
      expect(keys).toContain(`screen.${key}`);
      if (!SCREENS[key].tab && !['welcome', 'confirmed', 'booked'].includes(key)) expect(keys).toContain(`title.${key}`);
    }
  });

  it('has the iOS permission text and display name in French too', () => {
    const fr = JSON.parse(readFileSync(join(ROOT, 'locales/fr.json'), 'utf8')) as Record<string, string>;
    expect(Object.keys(fr).sort()).toEqual(['CFBundleDisplayName', 'NSLocationWhenInUseUsageDescription']);
  });
});

describe('code rules', () => {
  it('names no province, city or time zone (region-neutral: they come from the region model)', () => {
    const offenders = source.filter((f) => /Alberta|Calgary|Edmonton|America\/[A-Z]|Canada\/[A-Z]/.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });

  it('defines no colour: everything comes from @northline/tokens through the kit', () => {
    const offenders = source.filter((f) => /['"`]#[0-9a-fA-F]{3,8}['"`]|rgba?\(/.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });
});

describe('the screen list (src/screens.ts, docs/MOBILE_PLAN.md)', () => {
  it('has every screen of design 01, a route file for each, and the five tabs in the design order', () => {
    const design = readFileSync(join(REPO, 'design/Consumer Screen.dc.html'), 'utf8');
    const options = JSON.parse(/"screen":\{[^}]*"options":(\[[^\]]+\])/.exec(design.replace(/&quot;/g, '"'))![1]!) as string[];
    expect([...SCREEN_KEYS].sort()).toEqual([...options, 'quote'].sort());
    for (const key of SCREEN_KEYS) expect({ key, exists: existsSync(join(ROOT, 'app', `${SCREENS[key].file}.tsx`)) }).toEqual({ key, exists: true });
    expect(TABS).toEqual(['home', 'services', 'cart', 'orders', 'account']);
    expect(TABS.every((k) => SCREENS[k].tab)).toBe(true);
  });

  it('is documented in MOBILE_PLAN.md: every screen with its route and story', () => {
    const plan = readFileSync(join(REPO, 'docs/MOBILE_PLAN.md'), 'utf8');
    for (const key of SCREEN_KEYS) {
      const route = SCREENS[key].file.replace(/^\(tabs\)\//, '/').replace(/^(?!\/)/, '/');
      expect({ key, listed: plan.includes(`\`${key}\``) && plan.includes(route) }).toEqual({ key, listed: true });
    }
  });
});

describe('the native configuration', () => {
  const env = {
    APP_VARIANT: 'production',
    EXPO_PUBLIC_API_URL: 'https://api.example/api/v1',
    EXPO_PUBLIC_AUTH_ISSUER: 'https://auth.example',
    EXPO_PUBLIC_SITE_ORIGIN: 'https://site.example',
  };

  it("uses the mobile-consumer client's registered redirects and scopes", () => {
    const yml = readFileSync(join(REPO, 'server/auth/src/main/resources/application.yml'), 'utf8');
    const block = yml.slice(yml.indexOf('mobile-consumer:'), yml.indexOf('mobile-consumer:') + 500);
    expect(block).toContain(`- ${REDIRECT_URI}`);
    expect(block).toContain(`- \${CONSUMER_ORIGIN:http://localhost:3000}${HTTPS_REDIRECT_PATH}`);
    expect(block).toContain('scopes: [ openid, profile, orders, bookings, offline_access ]');
    const config = readFileSync(join(ROOT, 'src/config.ts'), 'utf8');
    expect(config).toContain("scopes: ['openid', 'profile', 'orders', 'bookings', 'offline_access']");
    expect(createConfig(env).scheme).toBe('ca.northline.app');
  });

  it('signs people in on the consumer site, not the Studio (northline.auth.consumer-clients)', () => {
    const yml = readFileSync(join(REPO, 'server/auth/src/main/resources/application.yml'), 'utf8');
    expect(/consumer-clients: \[([^\]]+)\]/.exec(yml)![1]).toContain('mobile-consumer');
  });

  it('claims the consumer site for App Links / Universal Links, only over https', () => {
    const c = createConfig(env);
    expect(c.ios?.associatedDomains).toEqual(['applinks:site.example', 'webcredentials:site.example']);
    const https = c.android?.intentFilters?.find((f) => f.autoVerify);
    expect(https?.data).toEqual([{ scheme: 'https', host: 'site.example', pathPrefix: '/app' }]);
    expect(createConfig({ APP_VARIANT: 'development' }).ios?.associatedDomains).toEqual([]);
  });

  it('refuses a store build with the fixture backend or without https', () => {
    expect(() => createConfig({ ...env, EXPO_PUBLIC_FIXTURES: '1' })).toThrow(/FIXTURES/);
    expect(() => createConfig({ ...env, EXPO_PUBLIC_SITE_ORIGIN: 'http://site.example' })).toThrow(/https/);
    expect(() => createConfig({ APP_VARIANT: 'development' })).not.toThrow();
  });

  it('asks only for location while in use, with an honest text, and nothing it does not use', () => {
    const c = createConfig(env);
    expect(c.ios?.infoPlist?.NSLocationWhenInUseUsageDescription).toBe(PERMISSION_TEXT.locationWhenInUse);
    expect(c.ios?.infoPlist).not.toHaveProperty('NSLocationAlwaysAndWhenInUseUsageDescription');
    expect(c.android?.blockedPermissions).toEqual(expect.arrayContaining(['android.permission.ACCESS_BACKGROUND_LOCATION', 'android.permission.CAMERA', 'android.permission.RECORD_AUDIO']));
    expect(c.android?.allowBackup).toBe(false);
  });

  it('gives each variant its own bundle id, and EAS a profile per environment', () => {
    const ids = ['development', 'preview', 'production'].map((v) => createConfig({ ...env, APP_VARIANT: v }).android?.package);
    expect(ids).toEqual(['ca.northline.app.dev', 'ca.northline.app.preview', 'ca.northline.app']);
    const eas = JSON.parse(readFileSync(join(ROOT, 'eas.json'), 'utf8')) as { build: Record<string, { env?: Record<string, string> }> };
    expect(eas.build.development!.env!.EXPO_PUBLIC_SITE_ORIGIN).toBe('https://dev.northline.ca');
    expect(eas.build.preview!.env!.EXPO_PUBLIC_API_URL).toBe('https://api.staging.northline.ca/api/v1');
    expect(eas.build.production!.env!.EXPO_PUBLIC_AUTH_ISSUER).toBe('https://auth.northline.ca');
  });
});
