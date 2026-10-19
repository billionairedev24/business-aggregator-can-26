#!/usr/bin/env node
// make photos-fetch (docs/runbooks/photos.md): candidate photos for every slot that has none yet, found through the
// Openverse API (https://api.openverse.org/v1/images/, anonymous) — openly licensed photographs, CC0 or the Public
// Domain Mark (no attribution needed), with --commercial also CC BY / CC BY-SA when those find too few. The top three
// per slot go to web/packages/media/candidates/<slot>/<n>.jpg and candidates.json records what each one is, who made
// it, where it came from and its licence. A person then picks (make photos-pick); nothing here touches photos.json.
//
// Runs on any machine with Node 22 (no dependencies): the owner's Mac when this environment cannot reach the photo
// hosts. Polite to the API (a pause between calls, longer waits on 429) and resumable: a slot already searched is
// skipped, so re-running after a rate limit continues where it stopped.
//
//   node scripts/photos/fetch.mjs [--commercial] [--group category|hero|banner|…] [--slots a,b] [--limit N] [--per 3]
//   env: OPENVERSE_DELAY_MS (pause between calls, default 1500), OPENVERSE_TOKEN (optional registered-client token)
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const MEDIA = join(REPO, 'web', 'packages', 'media');
export const API = 'https://api.openverse.org/v1/images/';
/** Photographic sources first: free stock sites, then museums and Wikimedia, then Flickr. */
export const PREFERRED = ['stocksnap', 'rawpixel', 'wikimedia', 'flickr'];
/** Words that mark a drawing, not a photo, in a result's title or tags. */
const NOT_A_PHOTO = /\b(clip ?art|illustration|vector|drawing|cartoon|icon|logo|diagram|render(ing)?|3d|painting|sketch|poster|map)\b/i;
export const MIN_WIDTH = 1600;
/** Slots shown wide (heroes, banners, empty pages): landscape only. */
const WIDE = /^(hero|banner|empty)\//;
/** Licences we accept (web/packages/media/src/core.mjs SITES.openverse): no NC, no ND. */
const ACCEPTED = new Set(['cc0', 'pdm', 'by', 'by-sa']);
const MAX_BYTES = 25_000_000;
/** Candidates are made smaller before they go into git: plenty for crops up to 1440 px. */
const LONG_SIDE = 2048;

/** Openverse's licence code and version as the library writes it ("CC0 1.0", "CC BY 2.0"). */
export function licenceName(code, version) {
  if (code === 'cc0') return 'CC0 1.0';
  if (code === 'pdm') return 'PDM 1.0';
  return `CC ${code.toUpperCase()} ${version ?? '4.0'}`;
}

/** The results worth downloading for a slot, best first. */
export function rank(results, slot) {
  const wide = WIDE.test(slot);
  return results
    .filter(r => ACCEPTED.has(r.license) && (r.width ?? 0) >= MIN_WIDTH && (!wide || (r.width ?? 0) > (r.height ?? 0)))
    .filter(r => (r.category == null || r.category === 'photograph') && !NOT_A_PHOTO.test(`${r.title ?? ''} ${(r.tags ?? []).map(t => t.name ?? t).join(' ')}`))
    .filter(r => !r.filetype || /^(jpe?g|png|webp)$/i.test(r.filetype))
    .map(r => ({ r, p: PREFERRED.indexOf(r.source ?? r.provider) }))
    .sort((a, b) => (a.p < 0 ? 99 : a.p) - (b.p < 0 ? 99 : b.p) || (b.r.width ?? 0) - (a.r.width ?? 0))
    .map(x => x.r);
}

export function searchUrl(query, slot, commercial) {
  const q = new URLSearchParams({ q: query, page_size: '20', mature: 'false', category: 'photograph', size: 'large', filter_dead: 'true' });
  if (commercial) q.set('license_type', 'commercial'); else q.set('license', 'cc0,pdm');
  if (WIDE.test(slot)) q.set('aspect_ratio', 'wide');
  return `${API}?${q}`;
}

class RateLimited extends Error {}

/**
 * Searches and downloads. Everything outside is injected (fetch, sleep, the files' places), so the tests run it
 * against a fake Openverse.
 */
export async function run({
  fetchImpl = globalThis.fetch, sleep = ms => new Promise(r => setTimeout(r, ms)), log = console.log,
  slots, photos, outDir = join(MEDIA, 'candidates'), commercial = false, per = 3, limit = Infinity,
  delayMs = Number(process.env.OPENVERSE_DELAY_MS ?? 1500), token = process.env.OPENVERSE_TOKEN, shrink = shrinkImage,
}) {
  mkdirSync(outDir, { recursive: true });
  const statePath = join(outDir, 'candidates.json');
  const state = existsSync(statePath) ? JSON.parse(readFileSync(statePath, 'utf8')) : { $comment: 'make photos-fetch (scripts/photos/fetch.mjs): Openverse candidates per slot; make photos-pick promotes one. Licences as Openverse reports them — check the landing page before you pick.', slots: {} };
  const save = () => writeFileSync(statePath, JSON.stringify(state, null, 2) + '\n');
  const headers = { 'user-agent': 'northline-photos-fetch/1 (docs/runbooks/photos.md)', ...(token ? { authorization: `Bearer ${token}` } : {}) };

  async function call(url, accept = 'application/json') {
    for (let attempt = 0; ; attempt++) {
      const res = await fetchImpl(url, { headers: { ...headers, accept } });
      if (res.status !== 429 && res.status < 500) return res;
      if (attempt >= 4) {
        if (res.status === 429) throw new RateLimited(`Openverse is rate-limiting this address: run make photos-fetch again later (it resumes)`);
        return res;
      }
      const retryAfter = Number(res.headers?.get?.('retry-after'));
      const wait = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 5000 * 2 ** attempt;
      log(`  … ${res.status}, waiting ${Math.round(wait / 1000)} s`);
      await sleep(wait);
    }
  }

  // searched before: skipped; one that found nothing is searched again when --commercial widens the licences
  const searched = s => state.slots[s.slot]?.status === 'done' || (state.slots[s.slot]?.status === 'empty' && (!commercial || state.slots[s.slot].commercial));
  const todo = slots.filter(s => !photos[s.slot] && !searched(s)).slice(0, limit);
  let filled = 0, empty = 0, stopped = null;
  for (const s of todo) {
    try {
      let found = [];
      for (const mode of commercial ? [false, true] : [false]) {
        const res = await call(searchUrl(s.query, s.slot, mode));
        await sleep(delayMs);
        if (!res.ok) { log(`  ! ${s.slot}: search answered ${res.status}`); break; }
        const body = await res.json();
        found = [...found, ...rank(body.results ?? [], s.slot).filter(r => !found.some(f => f.id === r.id))];
        if (found.length >= per) break;
      }
      const dir = join(outDir, s.slot);
      rmSync(dir, { recursive: true, force: true });
      const candidates = [];
      for (const r of found) {
        if (candidates.length >= per) break;
        const res = await call(r.url, 'image/*');
        await sleep(delayMs);
        const type = res.headers?.get?.('content-type') ?? '';
        if (!res.ok || !/^image\/(jpeg|png|webp)/.test(type)) continue;
        const bytes = Buffer.from(await res.arrayBuffer());
        if (bytes.length > MAX_BYTES) continue;
        mkdirSync(dir, { recursive: true });
        const n = candidates.length + 1;
        const file = join(dir, `${n}.jpg`);
        writeFileSync(file, bytes);
        shrink(file, type);
        candidates.push({
          n, file: relative(MEDIA, file), openverseId: r.id, title: r.title ?? null, creator: r.creator ?? null, creatorUrl: r.creator_url ?? null,
          source: r.source ?? r.provider ?? null, landingUrl: r.foreign_landing_url ?? null, imageUrl: r.url,
          licence: licenceName(r.license, r.license_version), licenceCode: r.license, licenceUrl: r.license_url ?? null,
          width: r.width ?? null, height: r.height ?? null,
        });
      }
      state.slots[s.slot] = { query: s.query, status: candidates.length ? 'done' : 'empty', commercial, fetched: new Date().toISOString().slice(0, 10), candidates };
      save();
      if (candidates.length) filled++; else empty++;
      log(`  ${candidates.length ? '✓' : '·'} ${s.slot} (${candidates.length}) — "${s.query}"`);
    } catch (e) {
      if (e instanceof RateLimited) { stopped = e.message; break; }
      throw e;
    }
  }
  save();
  const all = Object.values(state.slots);
  const summary = {
    searched: filled + empty, filled, empty, stopped,
    total: { withCandidates: all.filter(x => x.status === 'done').length, empty: all.filter(x => x.status === 'empty').length, left: slots.filter(s => !photos[s.slot] && !state.slots[s.slot]).length },
  };
  log(`\nthis run: ${filled} slot(s) with candidates, ${empty} with none${stopped ? ` — stopped: ${stopped}` : ''}`);
  log(`so far: ${summary.total.withCandidates} slot(s) with candidates, ${summary.total.empty} with none, ${summary.total.left} not searched yet → ${relative(process.cwd(), statePath)}`);
  return summary;
}

/**
 * Makes a candidate JPEG at most LONG_SIDE px on its long side: macOS's `sips` (every Mac has it), else the library's
 * sharp when web/ is installed, else it stays as downloaded.
 */
export function shrinkImage(file, type) {
  const big = statSync(file).size > 1_200_000 || !/jpeg/.test(type);
  if (!big) return;
  const tmp = `${file}.tmp.jpg`;
  try {
    execFileSync('sips', ['-s', 'format', 'jpeg', '-s', 'formatOptions', '85', '-Z', String(LONG_SIDE), file, '--out', tmp], { stdio: 'ignore' });
    renameSync(tmp, file);
    return;
  } catch { /* not a Mac */ }
  try {
    execFileSync(process.execPath, ['-e', `require('sharp')(process.argv[1]).rotate().resize({width:${LONG_SIDE},height:${LONG_SIDE},fit:'inside',withoutEnlargement:true}).jpeg({quality:85,mozjpeg:true}).toFile(process.argv[2]).then(()=>{},e=>{console.error(e);process.exit(1)})`, file, tmp], { cwd: MEDIA, stdio: 'ignore' });
    renameSync(tmp, file);
  } catch { rmSync(tmp, { force: true }); }
}

function arg(args, name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
}

async function main(args) {
  const all = JSON.parse(readFileSync(join(MEDIA, 'generated', 'slots.json'), 'utf8')).slots;
  const photos = JSON.parse(readFileSync(join(MEDIA, 'photos.json'), 'utf8')).photos;
  const group = arg(args, '--group');
  const only = arg(args, '--slots')?.split(',');
  const slots = all.filter(s => (!group || s.group === group) && (!only || only.includes(s.slot)));
  console.log(`Openverse candidates for ${slots.filter(s => !photos[s.slot]).length} slot(s) without a photo${args.includes('--commercial') ? ' (CC0/PDM, then CC BY/BY-SA)' : ' (CC0 and Public Domain Mark only)'}`);
  const summary = await run({ slots, photos, commercial: args.includes('--commercial'), per: Number(arg(args, '--per', 3)), limit: Number(arg(args, '--limit', Infinity)) });
  if (summary.stopped) process.exitCode = 3;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2)).catch(e => { console.error(e); process.exit(1); });
}
