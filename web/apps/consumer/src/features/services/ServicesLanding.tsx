import { useSuspenseQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { EmptyState, Skeleton, useLocale } from '@northline/ui';
import { servicesLandingQuery, type ServiceGroup } from './api';
import { useServicesT } from './messages';
import { categoryName } from './taxonomy';
import { regionNames } from './region';
import { dyn } from './text';

/** design 06 `services`: every service group with its categories, each opening the category page. */
export function ServicesLanding() {
  const t = useServicesT();
  const { locale } = useLocale();
  const { data } = useSuspenseQuery(servicesLandingQuery(locale));
  return (
    <div className="nl-page nl-svc">
      <h1 className="nl-svc-title">{t('landingTitle')}</h1>
      <p className="nl-svc-sub">{data.provinces.length > 0 ? t('landingSub', { count: data.liveCategories, region: regionNames(data.provinces, locale) }) : t('landingSubNoRegion', { count: data.liveCategories })}</p>
      {data.groups.length === 0
        ? <EmptyState>{t('landingEmpty')}</EmptyState>
        : <div className="nl-svc-groups">{data.groups.map(g => <Group key={g.id} group={g} />)}</div>}
    </div>
  );
}

function Group({ group }: { group: ServiceGroup }) {
  const t = useServicesT();
  const { locale } = useLocale();
  const name = categoryName(group.key, group.names, locale);
  const line = dyn(t, `group_${group.key.replace(/-/g, '_')}`, group.note ?? '');
  const id = `svc-group-${group.key}`;
  return (
    <section aria-labelledby={id}>
      <h2 id={id} className="nl-svc-group-title" lang={name.lang}>{name.text}</h2>
      {line ? <div className="nl-svc-group-kind">{line}</div> : null}
      <ul className="nl-svc-chips">
        {group.items.map(item => {
          const n = categoryName(item.slug, item.names, locale);
          return (
            <li key={item.slug}>
              <Link to="/services/$category" params={{ category: item.slug }} className="nl-svc-chip" lang={n.lang}>{n.text}</Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** Loading: the title and a few groups of chips. */
export function ServicesLandingSkeleton() {
  return (
    <div className="nl-page nl-svc" aria-busy="true">
      <Skeleton width={200} height={36} />
      <Skeleton width="min(520px, 90%)" height={14} style={{ marginTop: 10 }} />
      <div className="nl-svc-groups">
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i}>
            <Skeleton width={160} height={22} />
            <div className="nl-svc-chips">{Array.from({ length: 6 }, (_, j) => <Skeleton key={j} width={90 + (j % 3) * 20} height={40} />)}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
