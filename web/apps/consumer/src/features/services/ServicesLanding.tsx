import { useSuspenseQuery } from '@tanstack/react-query';
import { EmptyState, Skeleton, useLocale } from '@northline/ui';
import { serviceSlot } from '@northline/media';
import { Scene } from '../art/Scene';
import { RailSection, RailTrack } from '../pages/Rail';
import { Tile } from '../pages/Tile';
import { servicesLandingQuery, type ServiceGroup, type ServiceItem } from './api';
import { useServicesT } from './messages';
import { categoryName } from './taxonomy';
import { dyn } from './text';

/**
 * Services landing (light & photo-first, phase B; design 06 `services` behaviour): a band with what Services promises,
 * the categories with the most providers first, then every group as a sideways row of category photo tiles (no
 * wall of chips), each tile opening the category page. Region-neutral: the count line names no province.
 */
export function ServicesLanding() {
  const t = useServicesT();
  const { locale } = useLocale();
  const { data } = useSuspenseQuery(servicesLandingQuery(locale));
  const popular = data.groups.flatMap(g => g.items).filter(i => i.providers > 0).sort((a, b) => b.providers - a.providers).slice(0, 10);
  return (
    <div className="nl-page nl-svc">
      <section className="nl-band" aria-labelledby="svc-title">
        <div className="nl-band-copy">
          <span className="nl-band-kicker">{t('landingKicker', { count: data.liveCategories })}</span>
          <h1 id="svc-title" className="nl-band-title">{t('landingTitle')}</h1>
          <p className="nl-band-lede">{t('landingLede')}</p>
        </div>
        <span className="nl-band-pic" aria-hidden><Scene slot="banner/services" subject="mobile-mechanic" tone="sage" fit="contain" sizes="(min-width: 960px) 45vw, 100vw" priority /></span>
      </section>
      {data.groups.length === 0
        ? <div className="nl-section"><EmptyState>{t('landingEmpty')}</EmptyState></div>
        : (
          <>
            {popular.length >= 4 ? (
              <RailSection id="svc-popular" title={t('popularTitle')}>
                <RailTrack size="tile">{popular.map(item => <li key={item.slug}><CategoryTile item={item} /></li>)}</RailTrack>
              </RailSection>
            ) : null}
            {data.groups.map(g => <Group key={g.id} group={g} />)}
          </>
        )}
    </div>
  );
}

function CategoryTile({ item }: { item: ServiceItem }) {
  const t = useServicesT();
  const { locale } = useLocale();
  const n = categoryName(item.slug, item.names, locale);
  return <Tile href={`/services/${item.slug}`} name={n.text} lang={n.lang} slot={serviceSlot(item.slug) ?? 'banner/services'} subject={item.slug} meta={t('tilePros', { count: item.providers })} />;
}

function Group({ group }: { group: ServiceGroup }) {
  const t = useServicesT();
  const { locale } = useLocale();
  const name = categoryName(group.key, group.names, locale);
  const line = dyn(t, `group_${group.key.replace(/-/g, '_')}`, group.note ?? '');
  return (
    <RailSection id={`svc-group-${group.key}`} title={name.text} lede={line || undefined}>
      <RailTrack size="tile">{group.items.map(item => <li key={item.slug}><CategoryTile item={item} /></li>)}</RailTrack>
    </RailSection>
  );
}

/** Loading: the band and a few rows of tiles. */
export function ServicesLandingSkeleton() {
  return (
    <div className="nl-page nl-svc" aria-busy="true">
      <Skeleton height={260} radius="var(--radius-lg)" style={{ marginTop: 24 }} />
      {Array.from({ length: 3 }, (_, i) => (
        <div key={i} className="nl-rail">
          <Skeleton width={200} height={28} />
          <div className="nl-rail-track nl-rail-tile" style={{ marginTop: 16 }}>
            {Array.from({ length: 6 }, (_, j) => <Skeleton key={j} height={160} radius="var(--radius-lg)" />)}
          </div>
        </div>
      ))}
    </div>
  );
}
