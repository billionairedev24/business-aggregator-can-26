// @ts-check
/**
 * The offline release checks (S-103): everything that can be wrong in an app's release set-up without asking Expo,
 * Apple or Google. `errors` fail `pnpm lint`; `pending` lists what must still be done before a store release (real
 * screenshots, the App Store Connect ids, …) and fails only `check --strict` (the release workflow's store actions).
 *
 * Files read, per app (mobile/apps/<app>/):
 *   package.json                       the marketing version (the one source; app.config.ts reads it)
 *   app.config.ts                      the privacy manifest's data types and the Android permissions (as text)
 *   eas.json                           build profiles per environment, channels, submit profiles
 *   store/store.config.json            App Store listing en-CA + fr-CA, categories, age rating (EAS Metadata)
 *   store/play/<locale>/…              Google Play listing (fastlane supply layout)
 *   store/play/details.json            Play store settings, content rating and target audience answers
 *   store/privacy.json                 App Privacy (Apple) and Data safety (Google) answers
 *   store/screenshots.json             the screenshots to take, with captions; the files under store/
 *   store/policy.json                  how the app is distributed (public · unlisted) and the age-restricted goods it
 *                                      sells, against the stores' policies (2026-10-04)
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { pngSize } from './png.mjs';
import {
  ADVISORY_VALUES, AGE_CLASSES, APPLE_ADVISORY_FLAGS, APPLE_ADVISORY_LEVELS, APPLE_CATEGORIES, APPLE_LIMITS, DATA_TYPES,
  DISTRIBUTIONS, ENVIRONMENTS, FEATURE_GRAPHIC, LOCALES, PERMISSION_DATA, PLACE_NAMES, PLAY_LIMITS, SCREENSHOT_COUNT,
  SCREENSHOT_SIZES, STORE_ALLOWED_AGE_CLASSES,
} from './spec.mjs';

/** @typedef {import('./spec.mjs').AppSpec} AppSpec */
/** @typedef {{errors: string[], pending: string[]}} Report */

const PLACEHOLDER = /^REPLACE_WITH_[A-Z_]+$/;
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
/** Fields that would put a credential file path into the repository; EAS keeps the keys (`eas credentials`). */
const KEY_PATH_FIELDS = ['serviceAccountKeyPath', 'ascApiKeyPath', 'ascApiKeyId', 'ascApiKeyIssuerId', 'appleId'];
const BUILD_PROFILE_KEYS = new Set([
  'extends', 'environment', 'developmentClient', 'distribution', 'channel', 'autoIncrement', 'env', 'ios', 'android',
  'node', 'pnpm', 'credentialsSource', 'withoutCredentials', 'prebuildCommand', 'buildArtifactPaths', 'cache', 'config',
]);
const PLAY_TRACKS = ['internal', 'alpha', 'beta', 'production'];
const PLAY_RELEASE_STATUS = ['draft', 'inProgress', 'halted', 'completed'];

/** @param {string} file @returns {any} */
const readJson = (file) => JSON.parse(readFileSync(file, 'utf8'));
/** @param {string} file */
const readText = (file) => (existsSync(file) ? readFileSync(file, 'utf8').trim() : '');
/** @param {string} s */
const chars = (s) => [...s].length;
/** @param {unknown} u */
const isHttps = (u) => typeof u === 'string' && /^https:\/\/[^\s/]+\.[^\s]+$/.test(u);

/**
 * eas.json build profile with its `extends` chain merged (env and platform blocks merged one level deep).
 * @param {any} build @param {string} name @returns {any}
 */
export function resolveProfile(build, name, seen = new Set()) {
  const p = build?.[name];
  if (!p) return undefined;
  if (seen.has(name)) throw new Error(`eas.json: build profile ${name} extends itself`);
  seen.add(name);
  const base = p.extends ? resolveProfile(build, p.extends, seen) ?? {} : {};
  return {
    ...base,
    ...p,
    env: { ...base.env, ...p.env },
    ios: { ...base.ios, ...p.ios },
    android: { ...base.android, ...p.android },
  };
}

/** @param {any} submit @param {string} name @returns {any} */
function resolveSubmit(submit, name, seen = new Set()) {
  const p = submit?.[name];
  if (!p || seen.has(name)) return p;
  seen.add(name);
  const base = p.extends ? resolveSubmit(submit, p.extends, seen) ?? {} : {};
  return { ...base, ...p, ios: { ...base.ios, ...p.ios }, android: { ...base.android, ...p.android } };
}

/** eas.json: one profile family per environment with the runbooks' origins and channel; store submit profiles. */
export function checkEas(/** @type {AppSpec} */ app, /** @type {string} */ appDir, /** @type {Report} */ r) {
  const file = join(appDir, 'eas.json');
  if (!existsSync(file)) return void r.errors.push('eas.json: missing');
  const eas = readJson(file);
  if (eas.cli?.appVersionSource !== 'remote') r.errors.push('eas.json: cli.appVersionSource must be "remote" (EAS keeps the build numbers)');
  if (eas.cli?.requireCommit !== true) r.errors.push('eas.json: cli.requireCommit must be true (EAS builds what is committed)');
  for (const [name, profile] of Object.entries(eas.build ?? {})) {
    for (const key of Object.keys(/** @type {object} */ (profile))) {
      if (!BUILD_PROFILE_KEYS.has(key)) r.errors.push(`eas.json: build.${name}.${key} is not an eas.json build key${key === 'releaseChannel' ? ' (classic updates; use channel)' : ''}`);
    }
  }
  const channels = new Map();
  for (const [envName, env] of Object.entries(ENVIRONMENTS)) {
    for (const name of env.profiles) {
      const p = resolveProfile(eas.build, name);
      const where = `eas.json: build.${name} (${envName})`;
      if (!p) {
        r.errors.push(`${where}: missing`);
        continue;
      }
      if (p.channel !== env.channel) r.errors.push(`${where}: channel must be "${env.channel}", is "${p.channel}"`);
      if (channels.has(p.channel) && channels.get(p.channel) !== envName) r.errors.push(`${where}: channel "${p.channel}" is also ${channels.get(p.channel)}'s`);
      channels.set(p.channel, envName);
      if (p.environment !== env.easEnvironment) r.errors.push(`${where}: environment must be "${env.easEnvironment}"`);
      const want = /** @type {Record<string, string>} */ ({
        APP_VARIANT: env.variant,
        EXPO_PUBLIC_API_URL: env.api,
        EXPO_PUBLIC_AUTH_ISSUER: env.auth,
        EXPO_PUBLIC_FIXTURES: '0',
        ...(app.siteOrigin ? { EXPO_PUBLIC_SITE_ORIGIN: env.site } : {}),
      });
      for (const [k, v] of Object.entries(want)) {
        if (p.env?.[k] !== v) r.errors.push(`${where}: env.${k} must be ${v}, is ${p.env?.[k] ?? 'unset'}`);
      }
      for (const [k, v] of Object.entries(p.env ?? {})) {
        if (/TOKEN|SECRET|PASSWORD|PRIVATE|_KEY$/.test(k)) r.errors.push(`${where}: env.${k} looks like a secret — keep it in the EAS environment (eas env:create), not eas.json`);
        if (k.startsWith('EXPO_PUBLIC_') && typeof v === 'string' && v.startsWith('http://')) r.errors.push(`${where}: env.${k} must be https`);
      }
      if (p.credentialsSource !== 'remote') r.errors.push(`${where}: credentialsSource must be "remote" (EAS keeps the signing keys)`);
      if (envName !== 'dev' && p.autoIncrement !== true) r.errors.push(`${where}: autoIncrement must be true (EAS raises the build number)`);
    }
  }
  const prod = resolveProfile(eas.build, 'production');
  if (prod && prod.distribution !== 'store') r.errors.push('eas.json: build.production.distribution must be "store"');
  if (prod && prod.android?.buildType !== 'app-bundle') r.errors.push('eas.json: build.production.android.buildType must be "app-bundle" (Play takes AABs)');
  if (prod?.developmentClient) r.errors.push('eas.json: build.production must not be a development client');

  for (const name of ['internal', 'production']) {
    const s = resolveSubmit(eas.submit, name);
    const where = `eas.json: submit.${name}`;
    if (!s) {
      r.errors.push(`${where}: missing`);
      continue;
    }
    for (const platform of ['ios', 'android']) {
      for (const key of KEY_PATH_FIELDS) {
        if (s[platform]?.[key] !== undefined) r.errors.push(`${where}.${platform}.${key}: store credentials live in EAS (eas credentials) or the CI secrets, never in eas.json`);
      }
    }
    const { ascAppId, appleTeamId, metadataPath } = s.ios ?? {};
    if (PLACEHOLDER.test(ascAppId ?? '')) r.pending.push('eas.json: submit ios.ascAppId — the App Store Connect app id (App Information › Apple ID) once the app record exists');
    else if (!/^\d{6,12}$/.test(ascAppId ?? '')) r.errors.push(`${where}.ios.ascAppId must be the numeric App Store Connect app id (or REPLACE_WITH_…)`);
    if (PLACEHOLDER.test(appleTeamId ?? '')) r.pending.push('eas.json: submit ios.appleTeamId — the Apple Developer team id');
    else if (!/^[A-Z0-9]{10}$/.test(appleTeamId ?? '')) r.errors.push(`${where}.ios.appleTeamId must be a 10-character team id (or REPLACE_WITH_…)`);
    if (metadataPath !== './store/store.config.json') r.errors.push(`${where}.ios.metadataPath must be ./store/store.config.json`);
    const { track, releaseStatus, rollout } = s.android ?? {};
    if (!PLAY_TRACKS.includes(track)) r.errors.push(`${where}.android.track must be one of ${PLAY_TRACKS.join(', ')}`);
    if (!PLAY_RELEASE_STATUS.includes(releaseStatus)) r.errors.push(`${where}.android.releaseStatus must be one of ${PLAY_RELEASE_STATUS.join(', ')}`);
    if (rollout !== undefined && (releaseStatus !== 'inProgress' || !(rollout > 0 && rollout < 1))) r.errors.push(`${where}.android.rollout needs releaseStatus inProgress and a fraction between 0 and 1`);
  }
  // The flow (mobile-release.md): builds go to TestFlight and the Play internal track, production is promoted from there.
  if (resolveSubmit(eas.submit, 'internal')?.android?.track !== 'internal') r.errors.push('eas.json: submit.internal.android.track must be "internal"');
  const prodSubmit = resolveSubmit(eas.submit, 'production');
  const track = DISTRIBUTIONS[app.distribution].track;
  if (prodSubmit?.android && !(prodSubmit.android.track === track && prodSubmit.android.releaseStatus === 'draft')) {
    r.errors.push(
      track === 'production'
        ? 'eas.json: submit.production.android must be the production track as a draft (a person starts the staged rollout)'
        : `eas.json: submit.production.android must be the closed testing track ("${track}") as a draft — the app is unlisted (owner decision 2026-10-04), never a public Play listing`,
    );
  }
}

/** Store text names no province, territory, city or time zone. */
function checkRegionNeutral(/** @type {string} */ where, /** @type {string} */ text, /** @type {Report} */ r) {
  for (const place of PLACE_NAMES) {
    const re = new RegExp(`(^|[^\\p{L}])${place.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}($|[^\\p{L}])`, 'u');
    if (re.test(text)) r.errors.push(`${where}: names "${place}" — store text is region-neutral (no province, city or time zone)`);
  }
}

/** App Store listing (EAS Metadata store.config.json): locales, limits, URLs, categories, age rating. */
export function checkAppleListing(/** @type {string} */ appDir, /** @type {Report} */ r) {
  const file = join(appDir, 'store/store.config.json');
  if (!existsSync(file)) return void r.errors.push('store/store.config.json: missing');
  const config = readJson(file);
  const where = 'store/store.config.json';
  if (config.configVersion !== 0) r.errors.push(`${where}: configVersion must be 0`);
  const apple = config.apple ?? {};
  const locales = Object.keys(apple.info ?? {}).sort();
  if (locales.join() !== [...LOCALES].sort().join()) r.errors.push(`${where}: apple.info must have exactly ${LOCALES.join(' and ')}, has ${locales.join(', ') || 'none'}`);
  for (const locale of locales) {
    const info = apple.info[locale];
    const at = `${where} apple.info.${locale}`;
    for (const key of ['title', 'subtitle', 'description', 'keywords', 'supportUrl', 'privacyPolicyUrl', 'releaseNotes', 'promoText']) {
      if (!info[key] || (Array.isArray(info[key]) && !info[key].length)) r.errors.push(`${at}.${key}: missing`);
    }
    for (const key of /** @type {const} */ (['title', 'subtitle', 'description', 'promoText', 'releaseNotes'])) {
      if (typeof info[key] === 'string' && chars(info[key]) > APPLE_LIMITS[key]) r.errors.push(`${at}.${key}: ${chars(info[key])} characters, the App Store allows ${APPLE_LIMITS[key]}`);
    }
    if (typeof info.title === 'string' && !info.title.includes('Northline')) r.errors.push(`${at}.title: must contain the brand "Northline"`);
    if (Array.isArray(info.keywords)) {
      const bytes = Buffer.byteLength(info.keywords.join(','), 'utf8');
      if (bytes > APPLE_LIMITS.keywordsBytes) r.errors.push(`${at}.keywords: ${bytes} bytes joined with commas, the App Store allows ${APPLE_LIMITS.keywordsBytes}`);
      if (info.keywords.some((/** @type {string} */ k) => /,/.test(k) || k !== k.trim() || !k)) r.errors.push(`${at}.keywords: one keyword per entry, no commas or spaces around`);
      if (new Set(info.keywords.map((/** @type {string} */ k) => k.toLowerCase())).size !== info.keywords.length) r.errors.push(`${at}.keywords: duplicates`);
    } else if (info.keywords !== undefined) r.errors.push(`${at}.keywords: must be a list`);
    for (const key of ['supportUrl', 'privacyPolicyUrl', 'marketingUrl', 'privacyChoicesUrl']) {
      if (info[key] !== undefined && !isHttps(info[key])) r.errors.push(`${at}.${key}: must be an https URL`);
    }
    checkRegionNeutral(at, [info.title, info.subtitle, info.description, info.promoText, info.releaseNotes, ...(info.keywords ?? [])].join('\n'), r);
  }
  const [en, fr] = LOCALES.map((l) => apple.info?.[l]);
  if (en && fr) {
    for (const key of ['subtitle', 'description', 'promoText', 'releaseNotes']) {
      if (en[key] && en[key] === fr[key]) r.errors.push(`${where}: apple.info.fr-CA.${key} is the English text — translate it`);
    }
  }
  const categories = apple.categories ?? [];
  if (!Array.isArray(categories) || categories.length < 1 || categories.length > 2) r.errors.push(`${where}: apple.categories must be a primary and an optional secondary category`);
  for (const c of categories) if (!APPLE_CATEGORIES.includes(c)) r.errors.push(`${where}: apple.categories: unknown or unsuitable category ${c}`);
  if (!/^\d{4} .+/.test(apple.copyright ?? '')) r.errors.push(`${where}: apple.copyright must be "<year> <owner>"`);
  const advisory = apple.advisory ?? {};
  for (const key of APPLE_ADVISORY_LEVELS) {
    if (!ADVISORY_VALUES.includes(advisory[key])) r.errors.push(`${where}: apple.advisory.${key} must be one of ${ADVISORY_VALUES.join(', ')}`);
  }
  for (const key of APPLE_ADVISORY_FLAGS) {
    if (typeof advisory[key] !== 'boolean') r.errors.push(`${where}: apple.advisory.${key} must be true or false`);
  }
  if (apple.release?.automaticRelease !== false || apple.release?.phasedRelease !== true) {
    r.errors.push(`${where}: apple.release must be {automaticRelease: false, phasedRelease: true} (a person releases; 7-day phased release)`);
  }
  if (apple.review) r.errors.push(`${where}: apple.review holds the review account — enter it in App Store Connect, never in the repository`);
  return apple;
}

/** Google Play listing (fastlane supply layout): title, short and full description, release notes, store settings. */
export function checkPlayListing(/** @type {string} */ appDir, /** @type {Report} */ r) {
  const dir = join(appDir, 'store/play');
  const present = existsSync(dir) ? readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort() : [];
  if (present.join() !== [...LOCALES].sort().join()) r.errors.push(`store/play: must have exactly ${LOCALES.join(' and ')}, has ${present.join(', ') || 'none'}`);
  /** @type {Record<string, Record<string, string>>} */
  const texts = {};
  for (const locale of LOCALES) {
    const at = `store/play/${locale}`;
    const t = (texts[locale] = {
      title: readText(join(dir, locale, 'title.txt')),
      short_description: readText(join(dir, locale, 'short_description.txt')),
      full_description: readText(join(dir, locale, 'full_description.txt')),
      changelog: readText(join(dir, locale, 'changelogs/default.txt')),
    });
    for (const [key, value] of Object.entries(t)) {
      const limit = PLAY_LIMITS[/** @type {keyof typeof PLAY_LIMITS} */ (key)];
      const file = key === 'changelog' ? 'changelogs/default.txt' : `${key}.txt`;
      if (!value) r.errors.push(`${at}/${file}: missing or empty`);
      else if (chars(value) > limit) r.errors.push(`${at}/${file}: ${chars(value)} characters, Google Play allows ${limit}`);
    }
    if (t.title && !t.title.includes('Northline')) r.errors.push(`${at}/title.txt: must contain the brand "Northline"`);
    if (/<[a-z]/i.test(t.full_description ?? '')) r.errors.push(`${at}/full_description.txt: plain text only`);
    checkRegionNeutral(at, Object.values(t).join('\n'), r);
  }
  const en = texts[LOCALES[0] ?? ''];
  const fr = texts[LOCALES[1] ?? ''];
  if (en && fr) {
    for (const key of ['short_description', 'full_description', 'changelog']) {
      if (en[key] && en[key] === fr[key]) r.errors.push(`store/play/fr-CA: ${key} is the English text — translate it`);
    }
  }
  const detailsFile = join(dir, 'details.json');
  if (!existsSync(detailsFile)) return void r.errors.push('store/play/details.json: missing');
  const d = readJson(detailsFile);
  if (!LOCALES.includes(d.defaultLanguage)) r.errors.push('store/play/details.json: defaultLanguage must be a listing locale');
  if (!d.category) r.errors.push('store/play/details.json: category missing');
  for (const key of ['website', 'privacyPolicyUrl']) if (!isHttps(d[key])) r.errors.push(`store/play/details.json: ${key} must be an https URL`);
  if (!/^[^@\s]+@[^@\s]+\.[a-z]{2,}$/.test(d.contactEmail ?? '')) r.errors.push('store/play/details.json: contactEmail must be an email address');
  if (d.containsAds !== false) r.errors.push('store/play/details.json: containsAds must be false (Northline shows no ads)');
  if (!Array.isArray(d.targetAudience) || !d.targetAudience.length) r.errors.push('store/play/details.json: targetAudience missing');
  if (!d.contentRating || typeof d.contentRating.answers !== 'object') r.errors.push('store/play/details.json: contentRating.answers missing');
  else for (const [q, a] of Object.entries(d.contentRating.answers)) if (typeof a !== 'boolean') r.errors.push(`store/play/details.json: contentRating.answers.${q} must be true or false`);
  if (!d.appAccess) r.errors.push('store/play/details.json: appAccess (how reviewers sign in) missing');
}

/**
 * store/policy.json (2026-10-04): the distribution the owner chose (public; or unlisted — Apple Unlisted App
 * distribution, a Play closed testing track) and the age-restricted goods the app sells, held to the stores' policies:
 * only alcohol (App Store 1.4.3, Play's Inappropriate Content policy), with the age rating and audience that go with it.
 */
export function checkPolicy(/** @type {AppSpec} */ app, /** @type {string} */ appDir, /** @type {Report} */ r) {
  const file = join(appDir, 'store/policy.json');
  if (!existsSync(file)) return void r.errors.push('store/policy.json: missing');
  const p = readJson(file);
  const where = 'store/policy.json';
  const want = DISTRIBUTIONS[app.distribution];
  if (p.distribution?.apple !== want.apple || p.distribution?.play !== want.play) {
    r.errors.push(`${where}: distribution must be {apple: "${want.apple}", play: "${want.play}"} (${app.distribution}; spec.mjs APPS)`);
  }
  if (!p.distribution?.why) r.errors.push(`${where}: distribution.why: say who the app is for`);
  if (app.distribution === 'unlisted') {
    r.pending.push(`${where}: Apple Unlisted App distribution — request it (developer.apple.com/support/unlisted-app-distribution) once the app is approved, and give couriers the link; Play: add the couriers' Google Group to the closed testing track`);
  }
  const goods = p.ageRestrictedGoods;
  if (!goods || !Array.isArray(goods.inApp)) return void r.errors.push(`${where}: ageRestrictedGoods.inApp missing (a list, [] for none)`);
  for (const c of goods.inApp) {
    if (!AGE_CLASSES.includes(c)) r.errors.push(`${where}: ageRestrictedGoods.inApp: unknown class ${c}`);
    else if (!STORE_ALLOWED_AGE_CLASSES.includes(c)) r.errors.push(`${where}: ageRestrictedGoods.inApp: ${c} — the App Store (1.4.3) and Google Play do not allow apps that facilitate its sale`);
  }
  if (goods.inApp.length === 0) return;
  for (const key of ['ageGate', 'geoRestriction']) if (!goods[key]) r.errors.push(`${where}: ageRestrictedGoods.${key}: say how the app restricts the sale`);
  const apple = existsSync(join(appDir, 'store/store.config.json')) ? readJson(join(appDir, 'store/store.config.json')).apple : undefined;
  if (apple?.advisory?.alcoholTobaccoOrDrugUseOrReferences !== 'FREQUENT_OR_INTENSE') {
    r.errors.push(`${where}: an app that sells ${goods.inApp.join(', ')} answers apple.advisory.alcoholTobaccoOrDrugUseOrReferences FREQUENT_OR_INTENSE (store.config.json)`);
  }
  const details = existsSync(join(appDir, 'store/play/details.json')) ? readJson(join(appDir, 'store/play/details.json')) : undefined;
  if (details?.contentRating?.answers?.sellsAlcoholTobaccoOrAgeRestrictedGoods !== true) {
    r.errors.push(`${where}: an app that sells ${goods.inApp.join(', ')} answers sellsAlcoholTobaccoOrAgeRestrictedGoods true (store/play/details.json)`);
  }
  if (JSON.stringify(details?.targetAudience) !== JSON.stringify(['18 and over'])) {
    r.errors.push(`${where}: an app that sells ${goods.inApp.join(', ')} has the Play target audience ["18 and over"] only (store/play/details.json)`);
  }
}

/** App Privacy and Data safety: both stores told the same, matching the privacy manifest and the permissions. */
export function checkPrivacy(/** @type {string} */ appDir, /** @type {Report} */ r) {
  const file = join(appDir, 'store/privacy.json');
  if (!existsSync(file)) return void r.errors.push('store/privacy.json: missing');
  const p = readJson(file);
  const where = 'store/privacy.json';
  if (p.tracking !== false) r.errors.push(`${where}: tracking must be false (no tracking, no ad identifiers)`);
  if (!Array.isArray(p.sharedWithThirdParties) || p.sharedWithThirdParties.length) r.errors.push(`${where}: sharedWithThirdParties must be [] (service providers acting for Northline are not "sharing")`);
  if (p.encryptedInTransit !== true) r.errors.push(`${where}: encryptedInTransit must be true`);
  const declared = Object.keys(p.collected ?? {});
  for (const type of declared) {
    const at = `${where}: collected.${type}`;
    const d = p.collected[type];
    if (!DATA_TYPES[type]) {
      r.errors.push(`${at}: unknown data type (spec.mjs DATA_TYPES)`);
      continue;
    }
    if (d.linked !== true && d.linked !== false) r.errors.push(`${at}.linked must be true or false`);
    if (d.tracking !== false) r.errors.push(`${at}.tracking must be false`);
    if (!Array.isArray(d.applePurposes) || !d.applePurposes.length) r.errors.push(`${at}.applePurposes missing`);
    if (!Array.isArray(d.playPurposes) || !d.playPurposes.length) r.errors.push(`${at}.playPurposes missing`);
    if ([...(d.applePurposes ?? []), ...(d.playPurposes ?? [])].some((/** @type {string} */ x) => /advertis|marketing/i.test(x))) r.errors.push(`${at}: no advertising or marketing purposes`);
    if (typeof d.optional !== 'boolean' || typeof d.ephemeral !== 'boolean') r.errors.push(`${at}: optional and ephemeral (Data safety) must be true or false`);
    if (!d.why) r.errors.push(`${at}.why: say what the app does with it`);
  }
  // The privacy manifest in app.config.ts must declare exactly these (App Store Connect compares them).
  const config = readText(join(appDir, 'app.config.ts'));
  const manifest = new Set([...config.matchAll(/'(NSPrivacyCollectedDataType(?!Linked|Tracking|Purpose)[A-Za-z]+)'/g)].map((m) => m[1]));
  const wanted = new Set(declared.map((t) => DATA_TYPES[t]?.apple));
  for (const m of manifest) if (!wanted.has(m)) r.errors.push(`app.config.ts privacy manifest declares ${m}, store/privacy.json does not`);
  for (const w of wanted) if (w && !manifest.has(w)) r.errors.push(`store/privacy.json declares ${w}, the privacy manifest in app.config.ts does not`);
  const asked = /ANDROID_PERMISSIONS\s*=\s*\[([\s\S]*?)\]/.exec(config)?.[1] ?? '';
  if (!asked) r.errors.push('app.config.ts: no ANDROID_PERMISSIONS list to compare the privacy answers with');
  for (const [permission, type] of Object.entries(PERMISSION_DATA)) {
    if (asked.includes(`'${permission}'`) && !declared.includes(type)) r.errors.push(`app.config.ts asks for ${permission}: store/privacy.json must declare ${type}`);
  }
  const deletion = p.accountDeletion ?? {};
  // an app that creates no accounts may send people to the website, when the owner decided so (2026-10-04, the courier app)
  const webOnly = deletion.inApp === false && typeof deletion.webOnlyDecision === 'string' && /decision/i.test(deletion.webOnlyDecision) && isHttps(deletion.webUrl);
  if (!webOnly && (deletion.inApp !== true || !isHttps(deletion.webUrl))) r.pending.push(`${where}: accountDeletion — Apple (5.1.1(v)) wants deletion in the app and Google a web link: ${deletion.status ?? 'not available'}`);
}

/** The screenshots to take: each listed shot exists per store and locale at the store's size. */
export function checkScreenshots(/** @type {string} */ appDir, /** @type {Report} */ r) {
  const file = join(appDir, 'store/screenshots.json');
  if (!existsSync(file)) return void r.errors.push('store/screenshots.json: missing');
  const s = readJson(file);
  const shots = Array.isArray(s.shots) ? s.shots : [];
  for (const [platform, { min, max }] of Object.entries(SCREENSHOT_COUNT)) {
    if (shots.length < min || shots.length > max) r.errors.push(`store/screenshots.json: ${shots.length} shots, ${platform} takes ${min}–${max}`);
  }
  /** @param {string} rel @param {{width: number, height: number}} size */
  const image = (rel, size) => {
    const path = join(appDir, rel);
    if (!existsSync(path)) return void r.errors.push(`${rel}: missing (make mobile-store-placeholders)`);
    const got = pngSize(readFileSync(path));
    if (!got || got.width !== size.width || got.height !== size.height) r.errors.push(`${rel}: must be a ${size.width} × ${size.height} PNG, is ${got ? `${got.width} × ${got.height}` : 'not a PNG'}`);
  };
  const names = new Set();
  for (const shot of shots) {
    if (!/^\d\d-[a-z0-9-]+$/.test(shot.name ?? '')) r.errors.push(`store/screenshots.json: shot name "${shot.name}" must be NN-name`);
    if (names.has(shot.name)) r.errors.push(`store/screenshots.json: ${shot.name} twice`);
    names.add(shot.name);
    if (!shot.route || !shot.state) r.errors.push(`store/screenshots.json: ${shot.name} needs the route and the state to capture`);
    for (const locale of LOCALES) {
      const caption = shot.caption?.[locale];
      if (!caption) r.errors.push(`store/screenshots.json: ${shot.name} has no ${locale} caption`);
      else checkRegionNeutral(`store/screenshots.json ${shot.name} ${locale}`, caption, r);
      image(`store/screenshots/ios/${locale}/${shot.name}.png`, SCREENSHOT_SIZES.ios);
      image(`store/play/${locale}/images/phoneScreenshots/${shot.name}.png`, SCREENSHOT_SIZES.android);
    }
  }
  for (const locale of LOCALES) image(`store/play/${locale}/images/featureGraphic.png`, FEATURE_GRAPHIC);
  if (s.placeholder !== false) r.pending.push('store/screenshots: placeholders — capture the listed screens on a device (mobile-release.md § Screenshots) and set "placeholder": false');
}

/** The one source of the marketing version: package.json; app.config.ts reads it. */
export function checkVersion(/** @type {string} */ appDir, /** @type {Report} */ r) {
  const version = readJson(join(appDir, 'package.json')).version;
  if (!SEMVER.test(version ?? '')) r.errors.push(`package.json: version "${version}" must be X.Y.Z`);
  const config = readText(join(appDir, 'app.config.ts'));
  if (/APP_VERSION\s*=\s*['"]/.test(config)) r.errors.push('app.config.ts: APP_VERSION must be read from package.json (the one source of the version), not written there');
  if (!/version:\s*APP_VERSION/.test(config)) r.errors.push('app.config.ts: version must be APP_VERSION');
  if (/buildNumber:\s*['"\d]|versionCode:\s*\d/.test(config)) r.errors.push('app.config.ts: build numbers come from EAS (remote), never written in the config');
  if (!/runtimeVersion:\s*\{\s*policy:\s*'fingerprint'\s*\}/.test(config)) r.errors.push("app.config.ts: runtimeVersion must be { policy: 'fingerprint' } (updates reach only binaries with the same native layer)");
  if (!existsSync(join(appDir, 'fingerprint.config.js'))) r.errors.push('fingerprint.config.js: missing (the runtime fingerprint must skip extra and the version numbers)');
  return version;
}

/** Every check for one app. @param {AppSpec} app @param {string} appDir @returns {Report} */
export function checkApp(app, appDir) {
  /** @type {Report} */
  const r = { errors: [], pending: [] };
  checkVersion(appDir, r);
  checkEas(app, appDir, r);
  checkAppleListing(appDir, r);
  checkPlayListing(appDir, r);
  checkPrivacy(appDir, r);
  checkPolicy(app, appDir, r);
  checkScreenshots(appDir, r);
  return { errors: [...new Set(r.errors)], pending: [...new Set(r.pending)] };
}

/** A release tag (<prefix>X.Y.Z) must name the app's package.json version. @returns {string | null} the error */
export function checkTag(/** @type {AppSpec} */ app, /** @type {string} */ appDir, /** @type {string} */ tag) {
  const version = readJson(join(appDir, 'package.json')).version;
  if (!tag.startsWith(app.tagPrefix) || !SEMVER.test(tag.slice(app.tagPrefix.length))) return `"${tag}" is not a ${app.tagPrefix}X.Y.Z tag`;
  if (tag.slice(app.tagPrefix.length) !== version) return `${tag} does not match ${app.dir}/package.json version ${version}`;
  return null;
}
