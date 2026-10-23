import { useQuery } from '@tanstack/react-query';
import { EmptyState, ErrorState, SiteLink, Tag, useLocale } from '@northline/ui';
import { MagnifyingGlass } from '@phosphor-icons/react';
import { Crumbs } from '../pages/Crumbs';
import { PhotoWell } from '../art/PhotoWell';
import { useZone } from '../location/regions';
import { ItemCard } from '../search/ItemCard';
import { clock, runWhen, weekday } from './format';
import { withMarket } from './market';
import { useShopT } from './messages';
import { ProductCardTile, TIER_TONE, useTierLabel } from './parts';
import { storeGoodsQuery, type StoreData } from './store';

/**
 * A shop's page (E-15 S-172; the store page in the system of design/revamp/a.html's product and provider pages): the
 * breadcrumb, a header with the shop's photo well, department · market, its name, tier and the next run it packs for,
 * then its products as the results' cards. Data: its card (department or landing) and the public search by its name,
 * kept to this merchant; when the search has nothing of it, the popular products the Shop pages list for it.
 */
export function StorePage({ data, market }: { data: StoreData; market?: string }) {
  const t = useShopT();
  const { locale } = useLocale();
  const tier = useTierLabel();
  const { shop, landing, department } = data;
  const zone = useZone(landing.market);
  const link = (path: string) => withMarket(path, market);
  const goods = useQuery(storeGoodsQuery(shop.name, locale));
  const found = (goods.data?.items ?? []).filter(i => i.merchant.id === shop.merchantId);
  const listed = [...(department?.products ?? []), ...landing.popular].filter((p, i, all) => p.merchantId === shop.merchantId && all.findIndex(q => q.productId === p.productId) === i);
  const next = department?.run ?? landing.run;
  const run = shop.run;
  const searchHref = `/search?${new URLSearchParams({ scope: 'shop', q: shop.name })}`;

  return (
    <div className="nl-page shop-page store-page">
      <Crumbs label={t('crumbs')} items={[
        { label: t('shop'), href: link('/shop') },
        { label: shop.departmentName, href: link(`/shop/${shop.departmentSlug}`) },
        { label: shop.name },
      ]} />
      <header className="store-head">
        <span className="store-pic" aria-hidden>{shop.imageUrl ? <img src={shop.imageUrl} alt="" /> : <PhotoWell subject={shop.departmentSlug} />}</span>
        <div className="store-id">
          <span className="store-kicker">{t('storeKicker', { department: shop.departmentName, city: landing.market })}</span>
          <h1 className="store-name">{shop.name}</h1>
          <div className="store-meta">
            <Tag tone={TIER_TONE[shop.tier] ?? 'neutral'}>{tier(shop.tier)}</Tag>
            <span>{t('productsCount', { count: shop.products })}</span>
            {run ? <span className="store-run">{t('storeRun', { when: runWhen(run, zone), weekday: weekday(run.startsAt, locale, zone), time: clock(run.orderBy, locale, zone) })}</span> : <span>{t('notOnRun')}</span>}
          </div>
        </div>
        <SiteLink href={searchHref} className="btn btn-secondary store-search"><MagnifyingGlass size={16} aria-hidden />{t('storeSearch')}</SiteLink>
      </header>

      <section className="nl-section" aria-labelledby="store-products">
        <div className="nl-section-head"><h2 id="store-products" className="nl-section-title">{t('storeProducts')}</h2></div>
        {found.length > 0 ? (
          <ul className="nl-card-grid nl-card-grid-results" aria-labelledby="store-products">
            {found.map(i => <li key={i.id}><ItemCard item={i} /></li>)}
          </ul>
        ) : listed.length > 0 ? (
          <ul className="nl-card-grid nl-card-grid-results" aria-labelledby="store-products">
            {listed.map(p => <li key={p.productId}><ProductCardTile product={p} withMarket={link} next={next} /></li>)}
          </ul>
        ) : goods.isError ? <ErrorState message={t('loadError')} onRetry={() => void goods.refetch()} />
          : <EmptyState action={<SiteLink href={link(`/shop/${shop.departmentSlug}`)} className="btn btn-secondary">{shop.departmentName}</SiteLink>}>{t('storeEmpty', { shop: shop.name })}</EmptyState>}
      </section>
    </div>
  );
}
