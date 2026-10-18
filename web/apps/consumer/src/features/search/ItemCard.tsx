import { useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ProductCard, ServiceCard, useFormatters } from '@northline/ui';
import { cartQuery, useChangeCartLine } from '../cart/api';
import { useAddToCart } from '../product/api';
import { imageUrl, itemHref, type SearchItem } from './api';
import { useSearchT } from './messages';

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
        name={item.name} imageUrl={image} rating={item.rating} reviewCount={item.reviewCount}
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
  const navigate = useNavigate();
  const product = item.kind === 'product';
  const cart = useQuery({ ...cartQuery, enabled: product && variant === 'grid' });
  const add = useAddToCart();
  const change = useChangeCartLine();
  const lines = (cart.data?.groups ?? []).flatMap(g => g.items).filter(l => l.offerId === item.id);
  const inCart = lines.reduce((n, l) => n + l.qty, 0);
  const line = lines.length === 1 ? lines[0] : undefined;
  const eta = item.onTonightsRun ? <>{t('getIt')} <strong>{t('tonight')}</strong></>
    : item.kind === 'food' && item.openNow ? <strong>{t('tag_open')}</strong> : undefined;
  return (
    <ProductCard href={href} name={item.name} imageUrl={image} variant={variant} level={level}
      priceCents={item.priceCents ?? null} priceText={item.priceCents == null ? t('quote') : undefined}
      meta={[item.merchant.name, item.category?.name, km].filter(Boolean).join(' · ')}
      rating={item.rating} reviewCount={item.reviewCount} eta={eta} soldOut={!!item.soldOut}
      inCart={product ? inCart : 0} busy={add.isPending || change.isPending}
      onAdd={product ? () => add.mutate({ offerId: item.id, qty: 1 }, { onError: () => void navigate({ href }) }) : undefined}
      // several lines of one offer (different options) are changed in the cart, where each has its own stepper
      onQuantity={line ? qty => change.mutate({ itemId: line.itemId, qty }) : () => void navigate({ to: '/cart' })}
      max={line?.stock} />
  );
}
