import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import clsx from 'clsx';
import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Faders } from '@phosphor-icons/react';
import { ApiError, ValidationError } from '@northline/client';
import { EmptyState, ErrorState, SiteLink, Skeleton, fullNavEnabled, useFormatters, useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import {
  apiQuery, FILTER_KEYS, marketOf, RADII, searchQuery, SORTS,
  type Facet, type Place, type Scope, type SearchParams, type Sort,
} from './api';
import { ItemCard } from './ItemCard';
import { useSearchT } from './messages';
import { DEPARTMENTS, SERVICE_CATEGORIES } from '../home/catalog';
import { categorySlot } from '@northline/media';
import { Tile } from '../pages/Tile';
import { PlaceButton } from '../pages/PlaceButton';
import { rememberSearch } from './recent';
import { SmartSearch } from './SmartSearch';

type T = ReturnType<typeof useSearchT>;
const EXACT_UP_TO = 10_000;
/** "Under $10 / $25 / $50 / $100" — the API's `maxPrice` in cents (inclusive). */
const PRICES = [999, 2499, 4999, 9999] as const;
const KIND_SCOPE: Record<string, Scope> = { service: 'services', product: 'shop', food: 'food' };

/**
 * Search results (redesign § 1.3 "Results page", AT Shop:274; S-48 behaviour): a summary bar ("N results for q" and
 * the sort), a 230px filter aside — Type (products and services together, or one of them), Category, Distance,
 * Availability, Price, "Clear all" — and one grid of product and service cards, loaded page by page as the visitor
 * scrolls. The first page is server-rendered; the browser adds the visitor's place. S-158: a sentence typed in the
 * search box gets the AI's suggested filters as removable chips above the results, applied only on request.
 */
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
  const [filtersOpen, setFiltersOpen] = useState(false);
  const asideId = useId();

  useEffect(() => { if (params.q?.trim()) rememberSearch(params.q); }, [params.q]);

  const go = (next: SearchParams) => void navigate({ to: '/search', search: next, resetScroll: false });
  const anyFilter = FILTER_KEYS.some(k => params[k] !== undefined);
  const clear = () => go({ q: params.q, scope: params.scope, sort: params.sort });

  const first = results.data?.pages[0];
  const items = results.data?.pages.flatMap(p => p.items) ?? [];
  const facets = first?.facets;
  const total = first?.total ?? 0;
  const scopes: Scope[] = fullNavEnabled() ? ['all', 'shop', 'services', 'food'] : ['all', 'shop', 'services'];

  return (
    <div className="nl-page nl-results-page">
      <div className="nl-results-bar">
        <h1 className="nl-results-h1">{first ? <Heading t={t} q={params.q} total={total} /> : <Skeleton width={260} height={20} />}</h1>
        <div className="nl-results-bar-end">
          <button type="button" className="btn btn-secondary btn-sm nl-results-filters-toggle" aria-expanded={filtersOpen} aria-controls={asideId} onClick={() => setFiltersOpen(o => !o)}>
            <Faders size={16} aria-hidden />{t('filters')}
          </button>
          <SortControl t={t} value={params.sort ?? 'relevance'} located={located} onChange={sort => go({ ...params, sort: sort === 'relevance' ? undefined : sort })} />
        </div>
      </div>

      <div className="nl-results-layout">
        <aside id={asideId} className={clsx('nl-results-aside', filtersOpen && 'is-open')} aria-label={t('filters')}>
          <Group label={t('type')}>
            {scopes.map(s => {
              const count = scope === 'all' && s !== 'all' ? facets?.kinds.find(k => KIND_SCOPE[k.value] === s)?.count : undefined;
              return (
                <Option key={s} radio name="type" checked={scope === s} onChange={() => go({ ...withoutScopeFilters(params), scope: s === 'all' ? undefined : s })}
                  label={t(`type_${s}`)} count={count} />
              );
            })}
          </Group>
          <FacetGroup t={t} facets={facets?.categories} active={params.category} onPick={value => go({ ...params, category: params.category === value ? undefined : value })} />
          {located ? (
            <Group label={t('distance')}>
              <Option radio name="distance" checked={!params.radiusKm} onChange={() => go({ ...params, radiusKm: undefined })} label={t('anyDistance')} />
              {RADII.map(km => <Option key={km} radio name="distance" checked={params.radiusKm === km} onChange={() => go({ ...params, radiusKm: km })} label={t('underKm', { km })} />)}
            </Group>
          ) : (
            <Group label={t('distance')}>
              <p className="nl-results-note">{t('needsLocation')} <PlaceButton className="btn btn-link">{t('setLocation')}</PlaceButton></p>
            </Group>
          )}
          <Group label={t('availability')}>
            {scope !== 'shop' ? <Option checked={!!params.openNow} onChange={on => go({ ...params, openNow: on ? true : undefined })} label={t('f_open')} /> : null}
            {scope === 'services' ? <Option checked={!!params.instantBook} onChange={on => go({ ...params, instantBook: on ? true : undefined })} label={t('f_instant')} /> : null}
            {scope === 'shop' || scope === 'all' ? <Option checked={params.delivery === 'tonight'} onChange={on => go({ ...params, delivery: on ? 'tonight' : undefined })} label={t('f_tonight')} /> : null}
          </Group>
          <PriceGroup t={t} value={params.maxPrice} onPick={cents => go({ ...params, maxPrice: cents })} />
          {anyFilter ? <button type="button" className="btn btn-link nl-results-clear" onClick={clear}>{t('clearAll')}</button> : null}
        </aside>

        <div className="nl-results-main" aria-busy={results.isFetching}>
          <SmartSearch params={params} located={located} onApply={next => go(next)} />
          {results.isError && !results.data ? (
            <ErrorState message={errorText(t, results.error)} onRetry={() => void results.refetch()} />
          ) : !first ? (
            <ResultsSkeleton />
          ) : items.length === 0 ? (
            <>
              <EmptyState title={t('noResults')} action={anyFilter
                ? <button type="button" className="btn btn-primary" onClick={clear}>{t('emptyFiltered')}</button>
                : <SiteLink href="/services" className="btn btn-primary">{t('emptyBrowse')}</SiteLink>}>
                {anyFilter ? t('emptyHint') : params.q?.trim() ? t('empty', { q: params.q.trim() }) : t('emptyBlank')}
              </EmptyState>
              {!anyFilter ? <Suggestions t={t} /> : null}
            </>
          ) : (
            <>
              <ul className={clsx('nl-card-grid', results.isPlaceholderData && 'nl-results-stale')} aria-label={t('resultsLabel')}>
                {items.map(item => <li key={`${item.kind}-${item.id}`}><ItemCard item={item} level={2} /></li>)}
              </ul>
              {results.isFetchNextPageError ? <ErrorState message={errorText(t, results.error)} onRetry={() => void results.fetchNextPage()} /> : null}
              <More t={t} shown={items.length} total={total} hasNext={!!results.hasNextPage} loading={results.isFetchingNextPage}
                onMore={() => void results.fetchNextPage()} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/** Changing the type drops the filters that belong to another type (category, price, availability…). */
const withoutScopeFilters = (p: SearchParams): SearchParams => ({ q: p.q, sort: p.sort, radiusKm: p.radiusKm });

function Heading({ t, q, total }: { t: T; q: string | undefined; total: number }) {
  const { number } = useFormatters();
  const text = q?.trim();
  const count = total >= EXACT_UP_TO ? `${number(EXACT_UP_TO)}+` : number(total);
  if (!text) return <>{t('resultsBlank', { count: total, shown: count })}</>;
  return <>{t('resultsFor', { count: total, shown: count })} <strong>{t('quoted', { q: text })}</strong></>;
}

function errorText(t: T, error: unknown): string {
  if (error instanceof ValidationError && error.errors.some(e => e.field === 'after')) return t('staleLink');
  if (error instanceof ApiError && error.status === 429) return t('rateLimited');
  return t('error');
}

function Group({ label, children }: { label: ReactNode; children: ReactNode }) {
  const id = useId();
  return (
    <section className="nl-results-group" aria-labelledby={id}>
      <h2 id={id} className="nl-results-group-label">{label}</h2>
      <div className="nl-results-group-items">{children}</div>
    </section>
  );
}

/** A filter row (AT: a checkbox or radio list with counted options). */
function Option({ checked, onChange, label, count, radio, name }: { checked: boolean; onChange: (on: boolean) => void; label: string; count?: number; radio?: boolean; name?: string }) {
  const { number } = useFormatters();
  return (
    <label className="nl-results-option">
      <input type={radio ? 'radio' : 'checkbox'} name={name} checked={checked} onChange={e => onChange(radio ? true : e.target.checked)} />
      <span className="nl-results-option-label">{label}</span>
      {count !== undefined ? <span className="nl-results-option-count">{number(count)}</span> : null}
    </label>
  );
}

function FacetGroup({ t, facets, active, onPick }: { t: T; facets: Facet[] | undefined; active?: string; onPick: (value: string) => void }) {
  const shown = (facets ?? []).filter(f => f.label).slice(0, 8);
  if (!shown.length) return null;
  return (
    <Group label={t('categories')}>
      {shown.map(f => <Option key={f.value} checked={active === f.value} onChange={() => onPick(f.value)} label={f.label!} count={f.count} />)}
    </Group>
  );
}

function PriceGroup({ t, value, onPick }: { t: T; value: number | undefined; onPick: (cents: number | undefined) => void }) {
  const { money } = useFormatters();
  return (
    <Group label={t('price')}>
      <Option radio name="price" checked={value === undefined} onChange={() => onPick(undefined)} label={t('anyPrice')} />
      {PRICES.map(c => <Option key={c} radio name="price" checked={value === c} onChange={() => onPick(c)} label={t('underPrice', { price: money(c + 1, { whole: true }) })} />)}
    </Group>
  );
}

function SortControl({ t, value, located, onChange }: { t: T; value: Sort; located: boolean; onChange: (s: Sort) => void }) {
  return (
    <label className="nl-results-sort">
      <span>{t('sortedBy')}</span>
      <select className="input" value={value} onChange={e => onChange(e.target.value as Sort)}>
        {SORTS.filter(s => s !== 'distance' || located || value === 'distance').map(s => <option key={s} value={s}>{t(`sort_${s}`)}</option>)}
      </select>
    </label>
  );
}

/** The infinite list's end: a sentinel that loads the next page as it scrolls in, and the button for the same. */
function More({ t, shown, total, hasNext, loading, onMore }: { t: T; shown: number; total: number; hasNext: boolean; loading: boolean; onMore: () => void }) {
  const { number } = useFormatters();
  const sentinel = useRef<HTMLDivElement>(null);
  const more = useRef(onMore);
  more.current = onMore;
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasNext || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(entries => { if (entries.some(e => e.isIntersecting)) more.current(); }, { rootMargin: '600px 0px' });
    io.observe(el);
    return () => io.disconnect();
  }, [hasNext, shown]);
  return (
    <div ref={sentinel} className="nl-results-more" role="status">
      {hasNext ? (
        <>
          <span>{t('shown', { shown: number(shown), total: total >= EXACT_UP_TO ? `${number(EXACT_UP_TO)}+` : number(total) })}</span>
          <button type="button" className="btn btn-secondary" disabled={loading} onClick={onMore}>{loading ? t('loadingMore') : t('showMore')}</button>
        </>
      ) : <span>{t('seenAll', { count: shown })}</span>}
    </div>
  );
}

/** Loading: a grid of card shapes. */
export function ResultsSkeleton() {
  return (
    <ul className="nl-card-grid" aria-hidden>
      {Array.from({ length: 8 }, (_, i) => (
        <li key={i}><Skeleton height="auto" style={{ aspectRatio: '1 / 1' }} radius="var(--radius-sm)" /><Skeleton width="70%" style={{ marginTop: 10 }} /><Skeleton width="40%" height={24} style={{ marginTop: 6 }} /></li>
      ))}
    </ul>
  );
}

export function SearchSkeleton() {
  return (
    <div className="nl-page nl-results-page">
      <div className="nl-results-bar"><Skeleton width={260} height={20} /></div>
      <div className="nl-results-layout">
        <aside className="nl-results-aside" aria-hidden>{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} width={160} height={16} style={{ margin: '12px 0' }} />)}</aside>
        <div className="nl-results-main"><ResultsSkeleton /></div>
      </div>
    </div>
  );
}

/** Under "No results": popular ways in — services and shop departments as photo tiles. */
function Suggestions({ t }: { t: ReturnType<typeof useSearchT> }) {
  const { locale } = useLocale();
  const tiles = [...SERVICE_CATEGORIES.filter(s => ['mechanic', 'cleaning', 'plumber'].includes(s.key)), ...DEPARTMENTS.filter(d => ['groceries', 'bakery', 'pharmacy'].includes(d.key))];
  return (
    <section className="nl-section nl-results-suggest" aria-labelledby="results-suggest">
      <div className="nl-section-head"><h2 id="results-suggest" className="nl-section-title">{t('suggestTitle')}</h2></div>
      <ul className="nl-tiles">
        {tiles.map(tile => <li key={tile.key}><Tile href={tile.href} name={locale === 'fr' ? tile.fr : tile.en} slot={categorySlot(tile.ids[0]) ?? 'empty/search'} subject={tile.ids[0]!.split('.').pop()!} /></li>)}
      </ul>
    </section>
  );
}
