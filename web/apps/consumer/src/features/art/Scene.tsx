import { Photo, type Crop } from '@northline/ui';
import type { Photo as PhotoData } from '@northline/media';
import { Art, type Tone } from './Art';
import type { Subject } from './subjects';

export interface SceneProps {
  /**
   * The photo slot(s) (`@northline/media`: `hero/home-main`, `categorySlot(id)`, `departmentSlot(slug)`…), first that
   * has a photo wins, each with its own fallbacks (leaf → group → banner).
   */
  slot: string | readonly (string | null | undefined)[];
  /** An already resolved photo (tests); wins over `slot`. */
  photo?: PhotoData | null;
  /** The drawn picture while no slot has a photo yet (Art's subject: a slug, code or name). */
  subject: string;
  tone?: Tone;
  draw?: Subject;
  /** contain: a wide banner (16:9 photo; the drawing keeps its size). cover (default): tiles and cards. */
  fit?: 'cover' | 'contain';
  /** The photo's crop; by default 16:9 for banners, 4:3 otherwise. */
  crop?: Crop;
  /** How wide it shows (the `sizes` attribute). */
  sizes?: string;
  /** Above the fold: load at once. */
  priority?: boolean;
  /** Alternative text; omitted the picture is decorative (the heading beside it names the thing). */
  alt?: string;
  className?: string;
}

/**
 * A picture of a place in the market (owner, 2026-10-19: "use real pictures"): the stock photo of the slot from the
 * photo library, and only while the library has none for it, the drawn still life (`Art`). docs/runbooks/photos.md.
 */
export function Scene({ slot, photo, subject, tone, draw, fit = 'cover', crop, sizes, priority, alt, className }: SceneProps) {
  return (
    <Photo
      slot={slot} photo={photo} crop={crop ?? (fit === 'contain' ? 'wide' : 'card')} sizes={sizes} priority={priority}
      alt={alt} decorative={alt === undefined} className={className}
      fallback={<Art subject={subject} tone={tone} draw={draw} fit={fit} className={className} />}
    />
  );
}
