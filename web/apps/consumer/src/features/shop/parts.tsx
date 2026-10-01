import { ProductTile, ShopTile, Skeleton, useFormatters, useLocale, type TagTone } from '@northline/ui';
import { useZone } from '../location/regions';
import type { ProductCard, Run, ShopCard } from './api';
import { clock, runWhen, weekday } from './format';
import { useShopT } from './messages';

/** Design 06 tier tags: Master = accent, Trusted = rosehip, Registered = neutral. */
export const TIER_TONE: Record<string, TagTone> = { master: 'accent', trusted: 'accent-2', registered: 'neutral' };

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

export function ProductCardTile({ product, withMarket }: { product: ProductCard; withMarket: (path: string) => string }) {
  const t = useShopT();
  const { money } = useFormatters();
  const price = money(product.priceCents);
  const meta = product.sellers > 1
    ? t('productMetaMany', { shop: product.shopName, others: product.sellers - 1 })
    : product.unit ? t('productMeta', { shop: product.shopName, unit: product.unit }) : product.shopName;
  return (
    <ProductTile href={withMarket(`/products/${product.productId}`)} name={product.name} imageUrl={product.imageUrl}
      price={product.sellers > 1 ? t('from', { price }) : price} meta={meta} />
  );
}

export function ShopCardTile({ shop, next, look, href }: { shop: ShopCard; next: Run | null | undefined; look: 'dot' | 'initial'; href: string }) {
  const t = useShopT();
  const tier = useTierLabel();
  const runTag = useRunTag();
  const orderBy = useOrderByTag();
  const products = t('productsCount', { count: shop.products });
  return look === 'dot'
    ? <ShopTile look="dot" id={shop.merchantId} href={href} name={shop.name} meta={t('shopDept', { department: shop.departmentName, products })} tag={orderBy(shop.run, next)} />
    : <ShopTile id={shop.merchantId} href={href} name={shop.name} meta={products} tier={{ label: tier(shop.tier), tone: TIER_TONE[shop.tier] ?? 'neutral' }} tag={runTag(shop.run, next)} />;
}

/** Skeleton of a Shop page while it loads (kicker, title, a tile grid, a product grid). */
export function ShopSkeleton() {
  const t = useShopT();
  return (
    <div className="nl-page shop-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width={200} height={12} />
      <Skeleton width="min(520px, 90%)" height={40} style={{ marginTop: 10 }} />
      <Skeleton width="min(360px, 70%)" height={14} style={{ marginTop: 10 }} />
      <div className="shop-skeleton-grid shop-skeleton-depts">{Array.from({ length: 9 }, (_, i) => <Skeleton key={i} height={88} radius={12} />)}</div>
      <div className="shop-skeleton-grid shop-skeleton-products">{Array.from({ length: 4 }, (_, i) => <div key={i}><Skeleton height={0} style={{ aspectRatio: '1 / 1', height: 'auto' }} radius={12} /><Skeleton width="70%" height={14} style={{ marginTop: 10 }} /></div>)}</div>
    </div>
  );
}
