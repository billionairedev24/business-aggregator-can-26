import { useSuspenseQuery } from '@tanstack/react-query';
import { useZone } from '../location/regions';
import { EmptyState, SiteLink, TileGrid, useLocale } from '@northline/ui';
import { departmentQuery } from './api';
import { runWhen, weekday } from './format';
import { useMarketFollowsLocation, withMarket } from './market';
import { useShopT } from './messages';
import { ProductCardTile, ShopCardTile } from './parts';

/**
 * A department (S-49, design 06 `category`): breadcrumb, counts, the department's siblings in its group (the chips —
 * the taxonomy has no finer aisles, DECISIONS S-49), its shops and its popular products. Server-rendered for SEO.
 */
export function DepartmentPage({ slug, market: explicit }: { slug: string; market?: string }) {
  const { locale } = useLocale();
  const t = useShopT();
  const { data } = useSuspenseQuery(departmentQuery(slug, explicit, locale));
  const zone = useZone(data.market);
  useMarketFollowsLocation(explicit, data.market);
  const link = (path: string) => withMarket(path, explicit);
  const run = data.run;
  const when = run ? runWhen(run, zone) : null;
  const counts = { shops: data.shopCount, city: data.market, onRun: data.onRunCount, items: data.productCount };
  const sub = run && when ? t(`deptSub_${when}`, { ...counts, weekday: weekday(run.startsAt, locale, zone) }) : t('deptSubNoRun', counts);

  return (
    <div className="nl-page shop-page">
      <nav aria-label={t('crumbs')} className="shop-crumbs">
        <ol><li><SiteLink href="/">{t('home')}</SiteLink></li><li><SiteLink href={link('/shop')}>{t('shop')}</SiteLink></li><li aria-current="page">{data.name}</li></ol>
      </nav>
      <header className="shop-dept-head">
        <div>
          <h1 className="shop-title">{data.name}</h1>
          <div className="shop-sub">{data.served ? sub : data.market}</div>
        </div>
        {data.siblings.length > 1 ? (
          <nav aria-label={t('departments', { group: data.groupName })} className="shop-chips">
            {data.siblings.map(s => (
              <SiteLink key={s.slug} href={link(`/shop/${s.slug}`)} className="shop-chip" aria-current={s.slug === data.slug ? 'page' : undefined}>{s.name}</SiteLink>
            ))}
          </nav>
        ) : null}
      </header>

      {!data.served ? (
        <div className="shop-section"><EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('notServed', { city: data.market })}</EmptyState></div>
      ) : data.shops.length === 0 ? (
        <div className="shop-section"><EmptyState action={<SiteLink href={link('/shop')} className="btn btn-primary">{t('backToShop')}</SiteLink>}>{t('emptyDept', { name: data.name, city: data.market })}</EmptyState></div>
      ) : (
        <>
          <section className="shop-section" aria-labelledby="dept-shops">
            <h2 id="dept-shops" className="shop-h2">{t('shopsIn', { name: data.name })}</h2>
            <TileGrid min={240}>
              {data.shops.map(s => <li key={s.merchantId}><ShopCardTile shop={s} next={run} look="initial" href={`/search?scope=shop&q=${encodeURIComponent(s.name)}`} /></li>)}
            </TileGrid>
          </section>
          <section className="shop-section" aria-labelledby="dept-products">
            <div className="shop-section-head">
              <h2 id="dept-products" className="shop-h2">{t('popularIn', { name: data.name })}</h2>
              <span className="shop-note">{t('sortedBy')} <strong>{t('sortPopular')}</strong></span>
            </div>
            <TileGrid min={200}>
              {data.products.map(p => <li key={p.productId}><ProductCardTile product={p} withMarket={link} /></li>)}
            </TileGrid>
          </section>
        </>
      )}
    </div>
  );
}
