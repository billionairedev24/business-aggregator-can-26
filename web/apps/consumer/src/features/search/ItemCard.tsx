import type { ReactNode } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ProductCard, ServiceCard, unitPriceOf, useFormatters, useLocale } from '@northline/ui';
import { cartQuery, useChangeCartLine } from '../cart/api';
import { useAddToCart } from '../product/api';
import { imageUrl, itemHref, type SearchItem } from './api';
import { useSearchT } from './messages';
import { PhotoWell } from '../art/PhotoWell';
import { usePlusBadge } from '../loyalty/PlusBadge';
import { useZone } from '../location/regions';
import { windowRange } from '../shop/format';
import { useNextRun } from '../shop/run';

/**
 * One search item as a card (redesign § 1.3): products and dishes as ProductCards (results grid or home rail), services
 * and businesses as ServiceCards — products and services side by side in one grid. A product adds to the cart from the
 * card (the offer id is the result's id); when the shop needs an option chosen first the card opens the product page.
 */
export function ItemCard({ item, variant = 'grid', level = 3 }: { item: SearchItem; variant?: 'grid' | 'rail'; level?: 2 | 3 }) {
  const t = useSearchT();
  const { number } = useFormatters();
  const href = itemHref(item);
  const image = imageUrl(item.imageKey);
  const km = item.distanceKm != null ? t('km', { km: number(item.distanceKm, { maximumFractionDigits: 1 }) }) : null;
  if (item.kind === 'service' || item.kind === 'merchant') {
    const quote = item.priceCents == null || item.pricingMode === 'quote';
    return (
      <ServiceCard href={href} layout={variant === 'grid' ? 'stack' : 'row'} level={level}
        kind={item.kind === 'merchant' ? t(`type_${item.merchant.type}` as 'type_seller') : item.category?.name ?? undefined}
        name={item.name} imageUrl={image} mark={<PhotoWell subject={item.category?.id ?? item.name} />} rating={item.rating} reviewCount={item.reviewCount}
        place={[item.kind === 'service' ? item.merchant.name : null, km].filter(Boolean).join(' · ') || undefined}
        note={item.kind === 'service' && item.instantBook ? t('tag_instant') : undefined}
        priceCents={quote ? null : item.priceCents} priceText={quote ? t('quote') : undefined}
        priceSuffix={item.pricingMode === 'hourly' ? t('perHour') : undefined} fromPrice={item.kind === 'merchant' || item.pricingMode === 'hourly'}
        verified={item.trustTier === 'master' || item.trustTier === 'trusted'} />
    );
  }
  return <ProductItemCard item={item} href={href} image={image} km={km} variant={variant} level={level} />;
}

function ProductItemCard({ item, href, image, km, variant, level }: { item: SearchItem; href: string; image: string | null; km: string | null; variant: 'grid' | 'rail'; level: 2 | 3 }) {
  const t = useSearchT();
  const product = item.kind === 'product';
  return (
    <OfferCard offerId={product ? item.id : null} href={href} name={item.name} image={image} variant={variant} level={level}
      fallback={<PhotoWell subject={`${item.category?.id ?? ''} ${item.name}`} />}
      priceCents={item.priceCents ?? null} sizeWords={[item.name, item.description]}
      meta={[item.merchant.name, km ?? item.category?.name].filter(Boolean).join(' · ')}
      rating={item.rating} reviewCount={item.reviewCount} soldOut={!!item.soldOut} onTonight={!!item.onTonightsRun}
      note={item.kind === 'food' && item.openNow ? <strong>{t('tag_open')}</strong> : undefined} />
  );
}

export interface OfferCardProps {
  /** The offer the card adds to the cart; null: not addable from a card (a dish opens its kitchen). */
  offerId: string | null;
  href: string;
  name: string;
  image: string | null | undefined;
  fallback: ReactNode;
  priceCents: number | null;
  /** "from $6.50" (several sellers). */
  priceText?: string;
  /** The words that may state the pack's size ("Country sourdough, 900 g", the unit field): the unit price. */
  sizeWords?: readonly (string | null | undefined)[];
  meta?: ReactNode;
  rating?: number | null;
  reviewCount?: number | null;
  soldOut?: boolean;
  /** On the market's next pooled run: "Get it Tonight, 6–9 p.m." */
  onTonight?: boolean;
  /** Instead of the delivery promise (a kitchen open now). */
  note?: ReactNode;
  variant?: 'grid' | 'rail';
  level?: 2 | 3;
}

/**
 * A product offer as a card (results, Shop pages, home rails): the price with its unit price, the rating, the delivery
 * promise and "Add to cart" (the offer id), which becomes the stepper once it is in the cart; when the shop needs an
 * option chosen first the card opens the product page. S-152: the shop's Plus member price as a badge.
 */
export function OfferCard({ offerId, href, name, image, fallback, priceCents, priceText, sizeWords = [name], meta, rating, reviewCount, soldOut, onTonight, note, variant = 'grid', level = 3 }: OfferCardProps) {
  const t = useSearchT();
  const navigate = useNavigate();
  const cart = useQuery({ ...cartQuery, enabled: !!offerId && variant === 'grid' });
  const add = useAddToCart();
  const change = useChangeCartLine();
  const lines = (cart.data?.groups ?? []).flatMap(g => g.items).filter(l => l.offerId === offerId);
  const inCart = lines.reduce((n, l) => n + l.qty, 0);
  const line = lines.length === 1 ? lines[0] : undefined;
  const plus = usePlusBadge(offerId, priceCents);
  // E-15: the delivery promise names tonight's window when the market's next run is today
  const { run, market } = useNextRun();
  const { locale } = useLocale();
  const zone = useZone(market);
  const tonight = run && run.day === 'today' ? t('windowTonight', { window: windowRange(run.startsAt, run.endsAt, locale, zone) }) : t('tonight');
  const eta = onTonight ? <>{t('getIt')} <strong>{tonight}</strong></> : note;
  return (
    <ProductCard href={href} name={name} imageUrl={image} fallback={fallback} variant={variant} level={level}
      priceCents={priceText ? null : priceCents} priceText={priceText ?? (priceCents == null ? t('quote') : undefined)}
      unit={priceCents != null && !priceText ? unitPriceOf(priceCents, ...sizeWords) : null}
      meta={meta} rating={rating} reviewCount={reviewCount} eta={eta} soldOut={!!soldOut}
      badges={plus ? [{ label: plus, tone: 'accent' }] : undefined}
      inCart={offerId ? inCart : 0} busy={add.isPending || change.isPending}
      onAdd={offerId ? () => add.mutate({ offerId, qty: 1 }, { onError: () => void navigate({ href }) }) : undefined}
      // several lines of one offer (different options) are changed in the cart, where each has its own stepper
      onQuantity={line ? qty => change.mutate({ itemId: line.itemId, qty }) : () => void navigate({ to: '/cart' })}
      max={line?.stock} />
  );
}
