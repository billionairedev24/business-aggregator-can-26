import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import manifest from '../photos.json' with { type: 'json' };
import slotList from '../generated/slots.json' with { type: 'json' };
import { cropBox, problems, resolve, variants, type PhotoEntry } from './core.mjs';
import { hashOf, MAX_SOURCE_BYTES } from '../scripts/build.mjs';
import { categorySlot, credits, departmentSlot, photoFor, serviceSlot } from './index';

const HERE = join(import.meta.dirname, '..');
const photos = (manifest as { photos: Record<string, PhotoEntry> }).photos;
const known = new Set(slotList.slots.map(s => s.slot));

const GOOD: PhotoEntry = {
  file: 'source/category/shop.food-and-grocery.bakery.jpg',
  alt: { en: 'Loaves of sourdough on a wooden counter', fr: 'Miches de pain au levain sur un comptoir en bois' },
  focal: { x: 0.5, y: 0.45 },
  site: 'pexels',
  photographer: 'A. Photographer',
  sourceUrl: 'https://www.pexels.com/photo/bread-1000000/',
  licence: 'Pexels License',
  downloaded: '2026-10-20',
  width: 2000, height: 1333, hash: '0123456789', placeholder: '#a07850',
};

describe('the photo library (photos.json)', () => {
  it.each(Object.entries(photos))('%s names its file, alt text in both languages, source, photographer and licence, and is processed', (slot, e) => {
    expect(problems(slot, e, { processed: true })).toEqual([]);
    expect(known.has(slot), `${slot} is not a slot (make photos-slots lists them)`).toBe(true);
    const file = join(HERE, e.file);
    expect(existsSync(file), `${e.file} is missing`).toBe(true);
    expect(statSync(file).size).toBeLessThanOrEqual(MAX_SOURCE_BYTES);
    expect(e.hash, 'the source changed since make photos ran').toBe(hashOf(readFileSync(file), e.focal));
  });

  it('refuses an entry without its licence, source, photographer or alt text', () => {
    expect(problems('category/x.y', GOOD, { processed: true })).toEqual([]);
    const without = (key: keyof PhotoEntry) => problems('category/x.y', { ...GOOD, [key]: undefined });
    expect(without('licence').join()).toMatch(/licence/);
    expect(without('sourceUrl').join()).toMatch(/sourceUrl/);
    expect(without('photographer').join()).toMatch(/photographer/);
    expect(without('downloaded').join()).toMatch(/downloaded/);
    expect(problems('category/x.y', { ...GOOD, alt: { en: 'Bread', fr: '' } }).join()).toMatch(/alt\.fr/);
    // the licence must be the site's own, and the page on that site
    expect(problems('category/x.y', { ...GOOD, licence: 'Unsplash License' }).join()).toMatch(/licence/);
    expect(problems('category/x.y', { ...GOOD, sourceUrl: 'https://example.com/bread' }).join()).toMatch(/sourceUrl/);
    // Openverse finds: a Creative Commons licence with its deed and the site it came from
    const ov = { ...GOOD, site: 'openverse' as const, sourceUrl: 'https://www.flickr.com/photos/x/1', licence: 'CC BY 2.0' };
    expect(problems('category/x.y', ov).join()).toMatch(/licenceUrl.*source|source/);
    expect(problems('category/x.y', { ...ov, licenceUrl: 'https://creativecommons.org/licenses/by/2.0/', source: 'flickr' })).toEqual([]);
    // Open Images: Flickr photos under CC BY 2.0 only, credited like Openverse's
    const oi = { ...ov, site: 'openimages' as const, licenceUrl: 'https://creativecommons.org/licenses/by/2.0/', source: 'flickr' };
    expect(problems('category/x.y', oi)).toEqual([]);
    expect(problems('category/x.y', { ...oi, licence: 'CC BY-SA 2.0' }).join()).toMatch(/licence/);
    expect(problems('category/x.y', { ...oi, sourceUrl: 'https://example.com/x' }).join()).toMatch(/sourceUrl/);
    expect(problems('category/x.y', { ...ov, licence: 'CC BY-NC 2.0', licenceUrl: 'https://creativecommons.org/licenses/by-nc/2.0/', source: 'flickr' }).join()).toMatch(/licence/);
    // what make photos writes is required before the site links the variants
    expect(problems('category/x.y', { ...GOOD, hash: undefined }, { processed: true }).join()).toMatch(/make photos/);
  });
});

describe('slots, fallbacks and variants', () => {
  it('lists every catalogue category, the departments and the services by slug', () => {
    expect(known.has('category/service.home-trades.plumber')).toBe(true);
    expect(known.has('category/shop.food-and-grocery')).toBe(true);
    expect(known.has('hero/home-main')).toBe(true);
    expect(known.has('demo/listing/bakery.sourdough')).toBe(true);
    expect(departmentSlot('bakery')).toBe('category/shop.food-and-grocery.bakery');
    expect(serviceSlot('plumber')).toBe('category/service.home-trades.plumber');
    expect(serviceSlot('nope')).toBeNull();
    expect(categorySlot('shop.apparel')).toBe('category/shop.apparel');
  });

  it('shows a slot its own photo, else the nearest fallback that has one, else nothing', () => {
    const fb = { 'category/a.b.c': 'category/a.b', 'category/a.b': 'banner/a' };
    expect(resolve(['category/a.b.c'], { 'banner/a': GOOD }, fb)?.slot).toBe('banner/a');
    expect(resolve(['category/a.b.c'], { 'category/a.b.c': GOOD, 'banner/a': GOOD }, fb)?.slot).toBe('category/a.b.c');
    expect(resolve(['category/a.b.c'], { 'category/a.b': { ...GOOD, hash: undefined } }, fb)).toBeNull(); // not processed
    expect(resolve(['x/y', 'category/a.b'], { 'banner/a': GOOD }, fb)?.slot).toBe('banner/a');
  });

  it('cuts each crop around the focal point and never wider than the source', () => {
    expect(cropBox(2000, 1000, 'square', { x: 0.9, y: 0.5 })).toEqual({ left: 1000, top: 0, width: 1000, height: 1000 });
    expect(cropBox(2000, 1000, 'square', { x: 0.1, y: 0.5 })).toEqual({ left: 0, top: 0, width: 1000, height: 1000 });
    const all = variants('category/x.y', GOOD);
    expect(new Set(all.map(v => v.format))).toEqual(new Set(['avif', 'webp', 'jpg']));
    expect(all.filter(v => v.crop === 'square').map(v => v.width)).not.toContain(1440); // 1333 px square at most
    expect(all[0]!.path).toMatch(/^category\/x\.y\/(square|card|wide)-\d+\.0123456789\.(avif|webp|jpg)$/);
  });

  it('resolves nothing while the library is empty, and credits exactly what it holds', () => {
    if (Object.keys(photos).length) return;
    expect(photoFor(['hero/home-main', 'category/service.home-trades.plumber'])).toBeNull();
    expect(credits()).toEqual([]);
  });
});
