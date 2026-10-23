import { readFileSync } from 'node:fs';
import type { Page } from '@playwright/test';

/**
 * The photos of `make ui-shots` (S-182): the photo library's own files (web/packages/media: photos.json + source/), the
 * very bytes `make demo-data` uploads for the demo businesses and listings, so the screenshots show what the demo shows.
 * A listing points at `/api/v1/public/catalogue/media/<slot>` (shots/fixtures.mjs `media(slot)`, the slot with `~` for
 * `/`); the route answers that slot's source photo, same-origin (no CSP change, nothing fetched from the internet).
 * A slot the library has no photo for answers 404, and so do dish photos (the demo uploads none): the page then shows
 * its designed fallback, as the demo does. The stock photos (heroes, banners, category tiles) are the consumer build's
 * own `/photos/` variants (`make photos`), already the real ones.
 */
const MEDIA_ROOT = new URL('../../media/', import.meta.url);
const library = JSON.parse(readFileSync(new URL('photos.json', MEDIA_ROOT), 'utf8')) as { photos: Record<string, { file: string } | undefined> };
const MEDIA = '/api/v1/public/catalogue/media/';
const MENU_PHOTO = /^\/api\/v1\/merchants\/[^/]+\/menu-items\/[^/]+\/photo$/;
const TYPES: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' };

/** The library slot behind a fixture's media id (`demo~listing~bakery.sourdough` → `demo/listing/bakery.sourdough`). */
export const slotOf = (mediaId: string) => decodeURIComponent(mediaId).replaceAll('~', '/');

/** The source photo of a library slot: its bytes and type, or null (the slot has none). */
export function sourceOf(slot: string): { body: Buffer; contentType: string } | null {
  const entry = library.photos[slot];
  if (!entry?.file) return null;
  const ext = entry.file.split('.').pop()!.toLowerCase();
  return { body: readFileSync(new URL(entry.file, MEDIA_ROOT)), contentType: TYPES[ext] ?? 'application/octet-stream' };
}

/** Answers the catalogue's media and the menu items' photos in the browser, from the library. */
export async function libraryPhotos(page: Page) {
  await page.route(url => url.pathname.startsWith(MEDIA) || MENU_PHOTO.test(url.pathname), route => {
    const path = new URL(route.request().url()).pathname;
    const photo = path.startsWith(MEDIA) ? sourceOf(slotOf(path.slice(MEDIA.length))) : null;
    return photo ? route.fulfill({ status: 200, ...photo }) : route.fulfill({ status: 404, body: '' });
  });
}
