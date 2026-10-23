import { useEffect, type ReactNode } from 'react';
import { useQueries, useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useRouterState } from '@tanstack/react-router';
import { Clock } from '@phosphor-icons/react';
import {
  brand, DealBadge, EmptyState, ErrorState, PanelTile, PanelTiles, PictureGrid, Price, ServiceCard, SiteLink, Skeleton, useLocale,
  type PictureGridItem,
} from '@northline/ui';
import { categorySlot, cuisineSlot, departmentSlot } from '@northline/media';
import { Scene } from '../art/Scene';
import { activityQuery } from '../account/api';
import { useKitchens, type Card as Kitchen } from '../food/api';
import { CUISINE_NAMES } from '../food/messages';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { useZone } from '../location/regions';
import { rememberInvite } from '../loyalty/api';
import { RewardsNearYou } from '../loyalty/RewardsNearYou';
import { RailSection, RailTrack } from '../pages/Rail';
import { productQuery } from '../product/api';
import { marketOf, railQuery, type Place, type SearchItem, type SearchPage } from '../search/api';
import { ItemCard } from '../search/ItemCard';
import { useViewer } from '../session/api';
import type { Landing, ProductCard } from '../shop/api';
import { clock, runWhen, weekday } from '../shop/format';
import { useNextRun } from '../shop/run';
import { CUISINES, DEPARTMENTS, SERVICE_CATEGORIES, type Tile as CatalogTile } from './catalog';
import { MakerHero } from './MakerHero';
import { useHomeT } from './messages';

type T = ReturnType<typeof useHomeT>;

const SHOP_TILES = ['bakery', 'groceries', 'butcher', 'produce'];
const SERVICE_TILES = ['mechanic', 'cleaning', 'plumber', 'barber'];
const FOOD_TILES = ['vietnamese', 'indian', 'pizza', 'ramen'];

/**
 * Home (E-15 A · Plainsight, design/revamp/a.html "Consumer home", with Main Street's maker-of-the-week hero from
 * b.html): under the header and the delivery cut-off strip (the shell's), the hero — one featured local shop and two
 * side tiles — then four home tiles (departments, services, kitchens, the deal of the day) and rails: order again
 * (signed in, from the shops of the person's own orders), popular products, the top-rated pros and the popular
 * kitchens where the visitor is. The tiles are the catalogue's and render on the server; the hero and the rails load in
 * the browser once the place is known (the server doesn't know where the visitor is), in boxes drawn at their size.
 */
export function HomeScreen() {
  const t = useHomeT();
  const { locale } = useLocale();
  const { location, chooser } = useDeliveryLocation();
  const { user } = useViewer();
  const place: Place | null = location.status === 'locating' ? null
    : { market: marketOf(location.province), lat: location.lat, lng: location.lng };
  const { landing } = useNextRun();
  const products = useQuery({ ...railQuery('product', place ?? {}, locale), enabled: place !== null });
  const services = useQuery({ ...railQuery('service', place ?? {}, locale, 'rating'), enabled: place !== null });
  const kitchens = useKitchens(place ? location.city : undefined, location.lat, location.lng);
  const activity = useQuery({ ...activityQuery, enabled: !!user });
  const city = location.label?.split(',')[0]?.trim() || location.city;
  const empty = products.data?.items.length === 0 && services.data?.items.length === 0;
  // S-152: an invite link (/?invite=CODE) is kept until the person uses it in their account
  const searchStr = useRouterState({ select: s => s.location.searchStr });
  useEffect(() => { rememberInvite(searchStr); }, [searchStr]);

  // order again: the products of the shops the person has ordered from, from the same rail (no order-line history in the api)
  const shopsOrdered = new Set((activity.data ?? []).filter(a => a.kind === 'order').flatMap(a => a.with.map(w => w.toLowerCase())));
  const again = (products.data?.items ?? []).filter(i => shopsOrdered.has(i.merchant.name.toLowerCase()));

  return (
    <div className="nl-home">
      <h1 className="nl-sr-only">{t('pageTitle', { brand: brand.name })}</h1>
      <MakerHero landing={landing} services={services.data?.items} kitchens={kitchens.data?.items} />
      <HomeTiles t={t} landing={landing} kitchens={kitchens.data?.items} />

      {empty ? (
        <section className="nl-section" aria-label={t('loadError')}>
          <EmptyState title={city ? t('emptyCity', { city }) : t('emptyAnywhere')} action={<button type="button" className="btn btn-primary" onClick={chooser.show}>{t('changeLocation')}</button>}>{t('emptyHint')}</EmptyState>
        </section>
      ) : (
        <>
          {user && again.length > 0 ? (
            <ProductRail t={t} id="home-again" title={t('orderAgain')} more={{ href: '/account/orders', label: t('yourOrders') }} query={products} items={again} loading={false} />
          ) : null}
          <ProductRail t={t} id="home-products" title={city ? t('popularNear', { city }) : t('popularAnywhere')} more={{ href: '/shop', label: t('tileShopMore') }}
            query={products} items={products.data?.items.filter(i => !again.includes(i))} loading={place === null} />
          <ServiceRail t={t} title={city ? t('prosNear', { city }) : t('prosAnywhere')} query={services} loading={place === null} />
          <KitchenRail t={t} kitchens={kitchens} loading={place === null} />
          {place !== null ? <RewardsNearYou city={location.city ?? city} lat={location.lat} lng={location.lng} /> : null}
        </>
      )}
    </div>
  );
}

// ── the four home tiles ─────────────────────────────────────────────────────────────────────────────────────────

function HomeTiles({ t, landing, kitchens }: { t: T; landing: Landing | undefined; kitchens: readonly Kitchen[] | undefined }) {
  const { locale } = useLocale();
  const name = (tile: CatalogTile) => (locale === 'fr' ? tile.fr : tile.en);
  const pick = <X extends CatalogTile>(all: readonly X[], keys: string[]) => keys.map(k => all.find(x => x.key === k)).filter((x): x is X => !!x);
  const items = (tiles: readonly CatalogTile[], picture: (tile: CatalogTile) => ReactNode): PictureGridItem[] =>
    tiles.map(tile => ({ key: tile.key, href: tile.href, label: name(tile), picture: picture(tile) }));
  const shop = items(pick(DEPARTMENTS, SHOP_TILES), d => <Scene slot={[departmentSlot(d.href.split('/').pop()), 'banner/shop']} subject={d.key} crop="square" sizes="(min-width: 900px) 9vw, 22vw" />);
  const services = items(pick(SERVICE_CATEGORIES, SERVICE_TILES), s => <Scene slot={[categorySlot(s.ids[0]), 'banner/services']} subject={s.ids[0]!.split('.').pop()!} crop="square" sizes="(min-width: 900px) 9vw, 22vw" />);
  const food = pick(CUISINES, FOOD_TILES).map(c => ({ key: c.key, href: c.href, label: CUISINE_NAMES[c.key]?.[locale] ?? name(c), picture: <Scene slot={[cuisineSlot(c.key), 'banner/food']} subject={c.key} crop="square" sizes="(min-width: 900px) 9vw, 22vw" /> }));
  const open = kitchens?.filter(k => k.open).length;
  return (
    <PanelTiles className="nl-home-tiles">
      <PanelTile title={t('tileShop')} more={{ href: '/shop', label: t('tileShopMore') }}><PictureGrid items={shop} /></PanelTile>
      <PanelTile title={t('tileServices')} more={{ href: '/services', label: t('tileServicesMore') }}><PictureGrid items={services} /></PanelTile>
      <PanelTile title={t('tileFood')} more={{ href: '/food', label: open ? t('tileFoodMore', { count: open }) : t('tileFoodMoreAll') }}><PictureGrid items={food} /></PanelTile>
      <DealTile t={t} landing={landing} />
    </PanelTiles>
  );
}

/**
 * The deal of the day: the first of the landing's popular products whose offer has an earlier price (the product page's
 * `compareAtCents`; search and the landing carry none), else the most popular product, titled "Popular today".
 */
function DealTile({ t, landing }: { t: T; landing: Landing | undefined }) {
  const { locale } = useLocale();
  const zone = useZone(landing?.market);
  const candidates = (landing?.popular ?? []).slice(0, 3);
  const pages = useQueries({ queries: candidates.map(p => ({ ...productQuery(p.productId, landing?.market, locale), enabled: !!landing })) });
  const settled = pages.every(q => !q.isPending);
  const dealIndex = pages.findIndex(q => q.data?.offers.some(o => o.compareAtCents != null && o.compareAtCents > o.priceCents));
  const deal = dealIndex >= 0 ? candidates[dealIndex] : undefined;
  const offer = deal ? pages[dealIndex]!.data!.offers.find(o => o.compareAtCents != null && o.compareAtCents > o.priceCents) : undefined;
  const product: ProductCard | undefined = deal ?? (settled ? candidates[0] : undefined);
  const run = landing?.run;
  const when = run ? runWhen(run, zone) : null;
  return (
    <PanelTile title={deal ? t('tileDeal') : t('tilePopular')} className="nl-home-deal" more={product ? { href: `/products/${product.productId}`, label: t('dealMore') } : undefined}>
      {!product ? (
        <div aria-busy="true"><span className="nl-home-deal-pic" /><Skeleton width="80%" height={14} /><Skeleton width="40%" height={20} /></div>
      ) : (
        <SiteLink href={`/products/${product.productId}`} className="nl-home-deal-link">
          <span className="nl-home-deal-pic" aria-hidden>
            {product.imageUrl ? <img src={product.imageUrl} alt="" loading="lazy" decoding="async" />
              : <Scene slot={[categorySlot(product.categoryId)]} subject={product.name} crop="square" sizes="(min-width: 900px) 22vw, 44vw" />}
            {offer?.compareAtCents ? <DealBadge className="nl-home-deal-badge" percent={Math.round((1 - offer.priceCents / offer.compareAtCents) * 100)} /> : null}
          </span>
          <span className="nl-home-deal-name">{product.name}</span>
          <span className="nl-home-deal-meta">{product.shopName}</span>
          <Price cents={offer?.priceCents ?? product.priceCents} compareAtCents={offer?.compareAtCents} size="lg" />
          {run && when && product.run?.windowId === run.windowId ? (
            <span className="nl-home-deal-ends"><Clock size={14} aria-hidden />{t('dealOrderBy', { time: clock(run.orderBy, locale, zone), when, weekday: weekday(run.startsAt, locale, zone) })}</span>
          ) : null}
        </SiteLink>
      )}
    </PanelTile>
  );
}

// ── rails ────────────────────────────────────────────────────────────────────────────────────────────────────────

function ProductRail({ t, id, title, more, query, items, loading }: { t: T; id: string; title: string; more: { href: string; label: string }; query: UseQueryResult<SearchPage>; items: readonly SearchItem[] | undefined; loading: boolean }) {
  if (query.isSuccess && !items?.length) return null;
  return (
    <RailSection id={id} title={title} more={{ ...more, ariaLabel: t('seeMoreOf', { label: more.label, title }) }}>
      {query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
        : loading || query.isPending ? <RailSkeleton kind="product" label={t('loading')} />
          : <RailTrack size="product">{items!.slice(0, 14).map(item => <li key={item.id}><ItemCard item={item} level={3} /></li>)}</RailTrack>}
    </RailSection>
  );
}

function ServiceRail({ t, title, query, loading }: { t: T; title: string; query: UseQueryResult<SearchPage>; loading: boolean }) {
  const items = query.data?.items ?? [];
  if (query.isSuccess && items.length === 0) return null;
  return (
    <RailSection id="home-pros" title={title} more={{ href: '/services', label: t('seeServices'), ariaLabel: t('seeMoreOf', { label: t('seeServices'), title }) }}>
      {query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
        : loading || query.isPending ? <RailSkeleton kind="service" label={t('loading')} />
          : <RailTrack size="service">{items.slice(0, 10).map(item => <li key={item.id}><ItemCard item={item} level={3} /></li>)}</RailTrack>}
    </RailSection>
  );
}

function KitchenRail({ t, kitchens, loading }: { t: T; kitchens: UseQueryResult<{ items: Kitchen[] }>; loading: boolean }) {
  const { locale } = useLocale();
  const items = (kitchens.data?.items ?? []).filter(k => k.open).sort((a, b) => b.rating - a.rating);
  // no city known (the query never runs) or no kitchen open: no rail
  if ((kitchens.isSuccess && items.length === 0) || (!loading && kitchens.isPending && kitchens.fetchStatus === 'idle')) return null;
  const title = t('kitchensTonight');
  return (
    <RailSection id="home-kitchens" title={title} more={{ href: '/food', label: t('seeKitchens'), ariaLabel: t('seeMoreOf', { label: t('seeKitchens'), title }) }}>
      {kitchens.isError ? <ErrorState message={t('loadError')} onRetry={() => void kitchens.refetch()} />
        : loading || kitchens.isPending ? <RailSkeleton kind="service" label={t('loading')} />
          : (
            <RailTrack size="service">
              {items.slice(0, 10).map(k => (
                <li key={k.merchantId}>
                  <ServiceCard href={k.slug ? `/food/${k.slug}` : '/food'} layout="stack" level={3} name={k.name}
                    kind={k.cuisines.map(c => CUISINE_NAMES[c]?.[locale] ?? c).join(' · ') || undefined}
                    mark={<Scene slot={[cuisineSlot(k.cuisines[0]), 'banner/food']} subject={k.cuisines[0] ?? 'food'} crop="card" sizes="(min-width: 900px) 18vw, 44vw" />}
                    rating={k.reviews > 0 ? k.rating : null} reviewCount={k.reviews}
                    place={[t('eta', { from: k.etaFromMin, to: k.etaToMin }), k.priceLevel].filter(Boolean).join(' · ')} />
                </li>
              ))}
            </RailTrack>
          )}
    </RailSection>
  );
}

function RailSkeleton({ kind, label }: { kind: 'product' | 'service'; label: string }) {
  return (
    <div aria-busy="true">
      <span className="nl-sr-only" role="status">{label}</span>
      <ul className={`nl-rail-track nl-rail-${kind}`} aria-hidden>
        {Array.from({ length: kind === 'product' ? 7 : 5 }, (_, i) => (
          <li key={i}><div className="nl-home-skel"><span className={`nl-home-skel-pic nl-home-skel-${kind}`} /><Skeleton width="80%" height={14} /><Skeleton width="40%" height={20} /></div></li>
        ))}
      </ul>
    </div>
  );
}
