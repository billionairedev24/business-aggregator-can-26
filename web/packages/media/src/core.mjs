// The photo library's rules, shared by the build (scripts/build.mjs, Node), the web (src/index.ts) and the native apps'
// generator (mobile-kit scripts/gen-brand.mjs): crops, widths, file names, the fallback walk and what a manifest entry
// must say. Plain JavaScript without imports, so every one of them loads it as it is.

/** The crops every photo is cut to, by aspect ratio: tiles, cards, banners and heroes. */
export const CROPS = Object.freeze({ square: [1, 1], card: [4, 3], wide: [16, 9] });

/** The widths of each crop (never wider than the source allows). */
export const WIDTHS = Object.freeze([320, 640, 960, 1440]);

/** Best first: browsers take the first type they read. The JPEG is the `<img>` fallback. */
export const FORMATS = Object.freeze(['avif', 'webp', 'jpg']);

/**
 * Where a photo may come from (docs/runbooks/photos.md): Pexels and Unsplash under their own licences, or a photo
 * found through Openverse (`make photos-fetch`) under CC0, the Public Domain Mark or — with its attribution on the
 * credits page — CC BY / CC BY-SA; or from Google's Open Images dataset: Flickr photos the
 * dataset lists under CC BY 2.0, credited the same way. `hosts` limits the photo page's address (Openverse's sources
 * are many sites).
 */
export const SITES = Object.freeze({
  pexels: { licences: ['Pexels License'], hosts: ['pexels.com', 'www.pexels.com', 'images.pexels.com'] },
  unsplash: { licences: ['Unsplash License'], hosts: ['unsplash.com', 'www.unsplash.com', 'images.unsplash.com'] },
  openverse: { licences: ['CC0 1.0', 'PDM 1.0', 'CC BY 2.0', 'CC BY 2.5', 'CC BY 3.0', 'CC BY 4.0', 'CC BY-SA 2.0', 'CC BY-SA 2.5', 'CC BY-SA 3.0', 'CC BY-SA 4.0'], hosts: null },
  openimages: { licences: ['CC BY 2.0'], hosts: ['www.flickr.com', 'flickr.com'] },
});

/** Sites whose photos carry a Creative Commons deed and the site they were first published on. */
const CC_SITES = new Set(['openverse', 'openimages']);

/** Licences whose terms ask for the photographer's name next to the work (the credits page always gives it). */
export const needsAttribution = licence => /^CC BY/.test(licence ?? '');

/** Bumped when the variants change for the same source (another encoder setting): every file name changes. */
export const PIPELINE = 2;

const SLOT = /^[a-z]+(?:\/[a-z0-9_.-]+)+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const HEX = /^#[0-9a-f]{6}$/;

/** A slot name: `group/name` in lower case (`category/service.home-trades.plumber`, `hero/home-main`). */
export const isSlot = slot => SLOT.test(slot);

/**
 * What is wrong with a manifest entry (empty: nothing). `processed` also asks for what `make photos` writes (size,
 * hash, placeholder colour), so the site never links a variant that was not built.
 */
export function problems(slot, e, { processed = false } = {}) {
  const out = [];
  const need = (ok, what) => { if (!ok) out.push(`${slot}: ${what}`); };
  need(isSlot(slot), 'the slot is not group/name in lower case');
  need(e && typeof e === 'object', 'not an object');
  if (!e || typeof e !== 'object') return out;
  need(typeof e.file === 'string' && /^source\/.+\.(jpe?g|png|webp)$/i.test(e.file), 'file must be source/….jpg (or .png, .webp)');
  need(typeof e.alt?.en === 'string' && e.alt.en.trim().length >= 3, 'alt.en (what the photo shows, in English) is missing');
  need(typeof e.alt?.fr === 'string' && e.alt.fr.trim().length >= 3, 'alt.fr (what the photo shows, in French) is missing');
  need(e.focal && [e.focal.x, e.focal.y].every(v => typeof v === 'number' && v >= 0 && v <= 1), 'focal {x, y} between 0 and 1 is missing');
  const site = SITES[e.site];
  need(!!site, `site must be one of ${Object.keys(SITES).join(', ')}`);
  need(typeof e.photographer === 'string' && e.photographer.trim().length > 0, 'photographer is missing');
  let host = null;
  try { host = new URL(e.sourceUrl).hostname; } catch { /* reported below */ }
  need(!!host && (!site?.hosts || site.hosts.includes(host)), `sourceUrl must be the photo's page${site?.hosts ? ` on ${e.site}` : ''}`);
  need(!!site && site.licences.includes(e.licence), `licence must be one of: ${site?.licences.join(', ') ?? 'Pexels License, Unsplash License, CC0 1.0, …'}`);
  if (CC_SITES.has(e.site)) {
    let licenceHost = null;
    try { licenceHost = new URL(e.licenceUrl).hostname; } catch { /* reported below */ }
    need(licenceHost === 'creativecommons.org', 'licenceUrl (the Creative Commons deed) is missing');
    need(typeof e.source === 'string' && e.source.length > 0, 'source (the site it was published on: flickr, wikimedia, …) is missing');
  }
  need(typeof e.downloaded === 'string' && DATE.test(e.downloaded), 'downloaded (YYYY-MM-DD) is missing');
  if (processed) {
    need(Number.isInteger(e.width) && Number.isInteger(e.height) && e.width > 0 && e.height > 0, 'width/height missing: run make photos');
    need(typeof e.hash === 'string' && /^[0-9a-f]{10}$/.test(e.hash), 'hash missing: run make photos');
    need(typeof e.placeholder === 'string' && HEX.test(e.placeholder), 'placeholder colour missing: run make photos');
  }
  return out;
}

/** Whether `make photos` has processed the entry (only those are linked). */
export const processed = e => !!e && typeof e.hash === 'string' && Number.isInteger(e.width) && Number.isInteger(e.height);

/** The largest box of the crop's aspect inside the source, centred on the focal point as far as the edges allow. */
export function cropBox(width, height, crop, focal = { x: 0.5, y: 0.5 }) {
  const [aw, ah] = CROPS[crop];
  let w = width;
  let h = Math.round((width * ah) / aw);
  if (h > height) { h = height; w = Math.round((height * aw) / ah); }
  const clamp = (v, max) => Math.min(Math.max(v, 0), max);
  return {
    left: clamp(Math.round(focal.x * width - w / 2), width - w),
    top: clamp(Math.round(focal.y * height - h / 2), height - h),
    width: w,
    height: h,
  };
}

/** The widths built for a crop: the standard ones the crop is wide enough for, at least the smallest one possible. */
export function widthsFor(e, crop) {
  const box = cropBox(e.width, e.height, crop, e.focal);
  const fit = WIDTHS.filter(w => w <= box.width);
  return fit.length ? fit : [box.width];
}

/** A variant's path under the photos' base URL (content-hashed: served with a year-long cache). */
export const variantPath = (slot, crop, width, hash, format) => `${slot}/${crop}-${width}.${hash}.${format}`;

/** Every variant of a processed entry: what the build writes and what the pages link. */
export function variants(slot, e) {
  const out = [];
  for (const crop of Object.keys(CROPS)) {
    for (const width of widthsFor(e, crop)) {
      const [aw, ah] = CROPS[crop];
      for (const format of FORMATS) out.push({ crop, width, height: Math.round((width * ah) / aw), format, path: variantPath(slot, crop, width, e.hash, format) });
    }
  }
  return out;
}

/**
 * The first slot of `slots` (and each one's fallbacks, in order) whose photo has been processed; null when none has.
 * `fallback` maps a slot to the slot shown in its place (a leaf category → its group → the landing's banner).
 */
export function resolve(slots, photos, fallback) {
  for (const start of slots) {
    const seen = new Set();
    for (let s = start; s && !seen.has(s); s = fallback[s]) {
      seen.add(s);
      if (processed(photos[s])) return { slot: s, entry: photos[s] };
    }
  }
  return null;
}
