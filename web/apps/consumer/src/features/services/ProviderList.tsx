import { useMemo, useState } from 'react';
import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { BrandMark, EmptyState, ErrorState, Skeleton, TIME_ZONE, useLocale, type Locale } from '@northline/ui';
import { useDeliveryLocation, type DeliveryLocation } from '../location/useDeliveryLocation';
import { providersQuery, serviceCategoryQuery, type ProviderCard, type ProviderPlace, type ServiceKind } from './api';
import { nextAvailable, percent, price, rating } from './format';
import { useServicesT } from './messages';
import { categoryName, familyOf, nouns } from './taxonomy';

export type ProviderFilter = 'master' | 'instant' | 'today' | 'under80';
export const FILTERS: readonly ProviderFilter[] = ['master', 'instant', 'today', 'under80'];

/**
 * Where to look: device (or saved) coordinates when there are some, else the city. The default market and the CDN's
 * IP guess are a city, not a place, so they never pin the list to one neighbourhood.
 */
export function placeOf(location: DeliveryLocation): ProviderPlace | null {
  if (location.status === 'locating') return null;
  const precise = (location.source === 'device' || location.source === 'saved') && location.lat !== undefined && location.lng !== undefined;
  return precise ? { lat: location.lat, lng: location.lng, city: location.city } : { city: location.city };
}

const sameDay = (iso: string | null | undefined, now: Date) =>
  !!iso && new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE }).format(new Date(iso))
    === new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE }).format(now);

export function applyFilters(items: ProviderCard[], filters: ReadonlySet<ProviderFilter>, now = new Date()): ProviderCard[] {
  return items.filter(p => (!filters.has('master') || p.tier === 'master')
    && (!filters.has('instant') || p.instantBook)
    && (!filters.has('today') || sameDay(p.nextAvailable, now))
    && (!filters.has('under80') || (p.fromCents !== null && p.fromCents !== undefined && p.fromCents < 8000)));
}

/** design 06 `providers`: the category's verified providers covering the customer's location, most trusted first. */
export function ProviderList({ slug }: { slug: string }) {
  const t = useServicesT();
  const { locale } = useLocale();
  const { location } = useDeliveryLocation();
  const { data: category } = useSuspenseQuery(serviceCategoryQuery(slug, locale));
  const place = placeOf(location);
  const list = useQuery({ ...providersQuery(slug, place ?? {}, locale), enabled: place !== null });
  const [filters, setFilters] = useState<ReadonlySet<ProviderFilter>>(new Set());
  const name = categoryName(category.slug, category.names, locale);
  const noun = nouns(familyOf(category.kind, category.slug, category.vehicle), name.text, locale).heading;
  const shown = useMemo(() => applyFilters(list.data?.items ?? [], filters), [list.data, filters]);
  const toggle = (f: ProviderFilter) => setFilters(prev => { const next = new Set(prev); if (next.has(f)) next.delete(f); else next.add(f); return next; });
  const area = list.data?.area ?? list.data?.city ?? location.city ?? '';

  return (
    <div className="nl-page nl-svc nl-svc-providers">
      <aside className="nl-svc-filters" aria-labelledby="svc-filter">
        <div id="svc-filter" className="nl-svc-kicker">{t('filter')}</div>
        <div className="nl-svc-filter-list" role="group" aria-labelledby="svc-filter">
          {FILTERS.map(f => (
            <label key={f} className="nl-svc-filter">
              <input type="checkbox" checked={filters.has(f)} onChange={() => toggle(f)} />
              <span>{t(`f_${f}`)}</span>
            </label>
          ))}
        </div>
      </aside>
      <div className="nl-svc-list">
        <h1 className="nl-svc-list-title" lang={name.lang && noun === name.text ? name.lang : undefined}>
          {list.data ? heading(t, category.kind, noun, area, shown.length) : noun}
        </h1>
        <div className="nl-svc-muted nl-svc-sortnote">{t('sortNote')}</div>
        {place === null || list.isPending
          ? <ProviderRowsSkeleton label={t('locating')} />
          : list.isError
            ? <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
            : shown.length === 0
              ? (list.data.items.length === 0
                ? <EmptyState action={<Link to="/services" className="btn btn-secondary">{t('listEmptyAction')}</Link>}>{t('listEmpty', { area })}</EmptyState>
                : <EmptyState action={<button type="button" className="btn btn-secondary" onClick={() => setFilters(new Set())}>{t('clearFilters')}</button>}>{t('filteredEmpty')}</EmptyState>)
              : <ul className="nl-svc-rows">{shown.map(p => <li key={p.merchantId}><ProviderRow provider={p} locale={locale} /></li>)}</ul>}
      </div>
    </div>
  );
}

function heading(t: ReturnType<typeof useServicesT>, kind: ServiceKind, noun: string, area: string, count: number) {
  if (kind === 'appointment') return t('headingNear', { noun, area, count });
  if (kind === 'consult') return t('headingServing', { noun, area, count });
  return t('headingCome', { noun, area, count });
}

function ProviderRow({ provider: p, locale }: { provider: ProviderCard; locale: Locale }) {
  const t = useServicesT();
  const meta = [
    p.reviewCount > 0 ? t('rating', { rating: rating(p.rating, locale), count: p.reviewCount }) : t('newProvider'),
    p.onTimePct !== null && p.onTimePct !== undefined ? t('onTime', { pct: percent(p.onTimePct, locale) }) : null,
  ].filter(Boolean).join(' · ');
  const tone = p.tier === 'master' ? 'tag-accent' : p.tier === 'trusted' ? 'tag-accent-2' : 'tag-neutral';
  return (
    <Link to="/providers/$slug" params={{ slug: p.slug }} className="nl-svc-row">
      <BrandMark name={p.name} color={p.brandColor} size={64} />
      <span className="nl-svc-row-body">
        <span className="nl-svc-row-name"><span className="nl-svc-row-title">{p.name}</span><span className={`tag ${tone} nl-svc-tier`}>{t(`tier_${p.tier === 'master' || p.tier === 'trusted' ? p.tier : 'registered'}`)}</span></span>
        <span className="nl-svc-row-meta">{meta}</span>
        {p.blurb ? <span className="nl-svc-row-blurb">{p.blurb}</span> : null}
      </span>
      <span className="nl-svc-row-side">
        <span className="nl-svc-row-price">{price(t, locale, p.pricingMode, p.fromCents, true)}</span>
        <span className="nl-svc-row-avail">{nextAvailable(t, locale, p.nextAvailable)}</span>
      </span>
    </Link>
  );
}

function ProviderRowsSkeleton({ label }: { label: string }) {
  return (
    <div aria-busy="true">
      <span className="nl-sr-only" role="status">{label}</span>
      {Array.from({ length: 4 }, (_, i) => (
        <div key={i} className="nl-svc-row nl-svc-row-skel">
          <Skeleton width={64} height={64} radius="var(--radius-md)" />
          <span className="nl-svc-row-body"><Skeleton width="50%" height={18} /><Skeleton width="70%" height={12} style={{ marginTop: 8 }} /></span>
          <Skeleton width={70} height={16} />
        </div>
      ))}
    </div>
  );
}
