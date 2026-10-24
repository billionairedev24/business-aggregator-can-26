import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { screenRuleHits } from '@northline/mobile-kit';

import { createConfig, PERMISSION_TEXT, REDIRECT_URI } from '../app.config';
import { en } from '../src/i18n/en';
import { frCA } from '../src/i18n/fr-CA';

const ROOT = join(__dirname, '..');

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

  it('has the iOS permission texts in French too', () => {
    const fr = JSON.parse(readFileSync(join(ROOT, 'locales/fr.json'), 'utf8')) as Record<string, string>;
    expect(Object.keys(fr)).toEqual(
      expect.arrayContaining(['NSCameraUsageDescription', 'NSLocationWhenInUseUsageDescription', 'NSLocationAlwaysAndWhenInUseUsageDescription']),
    );
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

  // S-169: `radius.pill` is 3 pt in Plainsight (never pills); what is a circle says `radius.round`, a control `radius.control`
  it('never asks for a pill radius', () => {
    expect(source.filter((f) => /radius\.pill/.test(readFileSync(f, 'utf8')))).toEqual([]);
  });

  // S-178/S-179: every screen on the kit's roles, scale and components (the native lint:tokens, mobile-kit's rules.ts):
  // no raw colours, font sizes, line heights, spacing or radii, no retired display face, linen, drawings, hand-made
  // prices or the native switch
  it('uses the kit’s tokens and components only, on every screen', () => {
    const hits = source.filter((f) => /\.tsx$/.test(f)).flatMap((f) => screenRuleHits(f.slice(ROOT.length + 1), readFileSync(f, 'utf8')));
    expect(hits).toEqual([]);
  });

  // S-188: the courier keeps its sunlight rules — ink on white, large numbers — and its only violet is the primary
  // action (the kit's Button): no link or action colour, no violet tertiary, no display face, the tab bar in ink
  it('draws in ink, with violet only for the primary action (S-188)', () => {
    const violet = /semantic\.link\b|semantic\[['"](?:link|action-primary|action-accent|nav-active-marker)['"]\]|textStyles\.link\b|variant=["']tertiary["']|textStyles\.display|tone=["']brand["']/;
    expect(source.filter((f) => violet.test(readFileSync(f, 'utf8'))).map((f) => f.slice(ROOT.length + 1))).toEqual([]);
  });

  // S-169: the shared chrome draws with the kit's type roles (textStyles / TYPE), never a raw font size or the retired display face
  it('sets no raw font size in the shared chrome', () => {
    const chrome = ['src/components/ui.tsx', 'app/(app)/(tabs)/_layout.tsx'].map((f) => join(ROOT, f));
    expect(chrome.filter((f) => /fontSize:\s*\d|fonts\.display/.test(readFileSync(f, 'utf8')))).toEqual([]);
  });
});

describe('the native configuration', () => {
  const env = { APP_VARIANT: 'production', EXPO_PUBLIC_API_URL: 'https://api.example/api/v1', EXPO_PUBLIC_AUTH_ISSUER: 'https://auth.example' };

  it("redirects to the courier-app client's registered redirect URI", () => {
    const yml = readFileSync(join(ROOT, '../../../server/auth/src/main/resources/application.yml'), 'utf8');
    const courier = yml.slice(yml.indexOf('courier-app:'), yml.indexOf('courier-app:') + 600);
    expect(courier).toContain(`- ${REDIRECT_URI}`);
    expect(courier).toContain('scopes: [ openid, courier, deliveries ]');
    expect(createConfig(env).scheme).toBe('ca.northline.courier');
  });

  it('refuses a store build with the fixture backend or without https', () => {
    expect(() => createConfig({ ...env, EXPO_PUBLIC_FIXTURES: '1' })).toThrow(/FIXTURES/);
    expect(() => createConfig({ ...env, EXPO_PUBLIC_API_URL: 'http://api.example' })).toThrow(/https/);
    expect(() => createConfig({ APP_VARIANT: 'development' })).not.toThrow();
  });

  it('asks for the camera and location with honest texts, background location only for runs, no microphone', () => {
    const c = createConfig(env);
    expect(c.ios?.bundleIdentifier).toBe('ca.northline.courier');
    expect(c.ios?.infoPlist?.NSLocationAlwaysAndWhenInUseUsageDescription).toBe(PERMISSION_TEXT.locationAlways);
    expect(PERMISSION_TEXT.locationAlways).toMatch(/latest position/);
    expect(c.android?.blockedPermissions).toContain('android.permission.RECORD_AUDIO');
    expect(c.android?.permissions).toContain('android.permission.FOREGROUND_SERVICE_LOCATION');
    const location = (c.plugins ?? []).find((p) => Array.isArray(p) && p[0] === 'expo-location') as [string, Record<string, unknown>];
    expect(location[1]).toMatchObject({ isIosBackgroundLocationEnabled: true, isAndroidForegroundServiceEnabled: true });
    expect(c.android?.allowBackup).toBe(false);
  });

  it('gives each variant its own bundle id', () => {
    const ids = ['development', 'preview', 'production'].map((v) => createConfig({ ...env, APP_VARIANT: v }).android?.package);
    expect(new Set(ids).size).toBe(3);
  });
});
