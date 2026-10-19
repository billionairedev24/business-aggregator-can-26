import { FALLBACK, GROUPS, LIBRARY, SLUGS, type Crop, type LibraryPhoto } from './library.generated';

export type { Crop, LibraryPhoto } from './library.generated';

/**
 * The photo library in the apps (real photos, 2026-10-19; docs/runbooks/photos.md): which stock photo a slot shows —
 * its own, else its fallback's (a category → its group → the landing's banner) — as image sources the apps load from
 * the photos' base URL (the consumer site's /photos/, or the CDN of EXPO_PUBLIC_PHOTOS_BASE_URL). None → null: the
 * screen draws its illustration (`Art`).
 */
let base = 'http://localhost:3000/photos/';

/** Where the variants are served; each app sets it at start from its configuration. */
export function configurePhotoBase(url: string | null | undefined) {
  if (url) base = url.endsWith('/') ? url : `${url}/`;
}
export const photoBase = () => base;

export interface ResolvedPhoto {
  slot: string;
  alt: { en: string; fr: string };
  placeholder: string | null;
  /** every width of the crop: React Native's Image picks the one that fits the view */
  sources: { uri: string; width: number; height: number }[];
}

/** The first slot (or its fallbacks) with a photo; null when none has one. */
export function photoFor(slots: string | readonly (string | null | undefined)[], crop: Crop = 'card'): ResolvedPhoto | null {
  const list = (typeof slots === 'string' ? [slots] : slots).filter((s): s is string => !!s);
  for (const start of list) {
    const seen = new Set<string>();
    for (let s: string | undefined = start; s && !seen.has(s); s = FALLBACK[s]) {
      seen.add(s);
      const p: LibraryPhoto | undefined = LIBRARY[s];
      if (p) return { slot: s, alt: p.alt, placeholder: p.placeholder, sources: p.sizes[crop].map(([width, height, path]) => ({ uri: base + path, width, height })) };
    }
  }
  return null;
}

type Root = 'service' | 'shop' | 'food';
/** The slot of a catalogue category id (`service.home-trades.plumber`). */
export const categorySlot = (id: string | null | undefined) => (id ? `category/${id}` : null);
/** A category's (or group's) slot from its slug, as the screens name them. */
export function slugSlot(root: Root, slug: string | null | undefined): string | null {
  const id = slug ? SLUGS[root][slug] ?? GROUPS[root][slug] : undefined;
  return id ? `category/${id}` : null;
}
export const cuisineSlot = (code: string | null | undefined) => (code ? `cuisine/${code}` : null);
