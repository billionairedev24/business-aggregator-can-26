import type { ReactNode } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { MapPin, SealCheck } from '@phosphor-icons/react';
import { EmptyState, ErrorState, SiteLink, Skeleton, useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { marketOf, railQuery, type Place, type SearchPage } from '../search/api';
import { ItemCard } from '../search/ItemCard';
import { SiteSearch } from '../search/SiteSearch';
import { Art } from '../art/Art';
import { RailSection, RailTrack } from '../pages/Rail';
import { Tile } from '../pages/Tile';
import { DEPARTMENTS, SERVICE_CATEGORIES, type Tile as CatalogTile } from './catalog';
import { useHomeT } from './messages';

type T = ReturnType<typeof useHomeT>;

const SERVICE_TILES = ['mechanic', 'cleaning', 'plumber', 'barber', 'tutor', 'movers'];
const SHOP_TILES = ['groceries', 'bakery', 'butcher', 'produce', 'pharmacy', 'gifts'];
const QUICK = ['mechanic', 'cleaning', 'bakery', 'groceries'];

/**
 * Home (light & photo-first, phase B; redesign § 1.3 behaviour): a hero that leads with the search and the place —
 * the field, "Near {place} · Change" (the location dialog) and a few popular entries — beside an illustrated collage;
 * then illustrated tiles for services and the shop's departments; then sideways rails of real listings, products and
 * services, where the visitor is; then the sellers' banner. No carousel, no arrows over content. The greeting, the
 * "what do you need" question, live counts, promises, tier tags and "Your week" stay gone (§ 3.2). The server renders
 * the hero and the tiles (the same for everyone); the rails load in the browser once the place is known.
 */
export function HomeScreen() {
  const t = useHomeT();
  const { location, chooser } = useDeliveryLocation();
  const place: Place | null = location.status === 'locating' ? null
    : { market: marketOf(location.province), lat: location.lat, lng: location.lng };
  const { locale } = useLocale();
  const products = useQuery({ ...railQuery('product', place ?? {}, locale), enabled: place !== null });
  const services = useQuery({ ...railQuery('service', place ?? {}, locale), enabled: place !== null });
  const city = location.label?.split(',')[0]?.trim() || location.city;
  const empty = products.data?.items.length === 0 && services.data?.items.length === 0;
  const name = (tile: CatalogTile) => (locale === 'fr' ? tile.fr : tile.en);
  const pick = <X extends CatalogTile>(all: readonly X[], keys: string[]) => keys.map(k => all.find(x => x.key === k)).filter((x): x is X => !!x);

  return (
    <div className="nl-home">
      <section className="nl-home-hero" aria-labelledby="home-title">
        <div className="nl-home-hero-copy">
          <h1 id="home-title" className="nl-home-title">{t('heroTitle')}</h1>
          <p className="nl-home-sub">{t('heroSub')}</p>
          <div className="nl-home-search"><SiteSearch variant="hero" placeholder={t('heroSearch')} label={t('heroSearchLabel')} /></div>
          <p className="nl-home-where">
            <MapPin size={20} weight="fill" aria-hidden />
            {location.status === 'locating' ? <Skeleton width={160} height={20} />
              : city ? <span>{t('near', { place: city })}</span> : <span>{t('noPlace')}</span>}
            <button type="button" className="btn btn-link nl-home-where-btn" onClick={chooser.show}>{city ? t('changePlace') : t('setPlace')}</button>
          </p>
          <div className="nl-home-quick">
            <span className="nl-home-quick-label" id="home-quick">{t('popular')}</span>
            <ul aria-labelledby="home-quick">
              {[...pick(SERVICE_CATEGORIES, QUICK), ...pick(DEPARTMENTS, QUICK)].map(tile => (
                <li key={tile.key}><SiteLink href={tile.href} className="nl-chip nl-home-chip">{name(tile)}</SiteLink></li>
              ))}
            </ul>
          </div>
        </div>
        <div className="nl-home-collage" aria-hidden>
          <span className="nl-home-pic nl-home-pic-a"><Art subject="mobile-mechanic" tone="sage" /></span>
          <span className="nl-home-pic nl-home-pic-b"><Art subject="bakery" tone="ember" /></span>
          <span className="nl-home-pic nl-home-pic-c"><Art subject="house-cleaning" tone="clay" /></span>
          <span className="nl-home-badge"><SealCheck size={22} weight="fill" />{t('badge')}</span>
        </div>
      </section>

      <div className="nl-home-body">
        <TileSection t={t} id="home-services" title={t('tileServices')} more={{ href: '/services', label: t('allServices') }}
          tiles={pick(SERVICE_CATEGORIES, SERVICE_TILES).map(s => ({ key: s.key, href: s.href, name: name(s), subject: s.ids[0]!.split('.').pop()! }))} />
        <TileSection t={t} id="home-shop" title={t('tileShop')} more={{ href: '/shop', label: t('allDepartments') }}
          tiles={pick(DEPARTMENTS, SHOP_TILES).map(d => ({ key: d.key, href: d.href, name: name(d), subject: d.key }))} />
      </div>

      {empty ? (
        <section className="nl-section">
          <EmptyState title={city ? t('emptyCity', { city }) : t('emptyAnywhere')} action={<button type="button" className="btn btn-primary" onClick={chooser.show}>{t('changeLocation')}</button>}>{t('emptyHint')}</EmptyState>
        </section>
      ) : (
        <>
          <Rail t={t} id="home-products" title={city ? t('productsNear', { city }) : t('productsAnywhere')} more={{ href: '/shop' }} query={products} loading={place === null} kind="product" />
          <Rail t={t} id="home-services-rail" title={city ? t('servicesNear', { city }) : t('servicesAnywhere')} more={{ href: '/services' }} query={services} loading={place === null} kind="service" />
        </>
      )}

      <section className="nl-home-sell" aria-labelledby="home-sell">
        <div className="nl-home-sell-copy">
          <h2 id="home-sell" className="nl-home-sell-title">{t('sellTitle')}</h2>
          <p className="nl-home-sell-text">{t('sellText')}</p>
          <SiteLink href="/sell" className="btn btn-primary btn-lg">{t('sellCta')}</SiteLink>
        </div>
        <span className="nl-home-sell-pic" aria-hidden><Art subject="storefront" tone="sage" fit="contain" /></span>
      </section>
    </div>
  );
}

// ── tiles ────────────────────────────────────────────────────────────────────────────────────────────────────────

interface TileItem { key: string; href: string; name: string; subject: string }

/** A titled row of illustrated entry tiles (six; a sideways row on phones). */
function TileSection({ t, id, title, more, tiles }: { t: T; id: string; title: string; more: { href: string; label: string }; tiles: readonly TileItem[] }) {
  return (
    <section className="nl-home-tiles" aria-labelledby={id}>
      <div className="nl-section-head">
        <h2 id={id} className="nl-section-title">{title}</h2>
        <SiteLink href={more.href} className="nl-section-more" aria-label={t('seeMoreOf', { title })}>{more.label}</SiteLink>
      </div>
      <ul className="nl-home-tile-row">
        {tiles.map(tile => <li key={tile.key}><Tile href={tile.href} name={tile.name} subject={tile.subject} /></li>)}
      </ul>
    </section>
  );
}

// ── rails ────────────────────────────────────────────────────────────────────────────────────────────────────────

type RailQuery = UseQueryResult<SearchPage>;

function Rail({ t, id, title, more, query, loading, kind }: { t: T; id: string; title: string; more: { href: string }; query: RailQuery; loading: boolean; kind: 'product' | 'service' }) {
  const items = query.data?.items ?? [];
  if (query.isSuccess && items.length === 0) return null;
  return (
    <RailSection id={id} title={title} more={{ href: more.href, label: t('seeMore'), ariaLabel: t('seeMoreOf', { title }) }}>
      {query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
        : loading || query.isPending ? <RailSkeleton kind={kind} label={t('loading')} />
          : (
            <RailTrack size={kind}>
              {items.slice(0, kind === 'product' ? 12 : 8).map(item => <li key={item.id}><ItemCard item={item} variant={kind === 'product' ? 'rail' : 'grid'} level={3} /></li>)}
            </RailTrack>
          )}
    </RailSection>
  );
}

function RailSkeleton({ kind, label }: { kind: 'product' | 'service'; label: string }): ReactNode {
  return (
    <div aria-busy="true">
      <span className="nl-sr-only" role="status">{label}</span>
      <ul className={`nl-rail-track nl-rail-${kind}`} aria-hidden>
        {Array.from({ length: kind === 'product' ? 6 : 4 }, (_, i) => (
          <li key={i}>{kind === 'product'
            ? <div><Skeleton height="auto" style={{ aspectRatio: '1 / 1' }} radius="var(--radius-lg)" /><Skeleton width="80%" style={{ marginTop: 8 }} /><Skeleton width="40%" height={22} style={{ marginTop: 6 }} /></div>
            : <Skeleton height={300} radius="var(--radius-lg)" />}</li>
        ))}
      </ul>
    </div>
  );
}
