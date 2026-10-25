import { useId, type ReactNode } from 'react';
import clsx from 'clsx';
import { CaretRight, Storefront } from '@phosphor-icons/react';
import { MediaFallback } from './Commerce';
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
  /** An approved image (the merchant's upload); without one, `fallback` (the category's library photo), else the initial. */
  imageUrl?: string | null;
  fallback?: ReactNode;
  tag?: TileTag;
}

export function ProductTile({ href, name, price, meta, imageUrl, fallback, tag }: ProductTileProps) {
  return (
    <SiteLink href={href} className="nl-product-tile">
      <span className="nl-product-tile-media" aria-hidden>
        {imageUrl ? <img src={imageUrl} alt="" loading="lazy" decoding="async" /> : fallback ?? <MediaFallback name={name} />}
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
  /** The shop's photo (one of its own listing photos): shown in the mark's place instead of the letter. */
  imageUrl?: string | null;
}

export function ShopTile({ href, name, id, meta, tier, tag, look = 'initial', imageUrl }: ShopTileProps) {
  const swatch = `nl-swatch-${swatchOf(id)}`;
  // one layout for both looks: the mark | the name (two lines at most), its meta, then the tier and delivery tags
  // under it — the tags never take the name's width (2026-10-19: names wrapped one letter a line next to them)
  return (
    <SiteLink href={href} className={clsx('nl-shop-tile', `nl-shop-tile-${look}`)}>
      <span className={clsx('nl-shop-tile-mark', look === 'dot' && 'nl-shop-tile-round', imageUrl ? 'nl-shop-tile-photo' : swatch)} aria-hidden>
        {imageUrl ? <img src={imageUrl} alt="" loading="lazy" decoding="async" /> : name.trim().charAt(0).toUpperCase() || <Storefront size={22} />}
      </span>
      <span className="nl-shop-tile-body">
        <span className="nl-shop-tile-title">{name}</span>
        {meta ? <span className="nl-shop-tile-meta">{meta}</span> : null}
        {tier || tag ? <span className="nl-shop-tile-tags"><TagLabel tag={tier} /><TagLabel tag={tag} /></span> : null}
      </span>
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

export interface PanelTileProps {
  title: ReactNode;
  /** The link at the panel's foot ("See all 14 bakeries"). */
  more?: { href: string; label: ReactNode };
  /** The panel's heading level (default 2: a home tile is a section of the page). */
  level?: 2 | 3;
  className?: string;
  children: ReactNode;
}

/**
 * A home tile (E-15, design/revamp/a.html "Fresh from local bakeries", "Deal of the day"): a white panel with a hairline
 * edge and 4 px corners, its bold title, the body (a <PictureGrid>, a deal) and "See all …" in the action colour at the
 * foot. Four sit in a row on the home (<PanelTiles>), two on phones.
 */
export function PanelTile({ title, more, level = 2, className, children }: PanelTileProps) {
  const id = useId();
  const H = `h${level}` as 'h2';
  return (
    <section className={clsx('nl-panel-tile', className)} aria-labelledby={id}>
      <H id={id} className="nl-panel-tile-title">{title}</H>
      <div className="nl-panel-tile-body">{children}</div>
      {more ? <SiteLink href={more.href} className="nl-panel-tile-more">{more.label}<CaretRight size={14} weight="bold" aria-hidden /></SiteLink> : null}
    </section>
  );
}

/** The row of home tiles: four a row, two on phones. */
export const PanelTiles = ({ children, className }: { children: ReactNode; className?: string }) => <div className={clsx('nl-panel-tiles', className)}>{children}</div>;

export interface PictureGridItem { key: string; href: string; label: string; picture: ReactNode; lang?: string }
/**
 * Four (or two) square pictures with a caption each, two a row: the inside of a home tile. Each is a link; the picture
 * is decorative (the caption names it). Square boxes are reserved, so nothing moves when a photo arrives.
 */
export function PictureGrid({ items }: { items: readonly PictureGridItem[] }) {
  return (
    <ul className="nl-picture-grid">
      {items.map(i => (
        <li key={i.key}>
          <SiteLink href={i.href} className="nl-picture-grid-link">
            <span className="nl-picture-grid-pic" aria-hidden>{i.picture}</span>
            <span className="nl-picture-grid-label" lang={i.lang}>{i.label}</span>
          </SiteLink>
        </li>
      ))}
    </ul>
  );
}
