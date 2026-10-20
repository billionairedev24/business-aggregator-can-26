import type { CSSProperties, ReactNode } from 'react';
import clsx from 'clsx';
import { photoFor, type Crop, type Photo as PhotoData } from '@northline/media';
import { useLocale } from './i18n';

export type { Crop } from '@northline/media';

export interface PhotoProps {
  /**
   * The slot (or slots, first that has a photo wins) of the photo library (`hero/home-main`,
   * `category/service.home-trades.plumber`); each slot's fallbacks are tried too. docs/runbooks/photos.md.
   */
  slot?: string | readonly (string | null | undefined)[];
  /** An already resolved photo (stories, tests) — wins over `slot`. */
  photo?: PhotoData | null;
  /** The crop: square tiles, 4:3 cards (default), 16:9 banners and heroes. */
  crop?: Crop;
  /** The `sizes` attribute: how wide the picture shows, so the browser downloads the right width. */
  sizes?: string;
  /**
   * Alternative text. Omitted: the photo's own description (photos.json, in the page's language). Pass `''` (or
   * `decorative`) when the text beside it already says what it is.
   */
  alt?: string;
  decorative?: boolean;
  /** Above the fold (a hero): loaded at once, with high priority. Everything else waits until it nears the screen. */
  priority?: boolean;
  /** Keep the crop's shape (aspect-ratio box) instead of filling the parent. */
  ratio?: boolean;
  /** What shows while the slot has no photo yet: the drawn picture. Nothing when omitted. */
  fallback?: ReactNode;
  className?: string;
}

const RATIO: Record<Crop, string> = { square: '1 / 1', card: '4 / 3', wide: '16 / 9' };

/**
 * A stock photo from the library (`@northline/media`): a `<picture>` with AVIF, WebP and JPEG `srcset`s of the crop,
 * cut around the photo's focal point, lazy unless `priority`, on its placeholder colour while it loads. Fills its
 * parent (object-fit cover) or keeps its crop's shape (`ratio`). A slot without a photo shows `fallback`.
 */
export function Photo({ slot, photo, crop = 'card', sizes = '100vw', alt, decorative, priority, ratio, fallback = null, className }: PhotoProps) {
  const { locale } = useLocale();
  const p = photo !== undefined ? photo : slot ? photoFor(slot) : null;
  if (!p) return <>{fallback}</>;
  const text = decorative ? '' : alt ?? p.alt[locale];
  const style = { '--nl-photo-bg': p.placeholder, ...(ratio ? { '--nl-photo-ratio': RATIO[crop] } : {}) } as CSSProperties;
  const [avif, webp, jpg] = p.sources(crop);
  return (
    <span className={clsx('nl-photo', ratio && 'nl-photo-ratio', className)} style={style} data-slot={p.slot} aria-hidden={text === '' ? true : undefined}>
      <picture>
        {avif ? <source type={avif.type} srcSet={avif.srcSet} sizes={sizes} /> : null}
        {webp ? <source type={webp.type} srcSet={webp.srcSet} sizes={sizes} /> : null}
        <img
          src={p.src(crop)} srcSet={jpg?.srcSet} sizes={sizes} alt={text}
          loading={priority ? 'eager' : 'lazy'} decoding={priority ? 'sync' : 'async'} {...(priority ? { fetchPriority: 'high' as const } : {})}
          style={{ objectPosition: p.position }}
        />
      </picture>
    </span>
  );
}

/** Whether any of the slots has a photo (a page that lays out differently with one, e.g. a banner's text over it). */
export const hasPhoto = (slot: string | readonly (string | null | undefined)[]) => photoFor(slot) !== null;
