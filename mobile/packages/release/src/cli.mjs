#!/usr/bin/env node
// @ts-check
/**
 * Release helpers for the consumer and courier apps (S-103; docs/runbooks/mobile-release.md). Offline: no Expo, Apple
 * or Google account is needed for any of these.
 *
 *   node src/cli.mjs check [--app consumer|courier] [--strict]   the release set-up checks (part of `pnpm lint`);
 *                                                                 --strict also fails on what is still pending
 *   node src/cli.mjs version <app>                                the app's marketing version (package.json)
 *   node src/cli.mjs set-version <app> <X.Y.Z>                    change it (the one place it is written)
 *   node src/cli.mjs tag <app> <tag>                              the release tag must be <app>-v<version>
 *   node src/cli.mjs env <app> <profile>                          the profile's build env as KEY=value lines (eas update, expo config)
 *   node src/cli.mjs placeholders [--app <app>]                   (re)write the placeholder screenshots and feature graphics
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { checkApp, checkTag, resolveProfile } from './check.mjs';
import { placeholderPng, rgb } from './png.mjs';
import { APPS, FEATURE_GRAPHIC, LOCALES, SCREENSHOT_SIZES } from './spec.mjs';

const MOBILE = join(dirname(fileURLToPath(import.meta.url)), '../../..');
/** @param {string} name */
const appOf = (name) => {
  const app = APPS[/** @type {keyof typeof APPS} */ (name)];
  if (!app) fail(`unknown app "${name}" (consumer or courier)`);
  return /** @type {import('./spec.mjs').AppSpec} */ (app);
};
/** @param {string} msg @returns {never} */
function fail(msg) {
  console.error(msg);
  process.exit(1);
}
/** @param {string[]} args @param {string} flag */
const option = (args, flag) => {
  const i = args.indexOf(flag);
  return i >= 0 ? args[i + 1] : undefined;
};

const [command, ...args] = process.argv.slice(2);
switch (command) {
  case 'check': {
    const only = option(args, '--app');
    const strict = args.includes('--strict');
    let failed = 0;
    for (const [name, app] of Object.entries(APPS)) {
      if (only && only !== name) continue;
      const { errors, pending } = checkApp(app, join(MOBILE, app.dir));
      const blocking = strict ? [...errors, ...pending] : errors;
      failed += blocking.length;
      console.log(`${name}: ${errors.length ? `${errors.length} error(s)` : 'release set-up ok'}${pending.length ? `, ${pending.length} pending before a store release` : ''}`);
      for (const e of errors) console.log(`  ✗ ${e}`);
      for (const p of pending) console.log(`  ${strict ? '✗' : '·'} pending: ${p}`);
    }
    if (failed) fail(`release check: ${failed} problem(s)${strict ? ' (--strict)' : ''} — docs/runbooks/mobile-release.md`);
    break;
  }
  case 'version':
    console.log(JSON.parse(readFileSync(join(MOBILE, appOf(args[0] ?? '').dir, 'package.json'), 'utf8')).version);
    break;
  case 'set-version': {
    const app = appOf(args[0] ?? '');
    const version = args[1] ?? '';
    if (!/^\d+\.\d+\.\d+$/.test(version)) fail(`"${version}" is not X.Y.Z`);
    const file = join(MOBILE, app.dir, 'package.json');
    const text = readFileSync(file, 'utf8');
    writeFileSync(file, text.replace(/("version":\s*")[^"]+(")/, `$1${version}$2`));
    console.log(`${app.packageName} ${version} — commit it, then tag ${app.tagPrefix}${version}`);
    break;
  }
  case 'tag': {
    const app = appOf(args[0] ?? '');
    const error = checkTag(app, join(MOBILE, app.dir), args[1] ?? '');
    if (error) fail(error);
    console.log(args[1]);
    break;
  }
  case 'env': {
    const app = appOf(args[0] ?? '');
    const eas = JSON.parse(readFileSync(join(MOBILE, app.dir, 'eas.json'), 'utf8'));
    const profile = resolveProfile(eas.build, args[1] ?? '');
    if (!profile) fail(`no build profile "${args[1]}" in ${app.dir}/eas.json`);
    for (const [k, v] of Object.entries(profile.env ?? {})) console.log(`${k}=${v}`);
    break;
  }
  case 'placeholders': {
    const tokens = JSON.parse(readFileSync(join(MOBILE, '../web/packages/tokens/tokens.json'), 'utf8')).base;
    // Spruce top band, paper body, honey foot: obviously not a real screenshot.
    const bands = [{ until: 0.12, color: rgb(tokens.accent) }, { until: 0.94, color: rgb(tokens.bg) }, { until: 1, color: rgb(tokens.highlight) }];
    const only = option(args, '--app');
    for (const [name, app] of Object.entries(APPS)) {
      if (only && only !== name) continue;
      const appDir = join(MOBILE, app.dir);
      const { shots } = JSON.parse(readFileSync(join(appDir, 'store/screenshots.json'), 'utf8'));
      /** @param {string} rel @param {{width: number, height: number}} size */
      const write = (rel, size) => {
        mkdirSync(dirname(join(appDir, rel)), { recursive: true });
        writeFileSync(join(appDir, rel), placeholderPng(size.width, size.height, bands));
      };
      for (const locale of LOCALES) {
        for (const shot of shots) {
          write(`store/screenshots/ios/${locale}/${shot.name}.png`, SCREENSHOT_SIZES.ios);
          write(`store/play/${locale}/images/phoneScreenshots/${shot.name}.png`, SCREENSHOT_SIZES.android);
        }
        write(`store/play/${locale}/images/featureGraphic.png`, { width: FEATURE_GRAPHIC.width, height: FEATURE_GRAPHIC.height });
      }
      console.log(`${name}: placeholders for ${shots.length} shots × ${LOCALES.length} locales`);
    }
    break;
  }
  default:
    fail('usage: cli.mjs check [--app <app>] [--strict] | version <app> | set-version <app> <X.Y.Z> | tag <app> <tag> | env <app> <profile> | placeholders [--app <app>]');
}
