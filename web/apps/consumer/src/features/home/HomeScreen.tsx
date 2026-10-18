import { useState, type ReactNode } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import {
  Baby, Basket, Bread, Broom, CalendarCheck, CaretLeft, CaretRight, Car, Carrot, Drop, Gift, GraduationCap, Hammer, HouseLine,
  Knife, Martini, Pill, Scissors, Snowflake, Storefront, Truck, TShirt, Wrench, type Icon,
} from '@phosphor-icons/react';
import { EmptyState, ErrorState, SiteLink, Skeleton, useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { marketOf, railQuery, type Place, type SearchPage } from '../search/api';
import { ItemCard } from '../search/ItemCard';
import { DEPARTMENTS, SERVICE_CATEGORIES, type Tile } from './catalog';
import { useHomeT } from './messages';

type T = ReturnType<typeof useHomeT>;

/**
 * Home (redesign § 1.3 "Home", AT Shop:182–272; S-46 behaviour): a full-bleed hero carousel, a row of tiles overlapping
 * it (services, shop departments, groceries — food is a department here, not its own vertical, § 3.7 — and jobs
 * around the home), then rails of real listings — products and services — where the visitor is. The greeting, the
 * "what do you need" question, the live counts, the promises, the tier tags and "Your week" are gone (§ 3.2). The
 * server renders the hero and the tiles (the same for everyone); the rails load in the browser once the place is known.
 */
export function HomeScreen() {
  const t = useHomeT();
  const { location } = useDeliveryLocation();
  const place: Place | null = location.status === 'locating' ? null
    : { market: marketOf(location.province), lat: location.lat, lng: location.lng };
  const { locale } = useLocale();
  const products = useQuery({ ...railQuery('product', place ?? {}, locale), enabled: place !== null });
  const services = useQuery({ ...railQuery('service', place ?? {}, locale), enabled: place !== null });
  const city = location.label?.split(',')[0]?.trim() || location.city;
  const empty = products.data?.items.length === 0 && services.data?.items.length === 0;

  return (
    <div className="nl-home">
      <Hero t={t} />
      <div className="nl-page nl-home-body">
        <ul className="nl-home-tiles" aria-label={t('tilesLabel')}>
          <TileCard t={t} title={t('tileServices')} more={{ href: '/services' }} items={SERVICE_CATEGORIES.filter(s => ['mechanic', 'plumber', 'tutor', 'barber'].includes(s.key))} />
          <TileCard t={t} title={t('tileShop')} more={{ href: '/shop' }} items={DEPARTMENTS.filter(d => ['clothing', 'pharmacy', 'hardware', 'gifts'].includes(d.key))} />
          <TileCard t={t} title={t('tileGroceries')} more={{ href: '/shop/groceries' }} items={DEPARTMENTS.filter(d => ['groceries', 'bakery', 'butcher', 'produce'].includes(d.key))} />
          <TileCard t={t} title={t('tileHome')} more={{ href: '/services' }} items={SERVICE_CATEGORIES.filter(s => ['cleaning', 'snow', 'movers', 'realestate'].includes(s.key))} />
        </ul>

        {empty ? (
          <section className="nl-home-rail">
            <EmptyState title={city ? t('emptyCity', { city }) : t('emptyAnywhere')} action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('emptyHint')}</EmptyState>
          </section>
        ) : (
          <>
            <Rail t={t} id="home-products" title={city ? t('productsNear', { city }) : t('productsAnywhere')} more={{ href: '/shop' }} query={products} loading={place === null} kind="product" />
            <Rail t={t} id="home-services" title={city ? t('servicesNear', { city }) : t('servicesAnywhere')} more={{ href: '/services' }} query={services} loading={place === null} kind="service" />
          </>
        )}

        <section className="nl-home-banner" aria-labelledby="home-sell">
          <div>
            <h2 id="home-sell" className="nl-home-banner-title">{t('sellTitle')}</h2>
            <p className="nl-home-banner-text">{t('sellText')}</p>
          </div>
          <SiteLink href="/sell" className="btn btn-dark">{t('sellCta')}</SiteLink>
        </section>
      </div>
    </div>
  );
}

// ── hero carousel ────────────────────────────────────────────────────────────────────────────────────────────────

const SLIDES = [
  { key: 'services', href: '/services', icon: CalendarCheck },
  { key: 'shop', href: '/shop', icon: Storefront },
  { key: 'quotes', href: '/services', icon: Hammer },
] as const;

/** A carousel the visitor moves (it never turns by itself, WCAG 2.2.2): arrows 44×88, 8px pill dots, one slide shown. */
function Hero({ t }: { t: T }) {
  const [i, setI] = useState(0);
  const slide = SLIDES[i]!;
  const go = (n: number) => setI((n + SLIDES.length) % SLIDES.length);
  const Art = slide.icon;
  return (
    <section className="nl-home-hero" aria-roledescription={t('carousel')} aria-label={t('featured')}>
      <div className="nl-home-hero-inner">
        <button type="button" className="nl-home-hero-arrow nl-home-hero-prev" aria-label={t('prevSlide')} onClick={() => go(i - 1)}><CaretLeft size={28} aria-hidden /></button>
        <div className="nl-home-slide" role="group" aria-roledescription={t('slide')} aria-label={t('slideOf', { n: i + 1, total: SLIDES.length })}>
          <div className="nl-home-slide-text">
            <span className="nl-home-kicker">{t(`kicker_${slide.key}`)}</span>
            <h1 className="nl-home-title">{t(`title_${slide.key}`)}</h1>
            <p className="nl-home-sub">{t(`sub_${slide.key}`)}</p>
            <SiteLink href={slide.href} className="btn btn-accent btn-lg">{t(`cta_${slide.key}`)}</SiteLink>
          </div>
          <div className="nl-home-slide-art" aria-hidden><Art size={120} weight="fill" /></div>
        </div>
        <button type="button" className="nl-home-hero-arrow nl-home-hero-next" aria-label={t('nextSlide')} onClick={() => go(i + 1)}><CaretRight size={28} aria-hidden /></button>
      </div>
      <div className="nl-home-dots" role="group" aria-label={t('slides')}>
        {SLIDES.map((s, n) => (
          <button key={s.key} type="button" className="nl-home-dot" aria-label={t('slideOf', { n: n + 1, total: SLIDES.length })} aria-current={n === i ? 'true' : undefined} onClick={() => setI(n)}><span aria-hidden /></button>
        ))}
      </div>
    </section>
  );
}

// ── tiles ────────────────────────────────────────────────────────────────────────────────────────────────────────

const ICONS: Record<string, Icon> = {
  mechanic: Car, cleaning: Broom, bar: Martini, barber: Scissors, realestate: HouseLine, plumber: Drop, tutor: GraduationCap, movers: Truck, snow: Snowflake,
  groceries: Basket, butcher: Knife, bakery: Bread, produce: Carrot, clothing: TShirt, pharmacy: Pill, hardware: Wrench, kids: Baby, gifts: Gift,
};

/** A white card with shadow-sm, an xl title, a 2×2 grid of pictures with xs captions and "See more" (AT Shop:200). */
function TileCard({ t, title, items, more }: { t: T; title: string; items: readonly Tile[]; more: { href: string } }) {
  const { locale } = useLocale();
  return (
    <li className="nl-home-tile">
      <h2 className="nl-home-tile-title">{title}</h2>
      <ul className="nl-home-tile-grid">
        {items.map(tile => {
          const I = ICONS[tile.key] ?? Storefront;
          return (
            <li key={tile.key}>
              <SiteLink href={tile.href} className="nl-home-tile-item">
                <span className="nl-home-tile-pic" aria-hidden><I size={40} /></span>
                <span className="nl-home-tile-cap">{locale === 'fr' ? tile.fr : tile.en}</span>
              </SiteLink>
            </li>
          );
        })}
      </ul>
      <SiteLink href={more.href} className="nl-home-more" aria-label={t('seeMoreOf', { title })}>{t('seeMore')}</SiteLink>
    </li>
  );
}

// ── rails ────────────────────────────────────────────────────────────────────────────────────────────────────────

type RailQuery = UseQueryResult<SearchPage>;

function Rail({ t, id, title, more, query, loading, kind }: { t: T; id: string; title: string; more: { href: string }; query: RailQuery; loading: boolean; kind: 'product' | 'service' }) {
  const items = query.data?.items ?? [];
  if (query.isSuccess && items.length === 0) return null;
  return (
    <section className="nl-home-rail" aria-labelledby={id}>
      <div className="nl-home-rail-head">
        <h2 id={id} className="nl-home-rail-title">{title}</h2>
        <SiteLink href={more.href} className="nl-home-more" aria-label={t('seeMoreOf', { title })}>{t('seeMore')}</SiteLink>
      </div>
      {query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
        : loading || query.isPending ? <RailSkeleton kind={kind} label={t('loading')} />
          : (
            <ul className={kind === 'product' ? 'nl-card-grid nl-card-grid-rail' : 'nl-card-grid nl-card-grid-services'}>
              {items.slice(0, kind === 'product' ? 12 : 6).map(item => <li key={item.id}><ItemCard item={item} variant={kind === 'product' ? 'rail' : 'grid'} level={3} /></li>)}
            </ul>
          )}
    </section>
  );
}

function RailSkeleton({ kind, label }: { kind: 'product' | 'service'; label: string }): ReactNode {
  return (
    <div aria-busy="true">
      <span className="nl-sr-only" role="status">{label}</span>
      <ul className={kind === 'product' ? 'nl-card-grid nl-card-grid-rail' : 'nl-card-grid nl-card-grid-services'} aria-hidden>
        {Array.from({ length: kind === 'product' ? 6 : 3 }, (_, i) => (
          <li key={i}>{kind === 'product'
            ? <><Skeleton height="auto" style={{ aspectRatio: '1 / 1' }} radius="var(--radius-sm)" /><Skeleton width="80%" style={{ marginTop: 8 }} /><Skeleton width="40%" height={22} style={{ marginTop: 6 }} /></>
            : <Skeleton height={128} radius="var(--radius-md)" />}</li>
        ))}
      </ul>
    </div>
  );
}
