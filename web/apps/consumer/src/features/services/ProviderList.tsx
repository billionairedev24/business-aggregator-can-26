import { useMemo, useState } from 'react';
import { useZone } from '../location/regions';
import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { BrandMark, EmptyState, ErrorState, ServiceCard, Skeleton, useLocale, type Locale, timeZone } from '@northline/ui';
import { useDeliveryLocation, type DeliveryLocation } from '../location/useDeliveryLocation';
import { providersQuery, serviceCategoryQuery, type ProviderCard, type ProviderPlace, type ServiceKind } from './api';
import { nextAvailable, percent, price } from './format';
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

const sameDay = (iso: string | null | undefined, now: Date, zone: string) =>
  !!iso && new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(new Date(iso))
    === new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(now);

/** @param zone the visitor's market's zone ("available today" is today there); the platform zone by default */
export function applyFilters(items: ProviderCard[], filters: ReadonlySet<ProviderFilter>, now = new Date(), zone: string = timeZone()): ProviderCard[] {
  return items.filter(p => (!filters.has('master') || p.tier === 'master')
    && (!filters.has('instant') || p.instantBook)
    && (!filters.has('today') || sameDay(p.nextAvailable, now, zone))
    && (!filters.has('under80') || (p.fromCents !== null && p.fromCents !== undefined && p.fromCents < 8000)));
}

/**
 * The category's verified providers covering the customer's location, most trusted first (design 06 `providers`; the
 * redesign's service-card list, § 1.3 / AT Shop:891): filter chips (32px pills) above a list of ServiceCards — stars,
 * the on-time rate and the next opening instead of tier tags (§ 3.13), the verified seal for vetted providers.
 */
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
  const zone = useZone();
  const shown = useMemo(() => applyFilters(list.data?.items ?? [], filters, new Date(), zone), [list.data, filters, zone]);
  const toggle = (f: ProviderFilter) => setFilters(prev => { const next = new Set(prev); if (next.has(f)) next.delete(f); else next.add(f); return next; });
  const area = list.data?.area ?? list.data?.city ?? location.city ?? '';

  return (
    <div className="nl-page nl-svc nl-svc-providers">
      <nav aria-label={t('breadcrumb')} className="nl-svc-crumbs">
        <Link to="/services">{t('crumbServices')}</Link> › <Link to="/services/$category" params={{ category: category.slug }} lang={name.lang}>{name.text}</Link> › <span aria-current="page">{t('providersCrumb')}</span>
      </nav>
      <h1 className="nl-svc-list-title" lang={name.lang && noun === name.text ? name.lang : undefined}>
        {list.data ? heading(t, category.kind, noun, area, shown.length) : noun}
      </h1>
      <div className="nl-svc-muted nl-svc-sortnote">{t('sortNote')}</div>
      <div className="nl-chips nl-svc-filter-chips" role="group" aria-label={t('filter')}>
        {FILTERS.map(f => <button key={f} type="button" className="nl-chip" aria-pressed={filters.has(f)} onClick={() => toggle(f)}>{t(`f_${f}`)}</button>)}
      </div>
      {place === null || list.isPending
        ? <ProviderRowsSkeleton label={t('locating')} />
        : list.isError
          ? <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
          : shown.length === 0
            ? (list.data.items.length === 0
              ? <EmptyState action={<Link to="/services" className="btn btn-secondary">{t('listEmptyAction')}</Link>}>{t('listEmpty', { area })}</EmptyState>
              : <EmptyState action={<button type="button" className="btn btn-secondary" onClick={() => setFilters(new Set())}>{t('clearFilters')}</button>}>{t('filteredEmpty')}</EmptyState>)
            : <ul className="nl-svc-rows">{shown.map(p => <li key={p.merchantId}><ProviderRow provider={p} locale={locale} kind={name} /></li>)}</ul>}
    </div>
  );
}

function heading(t: ReturnType<typeof useServicesT>, kind: ServiceKind, noun: string, area: string, count: number) {
  if (kind === 'appointment') return t('headingNear', { noun, area, count });
  if (kind === 'consult') return t('headingServing', { noun, area, count });
  return t('headingCome', { noun, area, count });
}

/** One provider as a ServiceCard (AT Shop:891): mark | the service, name, stars, on-time rate, next opening | "from" price. */
function ProviderRow({ provider: p, locale, kind }: { provider: ProviderCard; locale: Locale; kind: { text: string; lang?: 'en' } }) {
  const zone = useZone();
  const t = useServicesT();
  const quote = p.pricingMode === 'quote' || p.fromCents == null;
  return (
    <ServiceCard href={`/providers/${p.slug}`} name={p.name} kind={<span lang={kind.lang}>{kind.text}</span>}
      mark={<BrandMark name={p.name} color={p.brandColor} size={96} />}
      rating={p.reviewCount > 0 ? p.rating : null} reviewCount={p.reviewCount}
      place={p.onTimePct !== null && p.onTimePct !== undefined ? t('onTime', { pct: percent(p.onTimePct, locale) }) : undefined}
      note={nextAvailable(t, locale, p.nextAvailable, new Date(), zone)}
      priceCents={quote ? null : p.fromCents} priceText={quote ? price(t, locale, p.pricingMode, p.fromCents, true) : undefined}
      priceSuffix={p.pricingMode === 'hourly' ? t('hourSuffix') : undefined}
      verified={p.tier === 'master' || p.tier === 'trusted'} level={2} />
  );
}

function ProviderRowsSkeleton({ label }: { label: string }) {
  return (
    <div aria-busy="true">
      <span className="nl-sr-only" role="status">{label}</span>
      {Array.from({ length: 4 }, (_, i) => (
        <div key={i} className="nl-scard nl-svc-row-skel">
          <Skeleton width={96} height={96} radius="var(--radius-sm)" />
          <span><Skeleton width="50%" height={18} /><Skeleton width="70%" height={12} style={{ marginTop: 8 }} /></span>
          <Skeleton width={70} height={16} />
        </div>
      ))}
    </div>
  );
}
