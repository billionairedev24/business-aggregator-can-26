#!/usr/bin/env node
// make photos-pick (docs/runbooks/photos.md): promotes an Openverse candidate (make photos-fetch, candidates/) into the
// library — the file into source/<slot>.jpg (at most 2048 px, so git stays small) and its entry into photos.json with
// the attribution candidates.json recorded. Then `make photos` builds its variants.
//
//   node scripts/pick.mjs --slot <slot> --n <1-3> --alt-en "…" --alt-fr "…" [--focal 0.5,0.4]
//   node scripts/pick.mjs --picks picks.json      [{ "slot", "n", "alt": { "en", "fr" }, "focal"?: { "x", "y" } }, …]
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { problems } from '../src/core.mjs';
import { MAX_SOURCE_BYTES } from './build.mjs';

const HERE = join(dirname(fileURLToPath(import.meta.url)), '..');
const LONG_SIDE = 2048;

/** The photos.json entry of a candidate (attribution from candidates.json; alt text and focal point from the picker). */
export function entryOf(slot, candidate, { alt, focal = { x: 0.5, y: 0.5 }, fetched }) {
  const site = candidate.source ?? 'openverse';
  return {
    file: `source/${slot}.jpg`,
    alt,
    focal,
    site: 'openverse',
    source: site,
    title: candidate.title ?? undefined,
    photographer: candidate.creator?.trim() || `unknown (${site})`,
    photographerUrl: candidate.creatorUrl ?? undefined,
    sourceUrl: candidate.landingUrl,
    licence: candidate.licence,
    licenceUrl: candidate.licenceUrl,
    downloaded: fetched,
  };
}

async function pick(sharp, state, manifest, { slot, n, alt, focal }) {
  const fetched = state.slots[slot];
  const candidate = fetched?.candidates?.find(c => c.n === Number(n));
  if (!candidate) throw new Error(`${slot}: no candidate ${n} in candidates/candidates.json (make photos-fetch)`);
  const entry = entryOf(slot, candidate, { alt, focal, fetched: fetched.fetched });
  const wrong = problems(slot, entry);
  if (wrong.length) throw new Error(wrong.join('\n'));
  const target = join(HERE, entry.file);
  mkdirSync(dirname(target), { recursive: true });
  await sharp(join(HERE, candidate.file)).rotate()
    .resize({ width: LONG_SIDE, height: LONG_SIDE, fit: 'inside', withoutEnlargement: true })
    .jpeg({ quality: 84, mozjpeg: true }).toFile(target);
  if (statSync(target).size > MAX_SOURCE_BYTES) throw new Error(`${slot}: ${entry.file} is still over ${MAX_SOURCE_BYTES / 1e6} MB`);
  manifest.photos[slot] = entry;
  console.log(`  ${slot} ← candidate ${n}: "${candidate.title ?? ''}" by ${entry.photographer}, ${entry.licence}`);
}

function arg(args, name) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
}

async function main(args) {
  const state = JSON.parse(readFileSync(join(HERE, 'candidates', 'candidates.json'), 'utf8'));
  const manifestPath = join(HERE, 'photos.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const picksFile = arg(args, '--picks');
  const toFocal = s => { const [x, y] = (s ?? '0.5,0.5').split(',').map(Number); return { x, y }; };
  const picks = picksFile
    ? JSON.parse(readFileSync(picksFile, 'utf8'))
    : [{ slot: arg(args, '--slot'), n: arg(args, '--n'), alt: { en: arg(args, '--alt-en'), fr: arg(args, '--alt-fr') }, focal: toFocal(arg(args, '--focal')) }];
  const { default: sharp } = await import('sharp');
  for (const p of picks) await pick(sharp, state, manifest, { ...p, focal: p.focal ?? { x: 0.5, y: 0.5 } });
  // keep photos.json in slot order so the diff of a pick is one block
  manifest.photos = Object.fromEntries(Object.entries(manifest.photos).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  console.log(`${picks.length} photo(s) added to photos.json — now run make photos, check the pages (make ui-shots) and commit source/ + photos.json`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2)).catch(e => { console.error(`photos-pick: ${e.message}`); process.exit(1); });
}
