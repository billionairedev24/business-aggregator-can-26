/**
 * The photo library at run time (docs/runbooks/photos.md): which photo a slot shows — its own, else its fallback's (a
 * leaf category → its group → the landing's banner) — and the URLs of its variants. A slot without any photo resolves
 * to null and the page draws its illustration instead. Pure data and functions: the web (`@northline/ui` Photo) and
 * the native apps' generator use it; nothing here touches the DOM.
 */
import manifest from '../photos.json' with { type: 'json' };
import runtime from '../generated/runtime.json' with { type: 'json' };
import { FORMATS, resolve, variants, needsAttribution, type Crop, type Format, type PhotoEntry } from './core.mjs';

export { CROPS, FORMATS, WIDTHS, SITES, problems, processed, needsAttribution, type Crop, type Format, type PhotoEntry, type Site } from './core.mjs';

const photos = (manifest as { photos: Record<string, PhotoEntry | undefined> }).photos;
const fallback = runtime.fallback as Record<string, string | undefined>;
type Root = 'service' | 'shop' | 'food';
const slugs = runtime.slugs as Record<Root, Record<string, string | undefined>>;
const groups = runtime.groups as Record<Root, Record<string, string | undefined>>;

let base = '/photos/';
/**
 * Where the variants are served: the consumer site's `/photos/` (default) or a CDN (NL_PHOTOS_BASE_URL). Idempotent;
 * the server render and the browser set the same value.
 */
export function configurePhotoBase(url: string | null | undefined) {
  if (url) base = url.endsWith('/') ? url : `${url}/`;
}
export const photoBase = () => base;

export interface PhotoSource { format: Format; type: string; srcSet: string }

/** A resolved photo: what to show for a slot, ready for `<picture>` (web) or `Image` sources (native). */
export interface Photo {
  /** the slot whose photo this is (the asked one or one of its fallbacks) */
  slot: string;
  alt: { en: string; fr: string };
  width: number;
  height: number;
  /** object-position for the focal point: "50% 40%" */
  position: string;
  /** the colour shown while the photo loads */
  placeholder: string;
  /** AVIF, WebP, then JPEG `srcset`s of a crop */
  sources(crop: Crop): PhotoSource[];
  /** one URL of a crop (the JPEG nearest to `width`): the `<img src>` fallback, social cards */
  src(crop: Crop, width?: number): string;
  /** every variant of a crop as [width, height, url] (the native apps pick by size) */
  sizes(crop: Crop, format?: Format): [number, number, string][];
  credit: Credit;
}

export interface Credit {
  slot: string;
  title?: string;
  photographer: string;
  photographerUrl?: string;
  site: PhotoEntry['site'];
  source?: string;
  sourceUrl: string;
  licence: string;
  licenceUrl?: string;
  /** the licence asks for the name next to the work (CC BY): the credits page gives every name anyway */
  attribution: boolean;
}

const TYPES: Record<Format, string> = { avif: 'image/avif', webp: 'image/webp', jpg: 'image/jpeg' };

function credit(slot: string, e: PhotoEntry): Credit {
  return {
    slot, title: e.title, photographer: e.photographer, photographerUrl: e.photographerUrl, site: e.site, source: e.source,
    sourceUrl: e.sourceUrl, licence: e.licence, licenceUrl: e.licenceUrl, attribution: needsAttribution(e.licence),
  };
}

function photo(slot: string, e: PhotoEntry & { width: number; height: number; hash: string; placeholder?: string }): Photo {
  const all = variants(slot, e);
  const url = (path: string) => base + path;
  const of = (crop: Crop, format: Format) => all.filter(v => v.crop === crop && v.format === format);
  return {
    slot,
    alt: e.alt,
    width: e.width,
    height: e.height,
    position: `${Math.round(e.focal.x * 100)}% ${Math.round(e.focal.y * 100)}%`,
    placeholder: e.placeholder ?? 'transparent',
    sources: crop => FORMATS.map(format => ({ format, type: TYPES[format], srcSet: of(crop, format).map(v => `${url(v.path)} ${v.width}w`).join(', ') })),
    src: (crop, width = 640) => {
      const list = of(crop, 'jpg');
      const best = list.find(v => v.width >= width) ?? list[list.length - 1]!;
      return url(best.path);
    },
    sizes: (crop, format = 'webp') => of(crop, format).map(v => [v.width, v.height, url(v.path)]),
    credit: credit(slot, e),
  };
}

/** The photo of the first slot (or its fallbacks) that has one; null when none has (draw the illustration). */
export function photoFor(slots: string | readonly (string | null | undefined)[]): Photo | null {
  const list = (typeof slots === 'string' ? [slots] : slots).filter((s): s is string => !!s);
  const hit = resolve(list, photos, fallback);
  return hit ? photo(hit.slot, hit.entry) : null;
}

/** The slot of a catalogue category or group id (`service.home-trades.plumber`, `shop.apparel`). */
export const categorySlot = (id: string | null | undefined) => (id ? `category/${id}` : null);

/** A category's slot from its slug (`plumber`, `bakery`), as the landing pages name them; groups too. */
export function slugSlot(root: Root, slug: string | null | undefined): string | null {
  if (!slug) return null;
  const id = slugs[root][slug] ?? groups[root][slug];
  return id ? `category/${id}` : null;
}

/** A shop department (a shop category leaf, `/shop/<slug>`). */
export const departmentSlot = (slug: string | null | undefined) => slugSlot('shop', slug);
/** A service category (`/services/<slug>`). */
export const serviceSlot = (slug: string | null | undefined) => slugSlot('service', slug);
/** A cuisine code (`vietnamese`, `meal_kits`). */
export const cuisineSlot = (code: string | null | undefined) => (code ? `cuisine/${code}` : null);

/** The banner of a half of the market: the last photo every category of it falls back to. */
export const rootSlot = (root: Root) => ({ service: 'banner/services', shop: 'banner/shop', food: 'banner/food' })[root];

/** Every photo in the library with its credit, by slot (the credits page). */
export function credits(): Credit[] {
  return Object.entries(photos)
    .filter((x): x is [string, PhotoEntry & { hash: string; width: number; height: number }] => !!x[1]?.hash)
    .map(([slot, e]) => credit(slot, e))
    .sort((a, b) => a.slot.localeCompare(b.slot));
}
