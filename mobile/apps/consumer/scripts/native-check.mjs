// Generates the native projects the way EAS Build does (`expo prebuild`, Continuous Native Generation) for the
// development and production variants and checks what reviewers and the sign-in depend on — without Xcode or the
// Android SDK (S-97). android/ and ios/ are deleted afterwards (never committed) and package.json is restored
// (prebuild rewrites its scripts).
//
//   node scripts/native-check.mjs        (pnpm native:check; make mobile-consumer-native-check)
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const pkg = readFileSync(join(ROOT, 'package.json'), 'utf8');
const failures = [];
const check = (label, ok) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}`);
  if (!ok) failures.push(label);
};
const read = (p) => (existsSync(join(ROOT, p)) ? readFileSync(join(ROOT, p), 'utf8') : '');
const ENVS = {
  production: { APP_VARIANT: 'production', EXPO_PUBLIC_API_URL: 'https://api.northline.ca/api/v1', EXPO_PUBLIC_AUTH_ISSUER: 'https://auth.northline.ca', EXPO_PUBLIC_SITE_ORIGIN: 'https://northline.ca' },
  development: { APP_VARIANT: 'development', EXPO_PUBLIC_API_URL: 'https://api.dev.northline.ca/api/v1', EXPO_PUBLIC_AUTH_ISSUER: 'https://auth.dev.northline.ca', EXPO_PUBLIC_SITE_ORIGIN: 'https://dev.northline.ca' },
};

try {
  for (const [variant, env] of Object.entries(ENVS)) {
    console.log(`\n# ${variant}`);
    execFileSync('npx', ['expo', 'prebuild', '--clean', '--no-install', '--platform', 'all'], { cwd: ROOT, env: { ...process.env, ...env, CI: '1' }, stdio: 'pipe' });
    const host = new URL(env.EXPO_PUBLIC_SITE_ORIGIN).host;
    const id = variant === 'production' ? 'ca.northline.app' : 'ca.northline.app.dev';
    const manifest = read('android/app/src/main/AndroidManifest.xml');
    const gradle = read('android/app/build.gradle');
    // the Xcode project is named after the variant's display name (Northline, NorthlineDev, …)
    const target = readdirSync(join(ROOT, 'ios')).find((d) => d.endsWith('.xcodeproj'))?.replace('.xcodeproj', '') ?? 'Northline';
    const plist = read(`ios/${target}/Info.plist`);
    const entitlements = read(`ios/${target}/${target}.entitlements`);
    const pbx = read(`ios/${target}.xcodeproj/project.pbxproj`);

    check(`${variant}: android applicationId ${id}`, gradle.includes(`applicationId '${id}'`));
    check(`${variant}: android OAuth redirect ca.northline.app:/oauth2redirect`, /<data android:scheme="ca.northline.app" android:path="\/oauth2redirect"\/>/.test(manifest));
    check(`${variant}: android verified App Link on ${host}/app`, new RegExp(`autoVerify="true"[\\s\\S]*?android:host="${host.replace(/\./g, '\\.')}" android:pathPrefix="/app"`).test(manifest));
    check(`${variant}: android location while in use only`, manifest.includes('ACCESS_FINE_LOCATION"/>') && /ACCESS_BACKGROUND_LOCATION" tools:node="remove"/.test(manifest));
    check(`${variant}: android no camera, microphone or storage`, ['CAMERA', 'RECORD_AUDIO', 'READ_EXTERNAL_STORAGE'].every((p) => new RegExp(`${p}" tools:node="remove"`).test(manifest)));
    check(`${variant}: android no backup`, manifest.includes('android:allowBackup="false"'));
    check(`${variant}: android cleartext only in development`, manifest.includes(`android:usesCleartextTraffic="${variant === 'development'}"`));
    check(`${variant}: ios bundle id ${id}`, new RegExp(`PRODUCT_BUNDLE_IDENTIFIER = "?${id.replace(/\./g, '\\.')}"?;`).test(pbx));
    check(`${variant}: ios scheme ca.northline.app`, plist.includes('<string>ca.northline.app</string>'));
    check(`${variant}: ios location text, no "Always" or motion`, plist.includes('Northline uses your location') && !plist.includes('NSLocationAlways') && !plist.includes('NSMotionUsageDescription'));
    check(`${variant}: ios associated domains applinks + webcredentials:${host}`, entitlements.includes(`applinks:${host}`) && entitlements.includes(`webcredentials:${host}`));
    check(`${variant}: ios French localisation`, plist.includes('<string>fr</string>'));
    check(`${variant}: ios privacy manifest without tracking`, /NSPrivacyTracking<\/key>\s*<false\/>/.test(read(`ios/${target}/PrivacyInfo.xcprivacy`)));
  }
} finally {
  rmSync(join(ROOT, 'android'), { recursive: true, force: true });
  rmSync(join(ROOT, 'ios'), { recursive: true, force: true });
  writeFileSync(join(ROOT, 'package.json'), pkg);
}
if (failures.length) {
  console.error(`\n${failures.length} check(s) failed`);
  process.exit(1);
}
console.log('\nnative check: all passed');
