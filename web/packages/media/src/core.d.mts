export type Crop = 'square' | 'card' | 'wide';
export type Format = 'avif' | 'webp' | 'jpg';
export type Site = 'pexels' | 'unsplash' | 'openverse' | 'openimages';

/** One photo of the library (photos.json): the source file, what it shows, where it came from and its licence. */
export interface PhotoEntry {
  file: string;
  alt: { en: string; fr: string };
  /** The point the crops keep in view, 0–1 from the left and the top. */
  focal: { x: number; y: number };
  site: Site;
  photographer: string;
  photographerUrl?: string;
  sourceUrl: string;
  /** 'Pexels License', 'Unsplash License', 'CC0 1.0', 'PDM 1.0', 'CC BY 4.0', … (core.mjs SITES). */
  licence: string;
  /** openverse, openimages: the Creative Commons deed. */
  licenceUrl?: string;
  /** openverse, openimages: the site it was published on (flickr, wikimedia, stocksnap, rawpixel…). */
  source?: string;
  /** the photo's title on its site (openverse attribution). */
  title?: string;
  downloaded: string;
  note?: string;
  // written by `make photos`
  width?: number;
  height?: number;
  hash?: string;
  placeholder?: string;
}

export interface Variant { crop: Crop; width: number; height: number; format: Format; path: string }

export const CROPS: Readonly<Record<Crop, readonly [number, number]>>;
export const WIDTHS: readonly number[];
export const FORMATS: readonly Format[];
export const SITES: Readonly<Record<Site, { licences: string[]; hosts: string[] | null }>>;
export function needsAttribution(licence: string | undefined): boolean;
export const PIPELINE: number;
export function isSlot(slot: string): boolean;
export function problems(slot: string, entry: unknown, options?: { processed?: boolean }): string[];
export function processed(entry: PhotoEntry | undefined | null): entry is PhotoEntry & { width: number; height: number; hash: string };
export function cropBox(width: number, height: number, crop: Crop, focal?: { x: number; y: number }): { left: number; top: number; width: number; height: number };
export function widthsFor(entry: PhotoEntry & { width: number; height: number }, crop: Crop): number[];
export function variantPath(slot: string, crop: Crop, width: number, hash: string, format: Format): string;
export function variants(slot: string, entry: PhotoEntry): Variant[];
export function resolve(
  slots: readonly string[],
  photos: Readonly<Record<string, PhotoEntry | undefined>>,
  fallback: Readonly<Record<string, string | undefined>>,
): { slot: string; entry: PhotoEntry & { width: number; height: number; hash: string; placeholder?: string } } | null;
