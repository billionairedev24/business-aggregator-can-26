import type { ReactNode } from 'react';
import clsx from 'clsx';
import { Minus, Package, Plus, SealCheck, Trash, Wrench, type Icon } from '@phosphor-icons/react';
import { defineMessages, formatMoney, formatNumber, langTag, useLocale, type Locale } from './i18n';
import { SiteLink } from './SiteLink';
import type { TagTone } from './Tag';

/**
 * The consumer commerce kit in AfroTransact's card language (redesign § 1.3, Shop:313–332 and Shop:891; impl
 * `ui/src/commerce/{Price,Stars,ProductCard}.tsx`): Price (whole-dollar figure with superscript currency and cents),
 * Stars (fractional, in the rating colour, with the review count), QuantityStepper (− 2 in cart +), ProductCard (results
 * grid and home rail) and ServiceCard (a provider's or a service's card: 96px thumbnail | name, stars, place | "from"
 * price). Semantic tokens only; every target ≥ 44px; every string in en and fr-CA.
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
  },
});

// ── Price ───────────────────────────────────────────────────────────────────────────────────────────────────────

function priceParts(cents: number, locale: Locale) {
  const parts = new Intl.NumberFormat(langTag(locale), { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol' }).formatToParts(Math.abs(cents) / 100);
  const symbolAt = parts.findIndex(p => p.type === 'currency');
  const integerAt = parts.findIndex(p => p.type === 'integer');
  return {
    symbol: parts.find(p => p.type === 'currency')?.value ?? '$',
    whole: parts.filter(p => p.type === 'integer' || p.type === 'group').map(p => p.value).join(''),
    cents: parts.find(p => p.type === 'fraction')?.value ?? '00',
    after: symbolAt > integerAt,
  };
}

export interface PriceProps {
  /** CAD cents. */
  cents: number;
  /** A higher earlier price, struck through ("Was $9.00"). */
  compareAtCents?: number | null;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Before the figure: "from", "/ hr" goes in `suffix`. */
  prefix?: ReactNode;
  suffix?: ReactNode;
  className?: string;
}

/**
 * A storefront price: the whole-dollar figure with the currency sign and the cents in superscript ($7⁵⁰ in en-CA,
 * 7⁵⁰ $ in fr-CA). Screen readers get the plain formatted amount.
 */
export function Price({ cents, compareAtCents, size = 'md', prefix, suffix, className }: PriceProps) {
  const t = useT();
  const { locale } = useLocale();
  const p = priceParts(cents, locale);
  const was = compareAtCents && compareAtCents > cents ? compareAtCents : null;
  return (
    <span className={clsx('nl-price', `nl-price-${size}`, className)}>
      {prefix ? <span className="nl-price-prefix">{prefix}</span> : null}
      <span className="nl-price-amount" aria-hidden>
        {p.after ? null : <span className="nl-price-sup">{p.symbol}</span>}
        <span className="nl-price-whole">{p.whole}</span>
        <span className="nl-price-sup">{p.cents}</span>
        {p.after ? <span className="nl-price-sup">{` ${p.symbol}`}</span> : null}
      </span>
      <span className="nl-sr-only">{formatMoney(cents, locale)}</span>
      {suffix ? <span className="nl-price-suffix">{suffix}</span> : null}
      {was ? <s className="nl-price-was">{t('was', { price: formatMoney(was, locale) })}</s> : null}
    </span>
  );
}

// ── Stars ───────────────────────────────────────────────────────────────────────────────────────────────────────

const STAR = 'M12 2.5l2.94 6.24 6.81.79-5.04 4.66 1.35 6.73L12 17.52 5.94 20.92l1.35-6.73L2.25 9.53l6.81-.79z';

function Star({ fill, size }: { fill: number; size: number }) {
  return (
    <span className="nl-star" style={{ width: size, height: size }}>
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
  /** The number before the stars (product page). */
  showValue?: boolean;
  size?: number;
  className?: string;
}

/** Read-only rating: five stars filled to the nearest half in the rating colour, announced as "4.5 out of 5 stars". */
export function Stars({ rating, reviewCount, showValue, size = 14, className }: StarsProps) {
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
 * What a card shows where its photo would be when it has none — never a blank box: the name's first letter in the
 * display face on the linen ground, a quiet icon of the kind of thing (a parcel for goods, a wrench for services).
 * Decorative (the card's title names it).
 */
export function MediaFallback({ name, icon: I = Package, className }: { name: string; icon?: Icon; className?: string }) {
  const initial = name.trim().charAt(0).toUpperCase() || '·';
  return (
    <span className={clsx('nl-media-fallback', className)} aria-hidden>
      <span className="nl-media-fallback-initial">{initial}</span>
      <I size={22} className="nl-media-fallback-icon" />
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
  /** "Glenmore Bakery · 900 g". */
  meta?: ReactNode;
  rating?: number | null;
  reviewCount?: number | null;
  badges?: readonly CardBadge[];
  /** The delivery promise ("Get it <strong>tonight</strong>"). */
  eta?: ReactNode;
  /** What the picture shows without a photo (an app's illustration); the initial on linen (MediaFallback) by default. */
  fallback?: ReactNode;
  /** Shows "Only N left" in the low-stock colour. */
  lowStock?: number | null;
  soldOut?: boolean;
  /** `grid` (results, bordered, 1:1 padded image, the cart action) or `rail` (home rows: borderless, title clamped). */
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
 * A product in a results grid or a home rail (AT ProductCard): image on the sunken surface, title (link colour on
 * hover), meta, stars, price, badges, delivery promise, low stock, then a full-width 32px "Add to cart" pill that turns
 * into "− 2 in cart +". The image repeats the title's link and is hidden from assistive technology (one link per card).
 */
export function ProductCard(p: ProductCardProps) {
  const t = useT();
  const variant = p.variant ?? 'grid';
  const H = `h${p.level ?? 3}` as 'h3';
  const price = p.priceCents != null
    ? <Price cents={p.priceCents} compareAtCents={p.compareAtCents} size={variant === 'rail' ? 'md' : 'lg'} />
    : p.priceText ? <span className="nl-card-price-text">{p.priceText}</span> : null;
  return (
    <article className={clsx('nl-pcard', `nl-pcard-${variant}`, p.className)} aria-label={p.name}>
      <SiteLink href={p.href} className="nl-pcard-media" tabIndex={-1} aria-hidden="true">
        <span className="nl-pcard-img">{p.imageUrl ? <img src={p.imageUrl} alt="" loading="lazy" decoding="async" /> : p.fallback ?? <MediaFallback name={p.name} />}</span>
      </SiteLink>
      <div className="nl-pcard-body">
        <H className="nl-pcard-heading"><SiteLink href={p.href} className="nl-pcard-title">{p.name}</SiteLink></H>
        {p.meta ? <span className="nl-pcard-meta">{p.meta}</span> : null}
        {p.rating ? <Stars rating={p.rating} reviewCount={p.reviewCount ?? undefined} size={variant === 'rail' ? 12 : 14} /> : null}
        {price ? <span className="nl-pcard-price">{price}</span> : null}
        {p.badges?.length ? <span className="nl-pcard-badges">{p.badges.map((b, i) => <span key={i} className={clsx('tag', `tag-${b.tone ?? 'neutral'}`)}>{b.label}</span>)}</span> : null}
        {p.eta && !p.soldOut ? <span className="nl-pcard-eta">{p.eta}</span> : null}
        {p.lowStock && !p.soldOut ? <span className="nl-pcard-low">{t('onlyLeft', { count: p.lowStock })}</span> : null}
        {variant === 'grid' && (p.onAdd || p.soldOut) ? (
          <div className="nl-pcard-actions">
            {p.soldOut ? <span className="nl-pcard-unavailable">{t('soldOut')}</span>
              : (p.inCart ?? 0) > 0 && p.onQuantity
                ? <QuantityStepper block inCart value={p.inCart!} max={Math.min(99, p.max ?? 99)} name={p.name} busy={p.busy} onChange={p.onQuantity} />
                : <button type="button" className="btn btn-accent btn-sm btn-block" disabled={p.busy} aria-busy={p.busy || undefined} aria-label={t('addNamed', { name: p.name })} onClick={p.onAdd}>
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
