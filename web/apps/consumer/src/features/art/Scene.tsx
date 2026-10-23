import type { CSSProperties } from 'react';
import clsx from 'clsx';
import { Photo, type Crop } from '@northline/ui';
import { photoFor, type Photo as PhotoData } from '@northline/media';
import { PhotoWell } from './PhotoWell';
import type { Subject, Tone } from './subjects';

export interface SceneProps {
  /**
   * The photo slot(s) (`@northline/media`: `hero/home-main`, `categorySlot(id)`, `departmentSlot(slug)`…), first that
   * has a photo wins, each with its own fallbacks (leaf → group → banner).
   */
  slot: string | readonly (string | null | undefined)[];
  /** An already resolved photo (tests); wins over `slot`. */
  photo?: PhotoData | null;
  /** Whose icon the designed fallback shows while no slot has a photo (a slug, code or name). */
  subject: string;
  /** No longer drawn (S-181 retired the illustrated scenes); kept so the callers' colour hints still type-check. */
  tone?: Tone;
  /** The fallback's icon instead of the subject's. */
  draw?: Subject;
  /** contain: a wide banner (16:9 photo). cover (default): tiles and cards. */
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
 * A picture of a place in the market: the stock photo of the slot from the photo library (`srcset` of its crop, cut
 * around its focal point), and only when no slot has one, the designed fallback — the dashed photo well with the
 * subject's icon (S-181). Never upscaled: a photo whose crop is narrower than the box shows at its own width, centred
 * on the well, instead of stretched (a banner then reads as a framed picture, not a blurry one). docs/runbooks/photos.md.
 */
export function Scene({ slot, photo, subject, draw, fit = 'cover', crop, sizes, priority, alt, className }: SceneProps) {
  const p = photo !== undefined ? photo : photoFor(slot);
  const c = crop ?? (fit === 'contain' ? 'wide' : 'card');
  if (!p) return <PhotoWell subject={subject} draw={draw} className={className} />;
  const native = p.sizes(c, 'jpg').at(-1)?.[0];
  // the widest variant there is: the box may grow past it, the picture does not (a data value, not a style choice)
  const style = native ? ({ '--nl-scene-max': `${native}px` } as CSSProperties) : undefined;
  return (
    <span className={clsx('nl-scene', className)} style={style}>
      <Photo photo={p} crop={c} sizes={sizes} priority={priority} alt={alt} decorative={alt === undefined} />
    </span>
  );
}
