import { useSuspenseQuery } from '@tanstack/react-query';
import { useZone } from '../location/regions';
import { EmptyState, SiteLink, TileGrid, useLocale } from '@northline/ui';
import { Art } from '../art/Art';
import { Crumbs } from '../pages/Crumbs';
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
      <Crumbs label={t('crumbs')} items={[{ label: t('home'), href: '/' }, { label: t('shop'), href: link('/shop') }, { label: data.name }]} />
      <header className="nl-band shop-band">
        <div className="nl-band-copy">
          <h1 className="nl-band-title">{data.name}</h1>
          <div className="nl-band-lede">{data.served ? sub : data.market}</div>
        </div>
        <span className="nl-band-pic" aria-hidden><Art subject={data.slug} fit="contain" /></span>
      </header>
      {data.siblings.length > 1 ? (
        <nav aria-label={t('departments', { group: data.groupName })} className="shop-chips">
          {data.siblings.map(s => (
            <SiteLink key={s.slug} href={link(`/shop/${s.slug}`)} className="nl-chip shop-chip" aria-current={s.slug === data.slug ? 'page' : undefined}>{s.name}</SiteLink>
          ))}
        </nav>
      ) : null}

      {!data.served ? (
        <div className="nl-section"><EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('notServed', { city: data.market })}</EmptyState></div>
      ) : data.shops.length === 0 ? (
        <div className="nl-section"><EmptyState action={<SiteLink href={link('/shop')} className="btn btn-primary">{t('backToShop')}</SiteLink>}>{t('emptyDept', { name: data.name, city: data.market })}</EmptyState></div>
      ) : (
        <>
          <section className="nl-section" aria-labelledby="dept-shops">
            <div className="nl-section-head"><h2 id="dept-shops" className="nl-section-title">{t('shopsIn', { name: data.name })}</h2></div>
            <TileGrid min={240}>
              {data.shops.map(s => <li key={s.merchantId}><ShopCardTile shop={s} next={run} look="initial" href={`/search?scope=shop&q=${encodeURIComponent(s.name)}`} /></li>)}
            </TileGrid>
          </section>
          <section className="nl-section" aria-labelledby="dept-products">
            <div className="nl-section-head">
              <h2 id="dept-products" className="nl-section-title">{t('popularIn', { name: data.name })}</h2>
              <span className="nl-section-lede">{t('sortedBy')} <strong>{t('sortPopular')}</strong></span>
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
