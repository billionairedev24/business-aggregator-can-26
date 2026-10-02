/**
 * The runtime version (app.config.ts `runtimeVersion: { policy: 'fingerprint' }`, S-103): a hash of the native layer.
 * An EAS Update reaches only binaries with the same fingerprint, so it must change exactly when native code does.
 *
 * - `extra` is skipped: it carries the EXPO_PUBLIC_* values (inlined into the JS bundle) and the EAS project id; if it
 *   counted, an `eas update` from a shell with other variables would publish to a runtime no binary has.
 * - The marketing version and build numbers are skipped: package.json's version and EAS's build numbers change for
 *   every store release without touching native code.
 *
 * Check it: `make mobile-fingerprint APP=<app>` (docs/runbooks/mobile-release.md § Over-the-air updates).
 */
/** @type {import('@expo/fingerprint').Config} */
module.exports = {
  sourceSkips: ['ExpoConfigExtraSection', 'ExpoConfigVersions', 'PackageJsonAndroidAndIosScriptsIfNotContainRun'],
};
