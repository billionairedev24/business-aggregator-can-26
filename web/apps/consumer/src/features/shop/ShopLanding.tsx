import { useSuspenseQuery } from '@tanstack/react-query';
import { DepartmentTile, EmptyState, SiteLink, Tag, TileGrid, useLocale } from '@northline/ui';
import { landingQuery } from './api';
import { clock, runWhen, weekday } from './format';
import { useArea, useMarketFollowsLocation, withMarket } from './market';
import { useShopT } from './messages';
import { ProductCardTile, ShopCardTile } from './parts';

/**
 * Shop landing (S-49, design 06 `shop`): a landing page, not results — the next pooled run, departments, the shops on
 * the run and what's popular. Server-rendered for the market in the URL (default Calgary); the visitor's own market
 * follows once the browser knows where they are.
 */
export function ShopLanding({ market: explicit }: { market?: string }) {
  const { locale } = useLocale();
  const t = useShopT();
  const market = explicit ?? 'Calgary';
  useMarketFollowsLocation(explicit);
  const { data } = useSuspenseQuery(landingQuery(market, locale));
  const area = useArea(data.market);
  const link = (path: string) => withMarket(path, explicit);
  const run = data.run;
  const when = run ? runWhen(run) : null;
  const day = run ? weekday(run.startsAt, locale) : '';

  const title = run && when ? t(`title_${when}`, { time: clock(run.startsAt, locale), weekday: day }) : t('titleNoRun');
  const sub = run
    ? t('sub', { time: clock(run.orderBy, locale), shops: data.shopCount, households: run.households })
    : t('subNoRun', { shops: data.shopCount });

  return (
    <div className="nl-page shop-page">
      <header className="shop-hero">
        <div>
          <div className="shop-kicker">{t('kicker')}</div>
          <h1 className="shop-title">{title}</h1>
          <div className="shop-sub">{data.served ? `${sub} · ${area}` : area}</div>
        </div>
        {data.served && run ? <Tag>{t('pooledTag', { area })}</Tag> : null}
      </header>

      {!data.served ? (
        <div className="shop-section"><EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('notServed', { city: data.market })}</EmptyState></div>
      ) : data.shopCount === 0 ? (
        <div className="shop-section"><EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('emptyShop', { city: data.market })}</EmptyState></div>
      ) : (
        <>
          <section className="shop-section" aria-labelledby="shop-depts">
            <h2 id="shop-depts" className="shop-h2">{t('byDept')}</h2>
            <TileGrid min={130}>
              {data.departments.map(d => <li key={d.slug}><DepartmentTile href={link(`/shop/${d.slug}`)} name={d.name} count={t('shopsCount', { count: d.shops })} /></li>)}
            </TileGrid>
          </section>

          <section className="shop-section" aria-labelledby="shop-run">
            <div className="shop-section-head">
              <h2 id="shop-run" className="shop-h2">{run && when ? t(`allShops_${when}`, { weekday: day }) : t('allShopsNoRun')}</h2>
              {run ? <span className="shop-note">{t('runNote')}</span> : null}
            </div>
            <TileGrid min={240}>
              {data.shops.map(s => <li key={s.merchantId}><ShopCardTile shop={s} next={run} look="dot" href={link(`/shop/${s.departmentSlug}`)} /></li>)}
            </TileGrid>
          </section>

          <section className="shop-section" aria-labelledby="shop-popular">
            <div className="shop-section-head">
              <h2 id="shop-popular" className="shop-h2">{t('popular')}</h2>
              <SiteLink href="/search?scope=shop" className="shop-more">{t('searchAll')}</SiteLink>
            </div>
            <TileGrid min={200}>
              {data.popular.map(p => <li key={p.productId}><ProductCardTile product={p} withMarket={link} /></li>)}
            </TileGrid>
          </section>
        </>
      )}
    </div>
  );
}
