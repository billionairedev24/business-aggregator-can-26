import type { ReactNode } from 'react';
import clsx from 'clsx';
import { Minus, Package, Plus, Trash, type Icon } from '@phosphor-icons/react';
import { formatNumber, formatPrice, formatUnitPrice, useLocale, type PriceUnit } from './i18n';
import { useCommerceT } from './commerceText';

/**
 * The commerce kit in the locked E-15 direction, A · Plainsight (design/revamp/a.html "Design system at a glance"):
 * Price (the one format, $8.50 / 8,50 $, with the unit price as meta), Stars (ink, with the value and the count),
 * DealBadge (the commerce colour), StatusTag (a semantic colour pair), QuantityStepper, MediaFallback (the dashed well
 * with the category icon); ProductCard and ServiceCard are in Cards.tsx (S-176: their own chunk). Semantic tokens only;
 * every target ≥ 44px; every string in en and fr-CA.
 */
const useT = useCommerceT;

// ── Price ───────────────────────────────────────────────────────────────────────────────────────────────────────

export interface PriceProps {
  /** CAD cents. */
  cents: number;
  /** A higher earlier price, struck through ("Was $9.00"). */
  compareAtCents?: number | null;
  /** The unit price as meta after the figure: per-unit cents and the unit ("$0.94/100 g", "$3.75 each"). */
  unit?: { cents: number; per: PriceUnit } | null;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Before the figure: "from", "/ hr" goes in `suffix`. */
  prefix?: ReactNode;
  suffix?: ReactNode;
  className?: string;
}

/**
 * A price in the one format (E-15; the audit found three styles on the same pages): `$8.50` / `8,50 $` in tabular
 * bold, the unit price and the earlier price as 12 px meta beside it.
 */
export function Price({ cents, compareAtCents, unit, size = 'md', prefix, suffix, className }: PriceProps) {
  const t = useT();
  const { locale } = useLocale();
  const was = compareAtCents && compareAtCents > cents ? compareAtCents : null;
  return (
    <span className={clsx('nl-price', `nl-price-${size}`, className)}>
      {prefix ? <span className="nl-price-prefix">{prefix}</span> : null}
      <span className="nl-price-amount">{formatPrice(cents, locale)}</span>
      {suffix ? <span className="nl-price-suffix">{suffix}</span> : null}
      {unit ? <span className="nl-price-unit">{formatUnitPrice(unit.cents, unit.per, locale)}</span> : null}
      {was ? <s className="nl-price-was">{t('was', { price: formatPrice(was, locale) })}</s> : null}
    </span>
  );
}

/** The deal badge: the commerce colour, white, bold ("12% off"); on a photo well's corner or beside a price. */
export function DealBadge({ percent, children, className }: { percent?: number; children?: ReactNode; className?: string }) {
  const t = useT();
  return <span className={clsx('nl-deal', className)}>{children ?? t('off', { percent: percent ?? 0 })}</span>;
}

export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';
/**
 * A status (Delivered, Packed, To pack, Wrong item, Paused): the semantic colour pair of its tone with a square dot —
 * never the action or the commerce colour (one role per colour).
 */
export function StatusTag({ tone = 'neutral', size, children }: { tone?: StatusTone; size?: 'lg'; children: ReactNode }) {
  return <span className={clsx('nl-status', tone !== 'neutral' && `nl-status-${tone}`, size === 'lg' && 'nl-status-lg')}>{children}</span>;
}

// ── Stars ───────────────────────────────────────────────────────────────────────────────────────────────────────

const STAR = 'M12 2.5l2.94 6.24 6.81.79-5.04 4.66 1.35 6.73L12 17.52 5.94 20.92l1.35-6.73L2.25 9.53l6.81-.79z';

function Star({ fill, size }: { fill: number; size: number }) {
  return (
    <span className="nl-star">
      <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden focusable="false" className="nl-star-track"><path fill="currentColor" d={STAR} /></svg>
      <span className="nl-star-fill" style={{ ['--pct' as string]: `${fill * 100}%` }}>
        <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden focusable="false"><path fill="currentColor" d={STAR} /></svg>
      </span>
    </span>
  );
}

export interface StarsProps {
  rating: number;
  /** Shown after the stars in the link colour; 0 with `showNew` reads "New". */
  reviewCount?: number | null;
  /** The value before the stars ("4.8"); on by default, as the mockups show it. */
  showValue?: boolean;
  size?: number;
  className?: string;
}

/** Read-only rating: the value, five ink stars filled to the nearest half, the count; announced as "4.5 out of 5 stars". */
export function Stars({ rating, reviewCount, showValue = true, size = 13, className }: StarsProps) {
  const t = useT();
  const { locale } = useLocale();
  const r = Math.max(0, Math.min(5, rating));
  const one = formatNumber(r, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return (
    <span className={clsx('nl-stars', className)}>
      {showValue ? <span className="nl-stars-value" aria-hidden>{one}</span> : null}
      <span className="nl-stars-row" role="img" aria-label={t('stars', { rating: one })}>
        {[0, 1, 2, 3, 4].map(i => <Star key={i} size={size} fill={Math.max(0, Math.min(1, Math.round((r - i) * 2) / 2))} />)}
      </span>
      {reviewCount != null ? <span className="nl-stars-count"><span aria-hidden>({formatNumber(reviewCount, locale)})</span><span className="nl-sr-only">{t('reviews', { count: reviewCount })}</span></span> : null}
    </span>
  );
}

/**
 * The results rail's "Customer rating" filter (the mockups' "★★★★☆ & up" rows): one toggle a row, the chosen one on
 * the selected tint with an ink edge; pressing it again clears it. Named "4 stars & up".
 */
export function RatingFilter({ value, options = [4, 3], onChange }: { value: number | undefined; options?: readonly number[]; onChange: (rating: number | undefined) => void }) {
  const t = useT();
  return (
    <div className="nl-rate-filter">
      {options.map(r => (
        <button key={r} type="button" className="nl-rate-opt" aria-pressed={value === r} aria-label={t('ratingUp', { rating: r })} onClick={() => onChange(value === r ? undefined : r)}>
          <span className="nl-stars-row" aria-hidden>{[0, 1, 2, 3, 4].map(i => <Star key={i} size={13} fill={i < r ? 1 : 0} />)}</span>
          <span aria-hidden>{t('andUp')}</span>
        </button>
      ))}
    </div>
  );
}

// ── QuantityStepper ─────────────────────────────────────────────────────────────────────────────────────────────

export interface QuantityStepperProps {
  value: number;
  /** Highest allowed (the stock, capped at 99). */
  max: number;
  onChange: (qty: number) => void;
  /** The item's name, for the buttons' names ("One more Country sourdough"). */
  name: string;
  busy?: boolean;
  /** "2 in cart" (cards) instead of the bare number (cart lines, product page). */
  inCart?: boolean;
  /** 0 is allowed (the minus becomes a bin at 1); off on the product page, where 1 is the floor. */
  removable?: boolean;
  block?: boolean;
  className?: string;
}

/** − / + with a 2px primary border (AT's stepper): replaces "Add to cart" once the item is in the cart. */
export function QuantityStepper({ value, max, onChange, name, busy, inCart, removable = true, block, className }: QuantityStepperProps) {
  const t = useT();
  const removing = removable && value <= 1;
  return (
    <span className={clsx('nl-stepper', block && 'nl-stepper-block', className)} role="group" aria-label={t('qtyOf', { name })} aria-busy={busy || undefined}>
      <button type="button" className="nl-stepper-btn" disabled={busy || (!removable && value <= 1)} aria-label={removing ? t('remove', { name }) : t('less', { name })}
        onClick={() => onChange(value - 1)}>
        {removing ? <Trash size={16} aria-hidden /> : <Minus size={16} aria-hidden />}
      </button>
      <span className="nl-stepper-value" aria-live="polite" aria-atomic="true">{inCart ? t('inCart', { count: value }) : value}</span>
      <button type="button" className="nl-stepper-btn" disabled={busy || value >= max} aria-label={t('more', { name })} onClick={() => onChange(value + 1)}>
        <Plus size={16} aria-hidden />
      </button>
    </span>
  );
}

// ── MediaFallback ───────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What a card shows where its photo would be when it has none — never a blank box: a dashed well with the category's
 * icon and, when there is room, the seller's or item's name as a quiet caption. Decorative (the card's title names it).
 */
export function MediaFallback({ name, caption, icon: I = Package, className }: { name: string; caption?: ReactNode; icon?: Icon; className?: string }) {
  return (
    <span className={clsx('nl-media-fallback', className)} aria-hidden data-name={name}>
      <I size={34} weight="light" className="nl-media-fallback-icon" />
      {caption ? <span className="nl-media-fallback-caption">{caption}</span> : null}
    </span>
  );
}
