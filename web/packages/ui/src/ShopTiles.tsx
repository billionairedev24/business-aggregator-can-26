import type { ReactNode } from 'react';
import clsx from 'clsx';
import { SiteLink } from './SiteLink';
import type { TagTone } from './Tag';

/**
 * Consumer Shop tiles (design 06 `shop`, `category`, `search`): a product card, a shop card and a department tile. Each
 * is one link (crawlable, opens in a new tab). Colours come from tokens only: a shop without a logo gets one of six
 * token swatches, picked from its id so it stays the same everywhere.
 */

export interface TileTag { label: ReactNode; tone?: TagTone }

const TagLabel = ({ tag }: { tag?: TileTag }) => (tag ? <span className={clsx('tag', `tag-${tag.tone ?? 'accent'}`, 'nl-tile-tag')}>{tag.label}</span> : null);

/** One of six token colours for `seed` (a merchant id): stable, not meaningful. */
export function swatchOf(seed: string): number {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  return (h % 6) + 1;
}

export interface ProductTileProps {
  href: string;
  name: string;
  /** Formatted price ("$7.50", "from $6.50"). */
  price: ReactNode;
  /** "Glenmore Bakery · 900 g". */
  meta?: ReactNode;
  /** An approved image; without one the tile shows the design's halftone placeholder. */
  imageUrl?: string | null;
  tag?: TileTag;
}

export function ProductTile({ href, name, price, meta, imageUrl, tag }: ProductTileProps) {
  return (
    <SiteLink href={href} className="nl-product-tile">
      <span className="nl-product-tile-media halftone" aria-hidden>
        {imageUrl ? <img src={imageUrl} alt="" loading="lazy" decoding="async" /> : null}
        {tag ? <TagLabel tag={tag} /> : null}
      </span>
      <span className="nl-product-tile-row"><span className="nl-product-tile-name">{name}</span><span className="nl-product-tile-price">{price}</span></span>
      {meta ? <span className="nl-product-tile-meta">{meta}</span> : null}
    </SiteLink>
  );
}

export interface ShopTileProps {
  href: string;
  name: string;
  /** Seed of the swatch colour (the merchant id). */
  id: string;
  meta?: ReactNode;
  /** The tier tag next to the name ("Master"). */
  tier?: TileTag;
  /** Delivery tag ("Order by 5:19 pm", "Tomorrow"). */
  tag?: TileTag;
  /** `initial`: a square with the first letter (department page); `dot`: a round swatch (landing). */
  look?: 'initial' | 'dot';
}

export function ShopTile({ href, name, id, meta, tier, tag, look = 'initial' }: ShopTileProps) {
  const swatch = `nl-swatch-${swatchOf(id)}`;
  return (
    <SiteLink href={href} className={clsx('nl-shop-tile', `nl-shop-tile-${look}`)}>
      {look === 'initial'
        ? <span className={clsx('nl-shop-tile-mark', swatch)} aria-hidden>{name.trim().charAt(0).toUpperCase()}</span>
        : <span className={clsx('nl-shop-tile-dot halftone', swatch)} aria-hidden />}
      <span className="nl-shop-tile-body">
        <span className="nl-shop-tile-title"><strong>{name}</strong>{look === 'initial' ? <TagLabel tag={tier} /> : null}</span>
        {meta ? <span className="nl-shop-tile-meta">{meta}</span> : null}
        {look === 'initial' && tag ? <span className="nl-shop-tile-run"><TagLabel tag={tag} /></span> : null}
      </span>
      {look === 'dot' ? <TagLabel tag={tag} /> : null}
    </SiteLink>
  );
}

export interface DepartmentTileProps { href: string; name: string; count: ReactNode }

export const DepartmentTile = ({ href, name, count }: DepartmentTileProps) => (
  <SiteLink href={href} className="nl-dept-tile"><span className="nl-dept-tile-name">{name}</span><span className="nl-dept-tile-count">{count}</span></SiteLink>
);

/** Responsive grid of tiles: `min` is the smallest column (design: 130 departments, 200 products, 240 shops). */
export const TileGrid = ({ min, children, label }: { min: 130 | 200 | 240; children: ReactNode; label?: string }) => (
  <ul className={clsx('nl-tile-grid', `nl-tile-grid-${min}`)} aria-label={label}>{children}</ul>
);
