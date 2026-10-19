// Real photos for the demo (docs/runbooks/photos.md): a business's or listing's `photo` slot of the plan, from the
// photo library (web/packages/media: photos.json + source/), uploaded through the product's own upload flow like any
// merchant's photo. A slot without a photo yet gives null and the run draws a picture as before (lib/images.mjs).
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const MEDIA = fileURLToPath(new URL('../../../web/packages/media/', import.meta.url));
const TYPES = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp' };

/** The library's photo of `slot` as an upload ({ bytes, name, type }), or null when it has none (yet). */
export function libraryPhoto(slot, root = MEDIA) {
  if (!slot) return null;
  const manifest = JSON.parse(readFileSync(join(root, 'photos.json'), 'utf8'));
  const entry = manifest.photos?.[slot];
  if (!entry?.file || !entry.hash) return null; // only what make photos processed (licence and credit checked)
  const path = join(root, entry.file);
  if (!existsSync(path)) return null;
  const type = TYPES[extname(path).toLowerCase()];
  if (!type || type === 'image/webp') return null; // the api takes JPEG and PNG
  return { bytes: readFileSync(path), name: `${slot.split('/').pop()}${extname(path).toLowerCase()}`, type };
}

/**
 * A business's photo as its storefront logo: the api takes PNG (or SVG) logos, so the photo is cut square and
 * converted with the library's sharp (web/ installed); null without sharp (the run draws a logo then).
 */
export async function logoFromPhoto(photo) {
  try {
    const require = createRequire(join(MEDIA, 'package.json'));
    const sharp = require('sharp');
    const bytes = await sharp(photo.bytes).rotate().resize(512, 512, { fit: 'cover' }).png().toBuffer();
    return { bytes, name: 'logo.png', type: 'image/png' };
  } catch {
    return null;
  }
}

/** As a FormData-ready Blob. */
export const photoBlob = photo => new Blob([photo.bytes], { type: photo.type });
