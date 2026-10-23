import { ShopTile, Skeleton, useFormatters, useLocale, type TagTone } from '@northline/ui';
import { PhotoWell } from '../art/PhotoWell';
import { useZone } from '../location/regions';
import type { ProductCard, Run, ShopCard } from './api';
import { clock, runWhen, weekday } from './format';
import { useShopT } from './messages';
import { OfferCard } from '../search/ItemCard';

/** Tier tags (E-15: one role per colour — a tier is a verification status, never the commerce colour): Master and Trusted in the success pair, Registered neutral. */
export const TIER_TONE: Record<string, TagTone> = { master: 'success', trusted: 'success', registered: 'neutral' };

export function useTierLabel() {
  const t = useShopT();
  return (tier: string) => (tier === 'master' || tier === 'trusted' || tier === 'registered' ? t(`tier_${tier}`) : tier);
}

/** The tag of something on a run: on the next run → "On tonight's run" (accent); a later run → its day (neutral). */
export function useRunTag() {
  const t = useShopT();
  const { locale } = useLocale();
  const zone = useZone();
  return (run: Run | null | undefined, next: Run | null | undefined): { label: string; tone: TagTone } => {
    if (!run) return { label: t('notOnRun'), tone: 'neutral' };
    const when = runWhen(run, zone);
    const label = t(`run_${when}`, { weekday: weekday(run.startsAt, locale, zone) });
    return { label, tone: next && run.windowId === next.windowId ? 'accent' : 'neutral' };
  };
}

/** Landing: "Order by 5:20 p.m." for the next run; the day for a later one. */
export function useOrderByTag() {
  const t = useShopT();
  const { locale } = useLocale();
  const zone = useZone();
  return (run: Run | null | undefined, next: Run | null | undefined): { label: string; tone: TagTone } => {
    if (!run) return { label: t('notOnRun'), tone: 'neutral' };
    if (next && run.windowId === next.windowId) return { label: t('orderBy', { time: clock(run.orderBy, locale, zone) }), tone: 'accent' };
    return { label: runWhen(run, zone) === 'tomorrow' ? t('tomorrow') : t('laterDay', { weekday: weekday(run.startsAt, locale, zone) }), tone: 'neutral' };
  };
}

/**
 * A popular product of a Shop page as the results' card (E-15: one card everywhere): the price with its unit price
 * (from the product's unit or name), "Get it Tonight" when it rides the market's next run, "Add to cart" (its offer).
 * Sold by several shops: "from $6.50" and the product page picks the offer. Age-restricted products and a shop's Plus
 * price say so under the shop's name.
 */
export function ProductCardTile({ product, withMarket, next }: { product: ProductCard; withMarket: (path: string) => string; next?: Run | null }) {
  const t = useShopT();
  const { money } = useFormatters();
  const many = product.sellers > 1;
  const shops = many ? t('productMetaMany', { shop: product.shopName, others: product.sellers - 1 })
    : product.unit ? t('productMeta', { shop: product.shopName, unit: product.unit }) : product.shopName;
  // an age-restricted product says so on its card, before the checkout's ID check
  const age = product.age ? (product.age.minimumAge ? t('ageCard', { age: product.age.minimumAge }) : t('ageCardNoAge')) : null;
  const meta = <>{shops}{age ? <span className="nl-age-chip">{age}</span> : null}</>;
  const href = withMarket(`/products/${product.productId}`);
  return (
    <OfferCard offerId={many ? null : product.offerId} href={href} name={product.name} image={product.imageUrl}
      fallback={<PhotoWell subject={`${product.categoryId ?? ''} ${product.name}`} />}
      priceCents={product.priceCents} priceText={many ? t('from', { price: money(product.priceCents) }) : undefined}
      sizeWords={[product.unit, product.name]} meta={meta}
      onTonight={!!(next && next.day === 'today' && product.run?.windowId === next.windowId)} />
  );
}

export function ShopCardTile({ shop, next, look, href }: { shop: ShopCard; next: Run | null | undefined; look: 'dot' | 'initial'; href: string }) {
  const t = useShopT();
  const tier = useTierLabel();
  const runTag = useRunTag();
  const orderBy = useOrderByTag();
  const products = t('productsCount', { count: shop.products });
  // the shop's own photo, else its letter (a department photo would repeat on every shop of the department)
  const imageUrl = shop.imageUrl ?? null;
  return look === 'dot'
    ? <ShopTile look="dot" id={shop.merchantId} href={href} name={shop.name} imageUrl={imageUrl} meta={t('shopDept', { department: shop.departmentName, products })} tag={orderBy(shop.run, next)} />
    : <ShopTile id={shop.merchantId} href={href} name={shop.name} imageUrl={imageUrl} meta={products} tier={{ label: tier(shop.tier), tone: TIER_TONE[shop.tier] ?? 'neutral' }} tag={runTag(shop.run, next)} />;
}

/** Skeleton of a Shop page while it loads (the landing head, a tile grid, a product grid): the boxes of the page. */
export function ShopSkeleton() {
  const t = useShopT();
  return (
    <div className="nl-page shop-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <div className="shop-skeleton-head">
        <Skeleton width={200} height={12} />
        <Skeleton width="min(520px, 90%)" height={28} />
        <Skeleton width="min(360px, 70%)" height={14} />
      </div>
      <div className="shop-skeleton-grid shop-skeleton-depts">{Array.from({ length: 9 }, (_, i) => <span key={i} className="shop-skeleton-pic" />)}</div>
      <div className="shop-skeleton-grid shop-skeleton-products">{Array.from({ length: 5 }, (_, i) => <div key={i} className="shop-skeleton-card"><span className="shop-skeleton-pic" /><Skeleton width="70%" height={14} /></div>)}</div>
    </div>
  );
}
