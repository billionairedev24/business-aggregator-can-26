import { useEffect, type ReactNode } from 'react';
import clsx from 'clsx';
import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { ApiError, ValidationError } from '@northline/client';
import { EmptyState, ErrorState, SiteLink, Skeleton, swatchOf, useFormatters, useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import {
  apiQuery, FILTER_KEYS, imageUrl, itemHref, marketOf, RADII, SCOPES, searchQuery, SORTS,
  type Facet, type Place, type Scope, type SearchItem, type SearchParams, type Sort,
} from './api';
import { useSearchT } from './messages';
import { rememberSearch } from './recent';

type T = ReturnType<typeof useSearchT>;
type Key = Parameters<T>[0];
const EXACT_UP_TO = 10_000;

/** A filter of the list (design 06): its label and the URL parameters it sets. */
interface FilterDef { key: Key; on: (p: SearchParams) => boolean; set: (p: SearchParams, on: boolean) => SearchParams }

const has = (list: string | undefined, code: string) => (list ?? '').split(',').includes(code);
const toggleIn = (list: string | undefined, codes: string[], on: boolean): string | undefined => {
  const rest = (list ?? '').split(',').filter(c => c && !codes.includes(c));
  const next = on ? [...rest, ...codes] : rest;
  return next.length ? next.join(',') : undefined;
};
const dietary = (key: Key, code: string): FilterDef => ({ key, on: p => has(p.dietary, code), set: (p, on) => ({ ...p, dietary: toggleIn(p.dietary, [code], on) }) });
const maxPrice = (key: Key, cents: number): FilterDef => ({ key, on: p => p.maxPrice === cents, set: (p, on) => ({ ...p, maxPrice: on ? cents : undefined }) });
const tierMaster = (key: Key): FilterDef => ({ key, on: p => p.tier === 'master', set: (p, on) => ({ ...p, tier: on ? 'master' : undefined }) });
const openNow = (key: Key): FilterDef => ({ key, on: p => !!p.openNow, set: (p, on) => ({ ...p, openNow: on ? true : undefined }) });

/**
 * The filter list per scope: design 06 `search` (the shop's list, also what the header's search shows), the services
 * chips of `providers` and the food chips of `food`. "Organic" has no data in the index (S-44) and isn't offered;
 * distance has its own group.
 */
const SHOP_FILTERS: FilterDef[] = [
  { key: 'f_tonight', on: p => p.delivery === 'tonight', set: (p, on) => ({ ...p, delivery: on ? 'tonight' : undefined }) },
  maxPrice('f_under10', 999), tierMaster('f_masterSellers'), dietary('f_halal', 'halal'), dietary('f_glutenFree', 'gluten_free'),
];
const FILTERS: Record<Scope, FilterDef[]> = {
  all: SHOP_FILTERS,
  shop: SHOP_FILTERS,
  services: [
    tierMaster('f_master'),
    { key: 'f_instant', on: p => !!p.instantBook, set: (p, on) => ({ ...p, instantBook: on ? true : undefined }) },
    openNow('f_today'), maxPrice('f_under80', 7999),
  ],
  food: [
    openNow('f_open'), dietary('f_halal', 'halal'), dietary('f_vegan', 'vegan'),
    { key: 'f_nutFree', on: p => has(p.allergenFree, 'peanuts') && has(p.allergenFree, 'tree_nuts'), set: (p, on) => ({ ...p, allergenFree: toggleIn(p.allergenFree, ['peanuts', 'tree_nuts'], on) }) },
  ],
};

const KIND_SCOPE: Record<string, Scope> = { service: 'services', product: 'shop', food: 'food' };

/** Search results (design 06 `search`, S-48). The first page is server-rendered; the browser adds the visitor's place. */
export function SearchResults({ params }: { params: SearchParams }) {
  const t = useSearchT();
  const { locale } = useLocale();
  const navigate = useNavigate();
  const { location } = useDeliveryLocation();
  const scope = params.scope ?? 'all';
  const place: Place | null = location.status === 'locating' ? null
    : { market: marketOf(location.province), lat: location.lat, lng: location.lng };
  const located = place?.lat !== undefined && place.lng !== undefined;
  const results = useInfiniteQuery({ ...searchQuery(apiQuery(params, place, locale)), placeholderData: keepPreviousData });

  useEffect(() => { if (params.q?.trim()) rememberSearch(params.q); }, [params.q]);

  const go = (next: SearchParams) => void navigate({ to: '/search', search: next, resetScroll: false });
  const filters = FILTERS[scope];
  const anyFilter = FILTER_KEYS.some(k => params[k] !== undefined);
  const clear = () => go({ q: params.q, scope: params.scope, sort: params.sort });

  const first = results.data?.pages[0];
  const items = results.data?.pages.flatMap(p => p.items) ?? [];
  const facets = first?.facets;
  const total = first?.total ?? 0;

  return (
    <div className="nl-results-page">
      <aside className="nl-results-aside" aria-label={t('filters')}>
        <Group label={t('show')}>
          {SCOPES.map(s => {
            const count = scope === 'all' && s !== 'all' ? facets?.kinds.find(k => KIND_SCOPE[k.value] === s)?.count : undefined;
            const label = t(`scope_${s}`);
            return <Check key={s} on={scope === s} radio onClick={() => go({ ...withoutScopeFilters(params), scope: s === 'all' ? undefined : s })}>
              {count !== undefined ? t('scopeCount', { label, count }) : label}
            </Check>;
          })}
        </Group>
        <Group label={t('filter')}>
          {filters.map(f => <Check key={f.key} on={f.on(params)} onClick={() => go(f.set(params, !f.on(params)))}>{t(f.key)}</Check>)}
        </Group>
        {located ? (
          <Group label={t('distance')}>
            <Check radio on={!params.radiusKm} onClick={() => go({ ...params, radiusKm: undefined })}>{t('anyDistance')}</Check>
            {RADII.map(km => <Check key={km} radio on={params.radiusKm === km} onClick={() => go({ ...params, radiusKm: km })}>{t('underKm', { km })}</Check>)}
          </Group>
        ) : null}
        <FacetGroup label={t('categories')} facets={facets?.categories} active={params.category}
          onPick={value => go({ ...params, category: params.category === value ? undefined : value })} />
        {facets?.merchants.length ? (
          <Group label={t(`merchants_${scope}`)} plain>
            {facets.merchants.slice(0, 5).map(m => <span key={m.value}>{t('merchantCount', { name: m.label ?? '', count: m.count })}</span>)}
          </Group>
        ) : null}
        {anyFilter ? <button type="button" className="nl-results-clear" onClick={clear}>{t('clearAll')}</button> : null}
      </aside>

      <div className="nl-results-main" aria-busy={results.isFetching}>
        <div className="nl-results-head">
          <div>
            <nav aria-label={t('crumbs')} className="nl-results-crumbs">
              <Link to={scope === 'all' ? '/' : `/${scope}`}>{t(scope === 'all' ? 'crumbHome' : scope === 'services' ? 'crumbServices' : scope === 'shop' ? 'crumbShop' : 'crumbFood')}</Link>
              {' › '}<span aria-current="page">{t('crumbSearch')}</span>
            </nav>
            <h1>{first ? heading(t, params.q, total) : <Skeleton width={280} height={36} />}</h1>
          </div>
          <SortControl t={t} value={params.sort ?? 'relevance'} located={located} onChange={sort => go({ ...params, sort: sort === 'relevance' ? undefined : sort })} />
        </div>
        {!located && (params.sort === 'distance' || params.radiusKm) && location.status !== 'locating' ? (
          <p className="nl-results-note">{t('needsLocation')} <SiteLink href="/location?next=/search">{t('setLocation')}</SiteLink></p>
        ) : null}

        {results.isError && !results.data ? (
          <ErrorState message={errorText(t, results.error)} onRetry={() => void results.refetch()} />
        ) : !first ? (
          <ResultsSkeleton />
        ) : items.length === 0 ? (
          <EmptyState action={anyFilter
            ? <button type="button" className="btn btn-primary" onClick={clear}>{t('emptyFiltered')}</button>
            : <SiteLink href="/services" className="btn btn-primary">{t('emptyBrowse')}</SiteLink>}>
            {params.q?.trim() ? t('empty', { q: params.q.trim() }) : t('emptyBlank')}
          </EmptyState>
        ) : (
          <>
            <ul className={clsx('nl-results-grid', results.isPlaceholderData && 'nl-results-stale')}>
              {items.map(item => <li key={`${item.kind}-${item.id}`}><ResultCard item={item} t={t} /></li>)}
            </ul>
            {results.isFetchNextPageError ? <ErrorState message={errorText(t, results.error)} onRetry={() => void results.fetchNextPage()} /> : null}
            <div className="nl-results-more">
              <span>{t('shown', { shown: items.length, total: total >= EXACT_UP_TO ? `${EXACT_UP_TO}+` : total })}</span>
              {results.hasNextPage ? (
                <button type="button" className="btn btn-ghost" disabled={results.isFetchingNextPage} onClick={() => void results.fetchNextPage()}>
                  {results.isFetchingNextPage ? t('loadingMore') : t('showMore')}
                </button>
              ) : null}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/** Changing the scope drops the filters that belong to another scope's list (category, price, tier…). */
const withoutScopeFilters = (p: SearchParams): SearchParams => ({ q: p.q, sort: p.sort, radiusKm: p.radiusKm });

function heading(t: T, q: string | undefined, total: number): string {
  const text = q?.trim();
  if (total >= EXACT_UP_TO) return text ? t('headingMany', { q: text, count: EXACT_UP_TO }) : t('headingBlankMany', { count: EXACT_UP_TO });
  return text ? t('heading', { q: text, count: total }) : t('headingBlank', { count: total });
}

function errorText(t: T, error: unknown): string {
  if (error instanceof ValidationError && error.errors.some(e => e.field === 'after')) return t('staleLink');
  if (error instanceof ApiError && error.status === 429) return t('rateLimited');
  return t('error');
}

function Group({ label, children, plain }: { label: ReactNode; children: ReactNode; plain?: boolean }) {
  return (
    <section className="nl-results-group">
      <h2 className="nl-results-group-label">{label}</h2>
      <div className={clsx('nl-results-group-items', plain && 'nl-results-group-plain')}>{children}</div>
    </section>
  );
}

/** design 06's filter row: a square box and the name (a toggle; `radio` = one of a group). */
function Check({ on, onClick, children, radio }: { on: boolean; onClick: () => void; children: ReactNode; radio?: boolean }) {
  return (
    <button type="button" className={clsx('nl-check', on && 'nl-check-on', radio && 'nl-check-radio')} aria-pressed={on} onClick={onClick}>
      <span className="nl-check-box" aria-hidden />{children}
    </button>
  );
}

function FacetGroup({ label, facets, active, onPick }: { label: string; facets: Facet[] | undefined; active?: string; onPick: (value: string) => void }) {
  const shown = (facets ?? []).filter(f => f.label).slice(0, 8);
  if (!shown.length) return null;
  return (
    <Group label={label}>
      {shown.map(f => <Check key={f.value} on={active === f.value} onClick={() => onPick(f.value)}>{`${f.label} · ${f.count}`}</Check>)}
    </Group>
  );
}

function SortControl({ t, value, located, onChange }: { t: T; value: Sort; located: boolean; onChange: (s: Sort) => void }) {
  return (
    <label className="nl-results-sort">
      {t('sortedBy')}{' '}
      <select value={value} onChange={e => onChange(e.target.value as Sort)}>
        {SORTS.filter(s => s !== 'distance' || located || value === 'distance').map(s => <option key={s} value={s}>{t(`sort_${s}`)}</option>)}
      </select>
    </label>
  );
}

/** One result (design 06 card): picture, name and price, "business · detail", one tag. */
function ResultCard({ item, t }: { item: SearchItem; t: T }) {
  const { money, number } = useFormatters();
  const price = item.priceCents == null || item.pricingMode === 'quote' ? t('quote')
    : item.kind === 'merchant' ? t('from', { price: money(item.priceCents) })
    : item.pricingMode === 'hourly' ? t('hourly', { price: money(item.priceCents) })
    : money(item.priceCents);
  const business = item.kind === 'merchant' ? t(`type_${item.merchant.type}` as Key) : item.merchant.name;
  const meta = [
    business,
    item.category?.name && item.kind !== 'merchant' ? item.category.name : null,
    item.rating ? (item.reviewCount ? t('ratingCount', { rating: number(item.rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 }), count: item.reviewCount }) : t('rating', { rating: number(item.rating, { maximumFractionDigits: 1 }) })) : null,
    item.distanceKm != null ? t('km', { km: number(item.distanceKm, { maximumFractionDigits: 1 }) }) : null,
  ].filter(Boolean).join(' · ');
  const tag = tagOf(item, t);
  const image = imageUrl(item.imageKey);
  return (
    <SiteLink href={itemHref(item)} className="nl-result">
      <span className={clsx('nl-result-media halftone', !image && `nl-swatch-${swatchOf(item.merchant.id)}`)} aria-hidden>
        {image ? <img src={image} alt="" loading="lazy" decoding="async" /> : null}
      </span>
      <span className="nl-result-row"><span className="nl-result-name">{item.name}</span><span className="nl-result-price">{price}</span></span>
      <span className="nl-result-meta">{meta}</span>
      {tag ? <span className="nl-result-tag"><span className={`tag tag-${tag.tone}`}>{tag.label}</span></span> : null}
    </SiteLink>
  );
}

function tagOf(item: SearchItem, t: T): { label: string; tone: 'accent' | 'accent-2' | 'neutral' } | null {
  if (item.soldOut) return { label: t('tag_soldOut'), tone: 'neutral' };
  if (item.onTonightsRun) return { label: t('tag_tonight'), tone: 'accent' };
  if (item.kind === 'food' && item.openNow) return { label: t('tag_open'), tone: 'accent' };
  if (item.kind === 'service' && item.instantBook) return { label: t('tag_instant'), tone: 'accent' };
  const tier = item.trustTier ?? item.merchant.tier;
  if (tier === 'master') return { label: t('tag_master'), tone: 'accent' };
  if (tier === 'trusted') return { label: t('tag_trusted'), tone: 'neutral' };
  return null;
}

/** Loading: the page's shape (heading, filter rows, a grid of cards). */
export function ResultsSkeleton() {
  return (
    <ul className="nl-results-grid" aria-hidden>
      {Array.from({ length: 6 }, (_, i) => (
        <li key={i}><Skeleton height="auto" style={{ aspectRatio: '1 / 1' }} radius="var(--radius-md)" /><Skeleton width="70%" style={{ marginTop: 10 }} /><Skeleton width="50%" style={{ marginTop: 6 }} /></li>
      ))}
    </ul>
  );
}

export function SearchSkeleton() {
  return (
    <div className="nl-results-page">
      <aside className="nl-results-aside" aria-hidden>{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} width={140} height={18} style={{ margin: '10px 0' }} />)}</aside>
      <div className="nl-results-main"><Skeleton width={280} height={36} /><ResultsSkeleton /></div>
    </div>
  );
}
