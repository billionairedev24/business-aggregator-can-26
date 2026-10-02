// @ts-check
// The release checks (S-103) on the real apps, and on copies broken one way at a time.
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

import { checkApp, checkTag, resolveProfile } from '../src/check.mjs';
import { pngSize, placeholderPng } from '../src/png.mjs';
import { APPS } from '../src/spec.mjs';

const MOBILE = join(dirname(fileURLToPath(import.meta.url)), '../../..');
const scratch = mkdtempSync(join(tmpdir(), 'nl-release-'));
after(() => rmSync(scratch, { recursive: true, force: true }));

let n = 0;
/** A copy of the app's release files to break. @param {'consumer' | 'courier'} name */
function copyOf(name) {
  const from = join(MOBILE, APPS[name].dir);
  const to = join(scratch, `${name}-${n++}`);
  for (const f of ['package.json', 'app.config.ts', 'eas.json', 'fingerprint.config.js', 'store']) cpSync(join(from, f), join(to, f), { recursive: true });
  return to;
}
/** @param {string} dir @param {string} rel @param {(x: any) => void} change */
function editJson(dir, rel, change) {
  const file = join(dir, rel);
  const json = JSON.parse(readFileSync(file, 'utf8'));
  change(json);
  writeFileSync(file, JSON.stringify(json, null, 2));
}
/** @param {string} dir @param {string} rel @param {(s: string) => string} change */
const editText = (dir, rel, change) => writeFileSync(join(dir, rel), change(readFileSync(join(dir, rel), 'utf8')));
/** @param {'consumer' | 'courier'} name @param {string} dir @param {RegExp} expected */
function failsWith(name, dir, expected) {
  const { errors } = checkApp(APPS[name], dir);
  assert.ok(errors.some((e) => expected.test(e)), `expected an error matching ${expected}, got:\n${errors.join('\n') || '(none)'}`);
}

describe('the apps as committed', () => {
  for (const name of /** @type {const} */ (['consumer', 'courier'])) {
    it(`${name}: no errors; only the account-dependent items are pending`, () => {
      const { errors, pending } = checkApp(APPS[name], join(MOBILE, APPS[name].dir));
      assert.deepEqual(errors, []);
      assert.ok(pending.some((p) => /ascAppId/.test(p)));
      assert.ok(pending.some((p) => /screenshots: placeholders/.test(p)));
    });
  }
});

describe('eas.json', () => {
  it('holds each environment to its origins and channel', () => {
    const dir = copyOf('consumer');
    editJson(dir, 'eas.json', (e) => (e.build.preview.env.EXPO_PUBLIC_API_URL = 'https://api.northline.ca/api/v1'));
    failsWith('consumer', dir, /build\.preview \(staging\): env\.EXPO_PUBLIC_API_URL must be https:\/\/api\.staging/);
    editJson(dir, 'eas.json', (e) => (e.build.production.channel = 'preview'));
    failsWith('consumer', dir, /build\.production \(prod\): channel must be "production"/);
  });

  it("checks the consumer app's site origin (App Links) but not the courier app's", () => {
    const dir = copyOf('consumer');
    editJson(dir, 'eas.json', (e) => delete e.build.development.env.EXPO_PUBLIC_SITE_ORIGIN);
    failsWith('consumer', dir, /build\.development \(dev\): env\.EXPO_PUBLIC_SITE_ORIGIN must be https:\/\/dev\.northline\.ca/);
    assert.deepEqual(checkApp(APPS.courier, copyOf('courier')).errors, []);
  });

  it('refuses secrets, key paths and legacy keys in eas.json', () => {
    const dir = copyOf('courier');
    editJson(dir, 'eas.json', (e) => {
      e.build.production.env.EXPO_TOKEN = 'fake-token-for-test';
      e.submit.internal.android.serviceAccountKeyPath = './play-fake.json';
      e.build.preview.releaseChannel = 'staging';
    });
    failsWith('courier', dir, /EXPO_TOKEN looks like a secret/);
    failsWith('courier', dir, /serviceAccountKeyPath: store credentials live in EAS/);
    failsWith('courier', dir, /releaseChannel is not an eas.json build key \(classic updates/);
  });

  it('wants remote build numbers and a draft production release', () => {
    const dir = copyOf('courier');
    editJson(dir, 'eas.json', (e) => {
      e.cli.appVersionSource = 'local';
      e.build.production.autoIncrement = false;
      e.submit.production.android.releaseStatus = 'completed';
    });
    failsWith('courier', dir, /appVersionSource must be "remote"/);
    failsWith('courier', dir, /build\.production \(prod\): autoIncrement must be true/);
    failsWith('courier', dir, /submit\.production\.android must be the production track as a draft/);
  });

  it('accepts real store ids and refuses malformed ones', () => {
    const dir = copyOf('consumer');
    editJson(dir, 'eas.json', (e) => Object.assign(e.submit.base.ios, { ascAppId: '1234567890', appleTeamId: 'ABCDE12345' }));
    assert.ok(!checkApp(APPS.consumer, dir).pending.some((p) => /ascAppId|appleTeamId/.test(p)));
    editJson(dir, 'eas.json', (e) => (e.submit.base.ios.ascAppId = 'ca.northline.app'));
    failsWith('consumer', dir, /ascAppId must be the numeric App Store Connect app id/);
  });

  it('merges extends chains', () => {
    const eas = JSON.parse(readFileSync(join(MOBILE, 'apps/consumer/eas.json'), 'utf8'));
    const p = resolveProfile(eas.build, 'development-device');
    assert.equal(p.channel, 'development');
    assert.equal(p.env.EXPO_PUBLIC_FIXTURES, '0');
    assert.equal(p.ios.simulator, false);
  });
});

describe('store listings', () => {
  it('holds the App Store fields to their limits', () => {
    const dir = copyOf('consumer');
    editJson(dir, 'store/store.config.json', (c) => {
      c.apple.info['en-CA'].subtitle = 'A subtitle far longer than thirty characters';
      c.apple.info['fr-CA'].keywords = [...c.apple.info['fr-CA'].keywords, 'réfrigérateur', 'électroménager', 'déménagement'];
    });
    failsWith('consumer', dir, /apple\.info\.en-CA\.subtitle: 44 characters, the App Store allows 30/);
    failsWith('consumer', dir, /apple\.info\.fr-CA\.keywords: \d+ bytes/);
  });

  it('holds the Play fields to their limits', () => {
    const dir = copyOf('courier');
    editText(dir, 'store/play/fr-CA/short_description.txt', (s) => `${s.trim()} Et encore plus de mots pour dépasser.`);
    failsWith('courier', dir, /store\/play\/fr-CA\/short_description\.txt: \d+ characters, Google Play allows 80/);
  });

  it('wants English and French in both stores, translated', () => {
    const dir = copyOf('consumer');
    editJson(dir, 'store/store.config.json', (c) => (c.apple.info['fr-CA'].description = c.apple.info['en-CA'].description));
    failsWith('consumer', dir, /fr-CA\.description is the English text/);
    editJson(dir, 'store/store.config.json', (c) => delete c.apple.info['fr-CA']);
    failsWith('consumer', dir, /apple\.info must have exactly en-CA and fr-CA/);
    rmSync(join(dir, 'store/play/fr-CA'), { recursive: true });
    failsWith('consumer', dir, /store\/play: must have exactly en-CA and fr-CA/);
  });

  it('is region-neutral', () => {
    const dir = copyOf('consumer');
    editText(dir, 'store/play/en-CA/full_description.txt', (s) => `${s}\nNow open in Calgary.`);
    editJson(dir, 'store/store.config.json', (c) => (c.apple.info['fr-CA'].promoText = 'Maintenant en Alberta.'));
    failsWith('consumer', dir, /store\/play\/en-CA: names "Calgary"/);
    failsWith('consumer', dir, /apple\.info\.fr-CA: names "Alberta"/);
  });

  it('wants https URLs, a known category, every age-rating answer and no review account', () => {
    const dir = copyOf('courier');
    editJson(dir, 'store/store.config.json', (c) => {
      c.apple.info['en-CA'].privacyPolicyUrl = 'http://northline.ca/legal/privacy.html';
      c.apple.categories = ['GAMES'];
      delete c.apple.advisory.contests;
      c.apple.review = { demoUsername: 'reviewer@example.test', demoPassword: 'fake-password' };
    });
    failsWith('courier', dir, /privacyPolicyUrl: must be an https URL/);
    failsWith('courier', dir, /unknown or unsuitable category GAMES/);
    failsWith('courier', dir, /apple\.advisory\.contests must be one of/);
    failsWith('courier', dir, /apple\.review holds the review account/);
  });
});

describe('privacy answers', () => {
  it('must match the privacy manifest in app.config.ts', () => {
    const dir = copyOf('consumer');
    editJson(dir, 'store/privacy.json', (p) => delete p.collected.DeviceID);
    failsWith('consumer', dir, /privacy manifest declares NSPrivacyCollectedDataTypeDeviceID, store\/privacy\.json does not/);
    failsWith('consumer', dir, /asks for android\.permission\.POST_NOTIFICATIONS: store\/privacy\.json must declare DeviceID/);
  });

  it('refuses tracking, sharing and advertising', () => {
    const dir = copyOf('courier');
    editJson(dir, 'store/privacy.json', (p) => {
      p.tracking = true;
      p.sharedWithThirdParties = ['An ad network'];
      p.collected.UserID.playPurposes.push('Advertising or marketing');
    });
    failsWith('courier', dir, /tracking must be false/);
    failsWith('courier', dir, /sharedWithThirdParties must be \[\]/);
    failsWith('courier', dir, /collected\.UserID: no advertising or marketing purposes/);
  });

  it('notices a camera the answers do not mention', () => {
    const dir = copyOf('courier');
    editJson(dir, 'store/privacy.json', (p) => delete p.collected.PhotosorVideos);
    editText(dir, 'app.config.ts', (s) => s.replace("'NSPrivacyCollectedDataTypePhotosorVideos',", ''));
    failsWith('courier', dir, /asks for android\.permission\.CAMERA: store\/privacy\.json must declare PhotosorVideos/);
  });
});

describe('screenshots', () => {
  it('wants every listed shot per store and locale at the store size', () => {
    const dir = copyOf('courier');
    writeFileSync(join(dir, 'store/play/fr-CA/images/phoneScreenshots/02-run.png'), placeholderPng(1080, 2400, [{ until: 1, color: [0, 0, 0] }]));
    rmSync(join(dir, 'store/screenshots/ios/en-CA/03-pickup.png'));
    failsWith('courier', dir, /02-run\.png: must be a 1080 × 1920 PNG, is 1080 × 2400/);
    failsWith('courier', dir, /ios\/en-CA\/03-pickup\.png: missing/);
  });

  it('wants a caption in both languages', () => {
    const dir = copyOf('consumer');
    editJson(dir, 'store/screenshots.json', (s) => delete s.shots[0].caption['fr-CA']);
    failsWith('consumer', dir, /01-home has no fr-CA caption/);
  });

  it('writes and reads PNG sizes', () => {
    const png = placeholderPng(1320, 2868, [{ until: 0.5, color: [30, 77, 54] }, { until: 1, color: [247, 244, 238] }]);
    assert.deepEqual(pngSize(png), { width: 1320, height: 2868 });
    assert.ok(png.length < 64 * 1024, `placeholder is ${png.length} bytes`);
    assert.equal(pngSize(Buffer.from('not a png at all, really not')), null);
  });
});

describe('versions', () => {
  it('reads the version from package.json only', () => {
    const dir = copyOf('consumer');
    editText(dir, 'app.config.ts', (s) => s.replace(/export const APP_VERSION: string = .*\n/, "export const APP_VERSION = '9.9.9';\n"));
    failsWith('consumer', dir, /APP_VERSION must be read from package\.json/);
  });

  it('wants a semantic version and a fingerprint runtime', () => {
    const dir = copyOf('courier');
    editJson(dir, 'package.json', (p) => (p.version = '1.0'));
    editText(dir, 'app.config.ts', (s) => s.replace("runtimeVersion: { policy: 'fingerprint' }", "runtimeVersion: '1.0.0'"));
    failsWith('courier', dir, /version "1\.0" must be X\.Y\.Z/);
    failsWith('courier', dir, /runtimeVersion must be \{ policy: 'fingerprint' \}/);
  });

  it('matches release tags to the version', () => {
    const dir = join(MOBILE, APPS.consumer.dir);
    const version = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8')).version;
    assert.equal(checkTag(APPS.consumer, dir, `consumer-v${version}`), null);
    assert.match(checkTag(APPS.consumer, dir, 'consumer-v99.0.0') ?? '', /does not match/);
    assert.match(checkTag(APPS.consumer, dir, `courier-v${version}`) ?? '', /not a consumer-vX\.Y\.Z tag/);
  });
});
