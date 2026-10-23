import type { ReactNode } from 'react';
import clsx from 'clsx';
import { Minus, Package, Plus, SealCheck, Trash, Truck, Warning, Wrench, type Icon } from '@phosphor-icons/react';
import { defineMessages, formatNumber, formatPrice, formatUnitPrice, useLocale, type PriceUnit } from './i18n';
import { SiteLink } from './SiteLink';
import type { TagTone } from './Tag';

/**
 * The commerce kit in the locked E-15 direction, A · Plainsight (design/revamp/a.html "Design system at a glance"):
 * Price (the one format, $8.50 / 8,50 $, with the unit price as meta), Stars (ink, with the value and the count),
 * DealBadge (the commerce colour), StatusTag (a semantic colour pair), QuantityStepper, MediaFallback (the dashed well
 * with the category icon), ProductCard (product on white in a 1:1 grey well) and ServiceCard. Semantic tokens only;
 * every target ≥ 44px; every string in en and fr-CA.
 */
const useT = defineMessages({
  en: {
    stars: '{rating} out of 5 stars',
    reviews: '{count, plural, one {# review} other {# reviews}}',
    noReviews: 'New',
    from: 'from',
    addToCart: 'Add to cart', addNamed: 'Add to cart: {name}', adding: 'Adding…',
    inCart: '{count} in cart', qtyOf: 'Quantity of {name}', less: 'One less {name}', more: 'One more {name}', remove: 'Remove {name}',
    soldOut: 'Currently unavailable', onlyLeft: 'Only {count} left',
    verified: 'Verified',
    was: 'Was {price}',
    off: '{percent}% off',
    andUp: '& up', ratingUp: '{rating} stars & up',
  },
  fr: {
    stars: '{rating} sur 5 étoiles',
    reviews: '{count, plural, one {# avis} other {# avis}}',
    noReviews: 'Nouveau',
    from: 'à partir de',
    addToCart: 'Ajouter au panier', addNamed: 'Ajouter au panier : {name}', adding: 'Ajout…',
    inCart: '{count} au panier', qtyOf: 'Quantité de {name}', less: 'Un {name} de moins', more: 'Un {name} de plus', remove: 'Retirer {name}',
    soldOut: 'Non disponible pour le moment', onlyLeft: 'Plus que {count}',
    verified: 'Vérifié',
    was: 'Avant {price}',
    off: '{percent} % de rabais',
    andUp: 'et plus', ratingUp: '{rating} étoiles et plus',
  },
});

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
      <span className="nl-star-fill" style={{ width: `${fill * 100}%` }}>
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

// ── ProductCard ─────────────────────────────────────────────────────────────────────────────────────────────────

export interface CardBadge { label: ReactNode; tone?: TagTone }

export interface ProductCardProps {
  href: string;
  name: string;
  imageUrl?: string | null;
  /** The price in cents; `null` = no figure (`priceText` says why: "Quote"). */
  priceCents?: number | null;
  priceText?: ReactNode;
  compareAtCents?: number | null;
  /** The unit price as meta beside the price ("$0.94/100 g"). */
  unit?: PriceProps['unit'];
  /** The deal badge on the photo well's corner ("12% off"); shown automatically from `compareAtCents` when omitted. */
  deal?: ReactNode | false;
  /** "Loaf & Ladder Bakery · Inglewood". */
  meta?: ReactNode;
  rating?: number | null;
  reviewCount?: number | null;
  badges?: readonly CardBadge[];
  /** The delivery promise ("Get it <strong>tonight, 6–9 p.m.</strong>"), after a truck icon. */
  eta?: ReactNode;
  /** What the picture shows without a photo (an app's illustration); the dashed well with the icon (MediaFallback) by default. */
  fallback?: ReactNode;
  /** Shows "Only N left" in the warning colour. */
  lowStock?: number | null;
  soldOut?: boolean;
  /** `grid` (results: the cart action) or `rail` (home rows: no action, the title clamped). Both are flat on white. */
  variant?: 'grid' | 'rail';
  /** Units of this offer already in the cart: the action becomes a stepper. */
  inCart?: number;
  onAdd?: () => void;
  onQuantity?: (qty: number) => void;
  max?: number;
  busy?: boolean;
  /** The card's heading level in the page's outline (default 3: under the section's h2). */
  level?: 2 | 3 | 4;
  className?: string;
}

/**
 * A product in a results grid or a home rail (the mockups' card): product on white in a 1:1 grey well with a 12 px
 * inset (the deal badge on its corner), the title (13.5, two lines), the seller in meta, the rating, the price with its
 * unit price, the delivery promise, low stock, then a full-width "Add to cart" in the action colour that turns into
 * "− 2 in cart +". The image repeats the title's link and is hidden from assistive technology (one link per card).
 */
export function ProductCard(p: ProductCardProps) {
  const t = useT();
  const variant = p.variant ?? 'grid';
  const H = `h${p.level ?? 3}` as 'h3';
  const off = p.compareAtCents && p.priceCents != null && p.compareAtCents > p.priceCents ? Math.round((1 - p.priceCents / p.compareAtCents) * 100) : 0;
  const deal = p.deal === false ? null : p.deal ?? (off > 0 ? <DealBadge percent={off} /> : null);
  const price = p.priceCents != null
    ? <Price cents={p.priceCents} compareAtCents={p.compareAtCents} unit={p.unit} size={variant === 'rail' ? 'sm' : 'md'} />
    : p.priceText ? <span className="nl-card-price-text">{p.priceText}</span> : null;
  return (
    <article className={clsx('nl-pcard', `nl-pcard-${variant}`, p.className)} aria-label={p.name}>
      <SiteLink href={p.href} className="nl-pcard-media" tabIndex={-1} aria-hidden="true">
        <span className="nl-pcard-img">{p.imageUrl ? <img src={p.imageUrl} alt="" loading="lazy" decoding="async" /> : p.fallback ?? <MediaFallback name={p.name} />}</span>
        {deal ? <span className="nl-pcard-deal">{deal}</span> : null}
      </SiteLink>
      <div className="nl-pcard-body">
        <H className="nl-pcard-heading"><SiteLink href={p.href} className="nl-pcard-title">{p.name}</SiteLink></H>
        {p.meta ? <span className="nl-pcard-meta">{p.meta}</span> : null}
        {p.rating ? <Stars rating={p.rating} reviewCount={p.reviewCount ?? undefined} /> : null}
        {price ? <span className="nl-pcard-price">{price}</span> : null}
        {p.badges?.length ? <span className="nl-pcard-badges">{p.badges.map((b, i) => <span key={i} className={clsx('tag', `tag-${b.tone ?? 'neutral'}`)}>{b.label}</span>)}</span> : null}
        {p.eta && !p.soldOut ? <span className="nl-pcard-eta"><Truck size={15} aria-hidden className="nl-pcard-eta-icon" /><span>{p.eta}</span></span> : null}
        {p.lowStock && !p.soldOut ? <span className="nl-pcard-low"><Warning size={14} aria-hidden />{t('onlyLeft', { count: p.lowStock })}</span> : null}
        {variant === 'grid' && (p.onAdd || p.soldOut) ? (
          <div className="nl-pcard-actions">
            {p.soldOut ? <span className="nl-pcard-unavailable">{t('soldOut')}</span>
              : (p.inCart ?? 0) > 0 && p.onQuantity
                ? <QuantityStepper block inCart value={p.inCart!} max={Math.min(99, p.max ?? 99)} name={p.name} busy={p.busy} onChange={p.onQuantity} />
                : <button type="button" className="btn btn-primary btn-sm btn-block" disabled={p.busy} aria-busy={p.busy || undefined} aria-label={t('addNamed', { name: p.name })} onClick={p.onAdd}>
                  {p.busy ? t('adding') : t('addToCart')}
                </button>}
          </div>
        ) : null}
      </div>
    </article>
  );
}

// ── ServiceCard ─────────────────────────────────────────────────────────────────────────────────────────────────

export interface ServiceCardProps {
  /** A link to the provider or the service, or (with `onSelect`) a choice in a list (radio-like, `selected`). */
  href?: string;
  onSelect?: () => void;
  selected?: boolean;
  name: string;
  /** The xs uppercase kicker: the kind of service ("Mobile mechanic"). */
  kind?: ReactNode;
  /** A thumbnail; without one, `mark` (a BrandMark) or the initial on the sunken surface. */
  imageUrl?: string | null;
  mark?: ReactNode;
  rating?: number | null;
  reviewCount?: number | null;
  /** "City · 2.4 km". */
  place?: ReactNode;
  /** One muted line under the place (the next opening, a blurb). */
  note?: ReactNode;
  /** The price on the right: cents (shown as "from $X" unless `fromPrice` is off) or text ("Quote"). */
  priceCents?: number | null;
  priceText?: ReactNode;
  priceSuffix?: ReactNode;
  fromPrice?: boolean;
  verified?: boolean;
  /** `row` (96px | 1fr | auto) or `stack` (narrow grids: thumbnail on top). */
  layout?: 'row' | 'stack';
  level?: 2 | 3 | 4;
  className?: string;
}

/**
 * A provider's or a service's card (AT Shop:891): 96px 1:1 thumbnail | kicker, name (lg bold), stars, "City ·
 * distance" | "from" over the price; 16px padding, md radius, 2px border (the primary colour when selected). A link
 * card, or a pressable choice with `onSelect`.
 */
export function ServiceCard(p: ServiceCardProps) {
  const t = useT();
  // a heading can't sit inside a button: the pressable choice names itself with a span
  const H = p.onSelect ? 'span' : `h${p.level ?? 3}` as 'h3';
  const price = p.priceCents != null
    ? <Price cents={p.priceCents} size="md" prefix={p.fromPrice === false ? undefined : t('from')} suffix={p.priceSuffix} />
    : p.priceText ? <span className="nl-card-price-text">{p.priceText}</span> : null;
  const body = (
    <>
      <span className="nl-scard-thumb" aria-hidden>
        {p.imageUrl ? <img src={p.imageUrl} alt="" loading="lazy" decoding="async" /> : p.mark ?? <MediaFallback name={p.name} icon={Wrench} />}
      </span>
      <span className="nl-scard-body">
        {p.kind ? <span className="nl-scard-kind">{p.kind}</span> : null}
        <H className="nl-scard-name">{p.name}</H>
        {p.rating ? <Stars rating={p.rating} reviewCount={p.reviewCount ?? undefined} /> : p.reviewCount === 0 ? <span className="nl-scard-new">{t('noReviews')}</span> : null}
        {p.place ? <span className="nl-scard-place">{p.place}</span> : null}
        {p.note ? <span className="nl-scard-note">{p.note}</span> : null}
        {p.verified ? <span className="nl-scard-seal"><SealCheck size={16} weight="fill" aria-hidden />{t('verified')}</span> : null}
      </span>
      {price ? <span className="nl-scard-price">{price}</span> : null}
    </>
  );
  const cls = clsx('nl-scard', `nl-scard-${p.layout ?? 'row'}`, p.className);
  if (p.onSelect) return <button type="button" className={cls} aria-pressed={!!p.selected} onClick={p.onSelect}>{body}</button>;
  return <SiteLink href={p.href ?? '#'} className={cls}>{body}</SiteLink>;
}
