#!/usr/bin/env node
// make photos (docs/runbooks/photos.md): every photo of the library (photos.json, sources in source/) cut to the
// crops (square tiles, 4:3 cards, 16:9 banners and heroes) around its focal point, at each width the source allows,
// as AVIF, WebP and JPEG, into the folder the consumer site serves at /photos/ (content-hashed names, a year's cache).
// It also writes each entry's width, height, hash and placeholder colour back into photos.json — commit that.
//
//   node scripts/build.mjs                 build what is missing, prune variants nothing links any more
//   node scripts/build.mjs --out <dir>     somewhere else (a CDN upload, NL_PHOTOS_BASE_URL)
//   node scripts/build.mjs --check         fail when photos.json lacks what the build writes (nothing is built)
//
// Next to the variants it writes credits.json (S-181): the machine-readable attribution every CC BY photo needs —
// for each slot its source file, its variants' folder, title, photographer, the photo's page, the licence and its
// deed — the same data the /credits page renders, shipped with the images (and to a CDN with --out).
//
// Variants already on disk are kept (the name holds the source's hash), so a re-run only does new photos.
import { createHash } from 'node:crypto';
import { availableParallelism } from 'node:os';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cropBox, PIPELINE, problems, variants } from '../src/core.mjs';

const HERE = join(dirname(fileURLToPath(import.meta.url)), '..');
export const DEFAULT_OUT = join(HERE, '..', '..', 'apps', 'consumer', 'public', 'photos');
/** A source photo stays small enough for git without LFS: about 2000 px on the long side is plenty for 1440 px. */
export const MAX_SOURCE_BYTES = 2_500_000;
const VARIANT = /^(square|card|wide)-\d+\.[0-9a-f]{10}\.(avif|webp|jpg)$/;
/** S-181: banners and heroes show 16:9 up to 1440 px wide; a narrower source shows at its own width, never stretched. */
export const BANNER_WIDTH = 1440;
const BANNER = /^(hero|banner)\//;

/** credits.json: one row per photo, what a CC BY licence asks us to say (photos.json is the source of truth). */
export function creditsOf(photos) {
  return Object.entries(photos).map(([slot, e]) => ({
    slot, file: e.file, variants: `${slot}/`, title: e.title ?? null, alt: e.alt, photographer: e.photographer,
    photographerUrl: e.photographerUrl ?? null, site: e.site, source: e.source ?? null, sourceUrl: e.sourceUrl,
    licence: e.licence, licenceUrl: e.licenceUrl ?? null, downloaded: e.downloaded, width: e.width ?? null, height: e.height ?? null,
  }));
}

/** The content hash in the variants' names: the source's bytes, its focal point and the pipeline version. */
export function hashOf(bytes, focal) {
  return createHash('sha256').update(bytes).update(`|${focal.x},${focal.y}|${PIPELINE}`).digest('hex').slice(0, 10);
}

const ENCODE = {
  avif: s => s.avif({ quality: 50, effort: 2 }), // effort 4 took ~10 s a photo; 2 looks the same at these sizes
  webp: s => s.webp({ quality: 72 }),
  jpg: s => s.jpeg({ quality: 76, progressive: true, mozjpeg: true }),
};

async function main(args) {
  const outAt = args.indexOf('--out');
  const out = outAt >= 0 ? args[outAt + 1] : DEFAULT_OUT;
  const check = args.includes('--check');
  const manifestPath = join(HERE, 'photos.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const entries = Object.entries(manifest.photos);
  const errors = entries.flatMap(([slot, e]) => problems(slot, e));
  for (const [slot, e] of entries) {
    const file = e?.file && join(HERE, e.file);
    if (!file || !existsSync(file)) errors.push(`${slot}: ${e?.file} does not exist`);
    else if (statSync(file).size > MAX_SOURCE_BYTES) errors.push(`${slot}: ${e.file} is over ${MAX_SOURCE_BYTES / 1e6} MB — save the site's "large" size (about 2000 px wide)`);
  }
  if (errors.length) { console.error(`photos.json:\n  ${errors.join('\n  ')}`); process.exit(1); }

  if (check) {
    const stale = entries.filter(([, e]) => e.hash !== hashOf(readFileSync(join(HERE, e.file)), e.focal) || !e.placeholder || !e.width);
    if (stale.length) { console.error(`photos.json is not processed for: ${stale.map(([s]) => s).join(', ')} — run make photos`); process.exit(1); }
    console.log(`photos: ${entries.length} processed`);
    return;
  }

  const { default: sharp } = await import('sharp');
  sharp.cache(false);
  mkdirSync(out, { recursive: true });
  const wanted = new Set();
  let written = 0;
  let changed = false;
  // a few photos at a time: AVIF encoding dominates, and the first build of a fresh clone does every photo
  const queue = [...entries];
  const work = async () => {
    for (let next = queue.shift(); next; next = queue.shift()) await buildOne(...next);
  };
  async function buildOne(slot, e) {
    const bytes = readFileSync(join(HERE, e.file));
    const hash = hashOf(bytes, e.focal);
    const upright = () => sharp(bytes).rotate(); // EXIF orientation applied
    if (e.hash !== hash || !e.width || !e.placeholder) {
      const { info } = await upright().toBuffer({ resolveWithObject: true });
      const { dominant } = await upright().stats();
      const hex = [dominant.r, dominant.g, dominant.b].map(v => v.toString(16).padStart(2, '0')).join('');
      Object.assign(e, { width: info.width, height: info.height, hash, placeholder: `#${hex}` });
      changed = true;
    }
    for (const v of variants(slot, e)) {
      const path = join(out, v.path);
      wanted.add(path);
      if (existsSync(path)) continue;
      mkdirSync(dirname(path), { recursive: true });
      const box = cropBox(e.width, e.height, v.crop, e.focal);
      await ENCODE[v.format](upright().extract(box).resize({ width: v.width, height: v.height, fit: 'cover' })).toFile(path);
      written++;
    }
    process.stdout.write(`  ${slot}\n`);
  }
  await Promise.all(Array.from({ length: Math.max(1, Math.min(4, availableParallelism() - 1)) }, work));
  if (changed) writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  const credits = join(out, 'credits.json');
  writeFileSync(credits, JSON.stringify({ $comment: 'GENERATED by web/packages/media/scripts/build.mjs from photos.json: the attribution of every stock photo served here (also /credits).', count: entries.length, photos: creditsOf(manifest.photos) }, null, 1) + '\n');
  wanted.add(credits);
  const narrow = entries.filter(([slot, e]) => BANNER.test(slot) && cropBox(e.width, e.height, 'wide', e.focal).width < BANNER_WIDTH).map(([slot, e]) => `${slot} (${e.width}×${e.height})`);
  if (narrow.length) console.log(`  ${narrow.length} banner/hero photo(s) narrower than ${BANNER_WIDTH} px show at their own width, framed (never stretched): ${narrow.join(', ')}`);
  let pruned = 0;
  const walk = dir => {
    for (const name of existsSync(dir) ? readdirSync(dir) : []) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) { walk(p); if (!readdirSync(p).length) rmSync(p, { recursive: true }); }
      else if (!wanted.has(p) && VARIANT.test(name)) { rmSync(p); pruned++; } // never anything the build didn't write
    }
  };
  walk(out);
  console.log(`photos: ${entries.length} in the library, ${written} variant(s) written, ${pruned} pruned → ${relative(process.cwd(), out) || '.'}${changed ? '; photos.json updated (commit it)' : ''}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2)).catch(e => { console.error(e); process.exit(1); });
}
