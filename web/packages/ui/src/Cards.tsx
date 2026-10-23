import type { ReactNode } from 'react';
import clsx from 'clsx';
import { SealCheck, Truck, Warning, Wrench } from '@phosphor-icons/react';
import { SiteLink } from './SiteLink';
import type { TagTone } from './Tag';
import { useCommerceT } from './commerceText';
import { DealBadge, MediaFallback, Price, QuantityStepper, Stars, type PriceProps } from './Commerce';

/*
 * S-176: the product and service cards in their own module, so an app's first screen that only draws prices and
 * status tags (the Studio's dashboard: Price, StatusTag) does not load them — rollup keeps one chunk per module, and
 * the cards load with the screens that show them (results, rails, the editors' and storefront's live previews).
 * Exported from the package index exactly as before.
 */
const useT = useCommerceT;

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
