import { useSuspenseQuery } from '@tanstack/react-query';
import { useZone } from '../location/regions';
import { EmptyState, SiteLink, Tag, TileGrid, useLocale } from '@northline/ui';
import { Art } from '../art/Art';
import { RailSection, RailTrack } from '../pages/Rail';
import { Tile } from '../pages/Tile';
import { landingQuery } from './api';
import { clock, runWhen, weekday } from './format';
import { useArea, useMarketFollowsLocation, withMarket } from './market';
import { useShopT } from './messages';
import { ProductCardTile, ShopCardTile } from './parts';

/**
 * Shop landing (S-49, design 06 `shop`): a landing page, not results — the next pooled run, departments, the shops on
 * the run and what's popular. Server-rendered for the market in the URL (none = the api's fallback market); the visitor's own market
 * follows once the browser knows where they are.
 */
export function ShopLanding({ market: explicit }: { market?: string }) {
  const { locale } = useLocale();
  const t = useShopT();
  const { data } = useSuspenseQuery(landingQuery(explicit, locale));
  const zone = useZone(data.market);
  useMarketFollowsLocation(explicit, data.market);
  const area = useArea(data.market);
  const link = (path: string) => withMarket(path, explicit);
  const run = data.run;
  const when = run ? runWhen(run, zone) : null;
  const day = run ? weekday(run.startsAt, locale, zone) : '';

  const title = run && when ? t(`title_${when}`, { time: clock(run.startsAt, locale, zone), weekday: day }) : t('titleNoRun');
  const sub = run
    ? t('sub', { time: clock(run.orderBy, locale, zone), shops: data.shopCount, households: run.households })
    : t('subNoRun', { shops: data.shopCount });

  return (
    <div className="nl-page shop-page">
      <header className="nl-band shop-band">
        <div className="nl-band-copy">
          <div className="nl-band-kicker">{t('kicker')}</div>
          <h1 className="nl-band-title">{title}</h1>
          <div className="nl-band-lede">{data.served ? `${sub} · ${area}` : area}</div>
          {data.served && run ? <div className="nl-band-actions"><Tag tone="success">{t('pooledTag', { area })}</Tag></div> : null}
        </div>
        <span className="nl-band-pic" aria-hidden><Art subject="groceries" tone="ember" fit="contain" /></span>
      </header>

      {!data.served ? (
        <div className="nl-section"><EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('notServed', { city: data.market })}</EmptyState></div>
      ) : data.shopCount === 0 ? (
        <div className="nl-section"><EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('emptyShop', { city: data.market })}</EmptyState></div>
      ) : (
        <>
          <section className="nl-section" aria-labelledby="shop-depts">
            <div className="nl-section-head"><h2 id="shop-depts" className="nl-section-title">{t('byDept')}</h2></div>
            <ul className="nl-tiles shop-depts">
              {data.departments.map(d => <li key={d.slug}><Tile href={link(`/shop/${d.slug}`)} name={d.name} subject={d.slug} meta={t('shopsCount', { count: d.shops })} /></li>)}
            </ul>
          </section>

          <section className="nl-section" aria-labelledby="shop-run">
            <div className="nl-section-head">
              <h2 id="shop-run" className="nl-section-title">{run && when ? t(`allShops_${when}`, { weekday: day }) : t('allShopsNoRun')}</h2>
              {run ? <span className="nl-section-lede">{t('runNote')}</span> : null}
            </div>
            <TileGrid min={240}>
              {data.shops.map(s => <li key={s.merchantId}><ShopCardTile shop={s} next={run} look="dot" href={link(`/shop/${s.departmentSlug}`)} /></li>)}
            </TileGrid>
          </section>

          <RailSection id="shop-popular" title={t('popular')} more={{ href: '/search?scope=shop', label: t('searchAll') }}>
            <RailTrack size="product">
              {data.popular.map(p => <li key={p.productId}><ProductCardTile product={p} withMarket={link} /></li>)}
            </RailTrack>
          </RailSection>
        </>
      )}
    </div>
  );
}
