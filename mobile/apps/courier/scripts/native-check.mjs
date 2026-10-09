// The courier app's counterpart of the consumer app's native check (mobile gaps part 1): generates the native projects
// the way EAS Build does (`expo prebuild`, Continuous Native Generation) for the development and production variants and
// checks what the run, the proofs, push and the pilot screenshot depend on — without Xcode or the Android SDK. android/
// and ios/ are deleted afterwards (never committed) and package.json is restored (prebuild rewrites its scripts).
//
//   node scripts/native-check.mjs        (pnpm native:check; make courier-native-check)
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
  production: { APP_VARIANT: 'production', EXPO_PUBLIC_API_URL: 'https://api.northline.ca/api/v1', EXPO_PUBLIC_AUTH_ISSUER: 'https://auth.northline.ca' },
  development: { APP_VARIANT: 'development', EXPO_PUBLIC_API_URL: 'https://api.dev.northline.ca/api/v1', EXPO_PUBLIC_AUTH_ISSUER: 'https://auth.dev.northline.ca' },
};
const removed = (manifest, p) => new RegExp(`${p}" tools:node="remove"`).test(manifest);

try {
  for (const [variant, env] of Object.entries(ENVS)) {
    console.log(`\n# ${variant}`);
    execFileSync('npx', ['expo', 'prebuild', '--clean', '--no-install', '--platform', 'all'], { cwd: ROOT, env: { ...process.env, ...env, CI: '1' }, stdio: 'pipe' });
    const id = variant === 'production' ? 'ca.northline.courier' : 'ca.northline.courier.dev';
    const manifest = read('android/app/src/main/AndroidManifest.xml');
    const gradle = read('android/app/build.gradle');
    const target = readdirSync(join(ROOT, 'ios')).find((d) => d.endsWith('.xcodeproj'))?.replace('.xcodeproj', '') ?? 'NorthlineCourier';
    const plist = read(`ios/${target}/Info.plist`);
    const entitlements = read(`ios/${target}/${target}.entitlements`);
    const pbx = read(`ios/${target}.xcodeproj/project.pbxproj`);

    check(`${variant}: android applicationId ${id}`, gradle.includes(`applicationId '${id}'`));
    check(`${variant}: ios bundle id ${id}`, new RegExp(`PRODUCT_BUNDLE_IDENTIFIER = "?${id.replace(/\./g, '\\.')}"?;`).test(pbx));
    check(`${variant}: android OAuth redirect ca.northline.courier:/oauth2redirect`, /<data android:scheme="ca.northline.courier" android:path="\/oauth2redirect"\/>/.test(manifest));
    check(`${variant}: ios scheme ca.northline.courier`, plist.includes('<string>ca.northline.courier</string>'));
    check(`${variant}: android camera (door photo), background location for the run`, manifest.includes('android.permission.CAMERA"/>') && manifest.includes('android.permission.ACCESS_BACKGROUND_LOCATION"/>'));
    check(`${variant}: android no microphone, storage or media (the screenshot comes through the system photo picker)`, ['RECORD_AUDIO', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'READ_MEDIA_IMAGES'].every((p) => removed(manifest, p)));
    check(`${variant}: ios camera, location and photo-library texts (ours), no microphone`, plist.includes('Northline Courier uses the camera') && plist.includes('Northline Courier opens your photos only when') && plist.includes('NSLocationAlwaysAndWhenInUseUsageDescription') && !plist.includes('NSMicrophoneUsageDescription'));
    const aps = variant === 'production' ? 'production' : 'development';
    check(`${variant}: ios push entitlement aps-environment ${aps}`, new RegExp(`<key>aps-environment</key>\\s*<string>${aps}</string>`).test(entitlements));
    check(`${variant}: android notifications permission and the "updates" channel`, manifest.includes('android.permission.POST_NOTIFICATIONS') && /default_notification_channel_id"[^>]*android:value="updates"/.test(manifest));
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
