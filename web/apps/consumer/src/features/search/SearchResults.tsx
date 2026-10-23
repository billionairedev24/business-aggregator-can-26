import { useEffect, useState, type ReactNode } from 'react';
import clsx from 'clsx';
import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { CaretLeft, Faders, Truck } from '@phosphor-icons/react';
import { ApiError, ValidationError } from '@northline/client';
import {
  Checkbox, Dialog, EmptyState, ErrorState, FilterChip, FilterGroup, Pagination, RatingFilter, Segmented, SiteLink, Skeleton,
  useFormatters, useLocale,
} from '@northline/ui';
import { categorySlot } from '@northline/media';
import { shortPlace, useDeliveryLocation } from '../location/useDeliveryLocation';
import { useZone } from '../location/regions';
import {
  apiQuery, FILTER_KEYS, marketOf, PAGE_SIZE, PRICE_BUCKETS, priceBucketOf, RADII, searchQuery, SORTS,
  type Facet, type Place, type Scope, type SearchParams, type Sort,
} from './api';
import { ItemCard } from './ItemCard';
import { useSearchT } from './messages';
import { DEPARTMENTS, SERVICE_CATEGORIES } from '../home/catalog';
import { Tile } from '../pages/Tile';
import { PlaceButton } from '../pages/PlaceButton';
import { clock, windowRange } from '../shop/format';
import { useNextRun } from '../shop/run';
import { rememberSearch } from './recent';
import { SmartSearch } from './SmartSearch';

type T = ReturnType<typeof useSearchT>;
const EXACT_UP_TO = 10_000;
const KIND_SCOPE: Record<string, Scope> = { service: 'services', product: 'shop', food: 'food' };
const SCOPES: readonly Exclude<Scope, 'all'>[] = ['shop', 'services', 'food'];

/**
 * Search results (E-15 A · Plainsight, design/revamp/a.html "Search results"; S-48 behaviour): a bar with "1–24 of 86
 * results for “q” in Shop · delivering to <place>" and the sort; a 224 px filter rail with counts (department, delivery
 * or availability, customer rating, distance, price, dietary); the applied filters as removable chips with "Clear all";
 * the dense grid (5 cards a row at 1280) and pagination. On phones the rail is a Filters button (with the number
 * applied) opening a sheet, and the chips scroll in one row. The first page is server-rendered; the browser adds the
 * visitor's place. S-158: a sentence typed in the search box gets the AI's suggested filters, applied only on request.
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
  const [sheet, setSheet] = useState(false);
  const page = params.page ?? 1;

  useEffect(() => { if (params.q?.trim()) rememberSearch(params.q); }, [params.q]);
  // pages are cursor-linked: a later page fetches the ones before it, once
  const loaded = results.data?.pages.length ?? 0;
  useEffect(() => {
    if (loaded > 0 && loaded < page && results.hasNextPage && !results.isFetchingNextPage) void results.fetchNextPage();
  }, [loaded, page, results]);

  const go = (next: SearchParams, keepPage = false) => void navigate({ to: '/search', search: keepPage ? next : { ...next, page: undefined }, resetScroll: keepPage });
  const applied = FILTER_KEYS.filter(k => params[k] !== undefined).length;
  const clear = () => go({ q: params.q, scope: params.scope, sort: params.sort });

  const first = results.data?.pages[0];
  const shown = results.data?.pages[page - 1];
  const items = shown?.items ?? [];
  const facets = first?.facets;
  const total = first?.total ?? 0;
  const pages = Math.max(1, Math.min(50, Math.ceil(Math.min(total, EXACT_UP_TO) / PAGE_SIZE)));
  const rail = <FilterRail t={t} params={params} facets={facets} located={located} go={go} />;

  return (
    <div className="nl-page nl-results-page">
      <div className="nl-results-bar">
        <h1 className="nl-results-h1">{first ? <Heading t={t} params={params} total={total} page={page} count={items.length} /> : <Skeleton width={320} height={18} />}</h1>
        <SortControl t={t} value={params.sort ?? 'relevance'} located={located} onChange={sort => go({ ...params, sort: sort === 'relevance' ? undefined : sort })} />
      </div>

      <div className="nl-results-layout">
        <aside className="nl-results-aside" aria-label={t('filters')}>{rail}</aside>

        <div className="nl-results-main" aria-busy={results.isFetching}>
          <div className="nl-results-chips" role="group" aria-label={t('filtersApplied')}>
            <button type="button" className="btn btn-secondary btn-sm nl-results-filters-toggle" aria-haspopup="dialog" onClick={() => setSheet(true)}>
              <Faders size={16} aria-hidden />{t('filtersButton')}
              {applied ? <span className="nl-results-count" aria-hidden>{applied}</span> : null}
              {applied ? <span className="nl-sr-only">{t('filtersCount', { count: applied })}</span> : null}
            </button>
            <AppliedChips t={t} params={params} facets={facets} go={go} />
            {applied ? <button type="button" className="btn btn-tertiary btn-sm nl-results-clear" onClick={clear}>{t('clearAll')}</button> : null}
          </div>
          {scope === 'shop' || params.delivery === 'tonight' ? <TonightNote t={t} /> : null}
          <SmartSearch params={params} located={located} onApply={next => go(next)} />
          {results.isError && !results.data ? (
            <ErrorState message={errorText(t, results.error)} onRetry={() => void results.refetch()} />
          ) : !first || (!shown && page > 1) ? (
            <ResultsSkeleton />
          ) : items.length === 0 ? (
            <>
              <EmptyState title={t('noResults')} action={applied
                ? <button type="button" className="btn btn-primary" onClick={clear}>{t('emptyFiltered')}</button>
                : <SiteLink href="/services" className="btn btn-primary">{t('emptyBrowse')}</SiteLink>}>
                {applied ? t('emptyHint') : params.q?.trim() ? t('empty', { q: params.q.trim() }) : t('emptyBlank')}
              </EmptyState>
              {!applied ? <Suggestions t={t} /> : null}
            </>
          ) : (
            <>
              <ul className={clsx('nl-card-grid nl-card-grid-results', results.isPlaceholderData && 'nl-results-stale')} aria-label={t('resultsLabel')}>
                {items.map(item => <li key={`${item.kind}-${item.id}`}><ItemCard item={item} level={2} /></li>)}
              </ul>
              {results.isFetchNextPageError ? <ErrorState message={errorText(t, results.error)} onRetry={() => void results.fetchNextPage()} /> : null}
              <Pagination className="nl-results-pager" page={page} pages={pages} onChange={p => go({ ...params, page: p > 1 ? p : undefined }, true)} />
            </>
          )}
        </div>
      </div>

      <Dialog open={sheet} onClose={() => setSheet(false)} title={t('filtersButton')} dismissible sheetOnPhone className="nl-results-sheet"
        actions={<button type="button" className="btn btn-primary btn-block" onClick={() => setSheet(false)}>{t('showResults', { count: total })}</button>}>
        {rail}
      </Dialog>
    </div>
  );
}

function Heading({ t, params, total, page, count }: { t: T; params: SearchParams; total: number; page: number; count: number }) {
  const { number } = useFormatters();
  const { location } = useDeliveryLocation();
  const text = params.q?.trim();
  const from = total === 0 ? 0 : (page - 1) * PAGE_SIZE + 1;
  const shownTotal = total >= EXACT_UP_TO ? `${number(EXACT_UP_TO)}+` : number(total);
  const placeName = shortPlace(location);
  return (
    <>
      <strong>{t('rangeOf', { from: number(from), to: number(from + Math.max(count, 1) - 1), total: shownTotal, count: total })}</strong>
      {text ? <> {t('rangeFor')} <strong>{t('quoted', { q: text })}</strong></> : null}
      {params.scope && params.scope !== 'all' ? <> {t('inScope', { scope: t(`scope_${params.scope}`) })}</> : null}
      {placeName ? <span className="nl-results-where"> · {t('deliveringTo', { place: placeName })}</span> : null}
    </>
  );
}

function errorText(t: T, error: unknown): string {
  if (error instanceof ValidationError && error.errors.some(e => e.field === 'after')) return t('staleLink');
  if (error instanceof ApiError && error.status === 429) return t('rateLimited');
  return t('error');
}

/** "Tonight's pooled run: add from several shops for one $2.99 delivery, order by 5:30 p.m." — when the market has a run. */
function TonightNote({ t }: { t: T }) {
  const { run, market } = useNextRun();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const zone = useZone(market);
  if (!run || run.day !== 'today') return null;
  return (
    <p className="nl-results-note"><Truck size={18} aria-hidden /><span><strong>{t('tonightNote')}</strong> {t('tonightNoteRest', { fee: money(run.feeCents), time: clock(run.orderBy, locale, zone) })}</span></p>
  );
}

// ── the filter rail (and the phone's sheet) ─────────────────────────────────────────────────────────────────────

type Go = (next: SearchParams) => void;

function FilterRail({ t, params, facets, located, go }: { t: T; params: SearchParams; facets: { kinds: Facet[]; categories: Facet[]; prices: Facet[]; dietary: Facet[] } | null | undefined; located: boolean; go: Go }) {
  const { number, money } = useFormatters();
  const { location } = useDeliveryLocation();
  const { run, market } = useNextRun();
  const { locale } = useLocale();
  const zone = useZone(market);
  const scope = params.scope ?? 'all';
  const count = (n: number | undefined) => (n === undefined ? undefined : number(n));
  const dietary = new Set((params.dietary ?? '').split(',').filter(Boolean));
  const bucket = priceBucketOf(params);
  const placeName = shortPlace(location);
  const tonight = run && run.day === 'today' ? t('f_tonightWindow', { window: windowRange(run.startsAt, run.endsAt, locale, zone) }) : t('f_tonightRun');
  const prices = facets?.prices.filter(p => p.count > 0 && PRICE_BUCKETS[p.value]) ?? [];
  const diets = facets?.dietary.filter(d => d.count > 0) ?? [];
  return (
    <div className="nl-results-rail">
      <FilterGroup label={t('department')}>
        {scope === 'all' ? (
          SCOPES.map(s => {
            const n = facets?.kinds.find(k => KIND_SCOPE[k.value] === s)?.count;
            return <Checkbox key={s} checked={false} onChange={() => go({ q: params.q, sort: params.sort, radiusKm: params.radiusKm, scope: s })} label={t(`scope_${s}`)} count={count(n)} />;
          })
        ) : (
          <>
            <button type="button" className="nl-results-up" onClick={() => go({ q: params.q, sort: params.sort, radiusKm: params.radiusKm })}><CaretLeft size={14} aria-hidden />{t('allDepartments')}</button>
            <span className="nl-results-cur">{t(`scope_${scope}`)}</span>
            {(facets?.categories ?? []).filter(f => f.label).slice(0, 8).map(f => (
              <Checkbox key={f.value} className="nl-results-sub" checked={params.category === f.value} label={f.label!} count={count(f.count)}
                onChange={on => go({ ...params, category: on ? f.value : undefined })} />
            ))}
          </>
        )}
      </FilterGroup>

      {scope === 'shop' || scope === 'all' ? (
        <FilterGroup label={t('delivery')}>
          <Checkbox checked={params.delivery === 'tonight'} onChange={on => go({ ...params, delivery: on ? 'tonight' : undefined })} label={tonight} />
        </FilterGroup>
      ) : (
        <FilterGroup label={t('availability')}>
          <Checkbox checked={!!params.openNow} onChange={on => go({ ...params, openNow: on ? true : undefined })} label={t('f_open')} />
          {scope === 'services' ? <Checkbox checked={!!params.instantBook} onChange={on => go({ ...params, instantBook: on ? true : undefined })} label={t('f_instant')} /> : null}
        </FilterGroup>
      )}

      <FilterGroup label={t('customerRating')}>
        <RatingFilter value={params.minRating} onChange={r => go({ ...params, minRating: r })} />
      </FilterGroup>

      <FilterGroup label={placeName ? t('distanceFrom', { place: placeName }) : t('distanceHere')}>
        {located ? (
          <Segmented name="search-distance" size="sm" fill aria-label={t('distanceHere')} value={params.radiusKm ? String(params.radiusKm) : 'any'}
            onChange={v => go({ ...params, radiusKm: v === 'any' ? undefined : Number(v) })}
            options={[...RADII.map(km => ({ value: String(km), label: `${number(km)} km` })), { value: 'any', label: t('anyKm') }]} />
        ) : (
          <p className="nl-results-hint">{t('needsLocation')} <PlaceButton className="btn btn-tertiary btn-sm">{t('setLocation')}</PlaceButton></p>
        )}
      </FilterGroup>

      {prices.length ? (
        <FilterGroup label={t('price')}>
          {prices.map(p => {
            const b = PRICE_BUCKETS[p.value]!;
            const label = t(`p_${p.value}` as 'p_under_10', { a: money((b.min ?? (b.max ?? 0) + 1), { whole: true }), b: money((b.max ?? 0) + 1, { whole: true }) });
            return <Checkbox key={p.value} checked={bucket === p.value} label={label} count={count(p.count)}
              onChange={on => go({ ...params, minPrice: on ? b.min : undefined, maxPrice: on ? b.max : undefined })} />;
          })}
        </FilterGroup>
      ) : null}

      {diets.length ? (
        <FilterGroup label={t('dietaryGroup')}>
          {diets.slice(0, 6).map(d => (
            <Checkbox key={d.value} checked={dietary.has(d.value)} label={dietLabel(t, d)} count={count(d.count)}
              onChange={on => {
                const next = new Set(dietary);
                if (on) next.add(d.value); else next.delete(d.value);
                go({ ...params, dietary: next.size ? [...next].join(',') : undefined });
              }} />
          ))}
        </FilterGroup>
      ) : null}
    </div>
  );
}

const DIETS = ['vegan', 'vegetarian', 'gluten_free', 'halal', 'kosher', 'nut_free', 'dairy_free'] as const;
const dietLabel = (t: T, d: { value: string; label?: string | null }) =>
  (DIETS as readonly string[]).includes(d.value) ? t(`d_${d.value}` as 'd_vegan') : d.label ?? d.value.replace(/_/g, ' ');

/** The applied filters as removable chips ("✓ Tonight ×"): each names what it removes. */
function AppliedChips({ t, params, facets, go }: { t: T; params: SearchParams; facets: { categories: Facet[] } | null | undefined; go: Go }) {
  const { money, number } = useFormatters();
  const chips: { key: string; label: string; remove: () => void }[] = [];
  if (params.category) chips.push({ key: 'category', label: facets?.categories.find(c => c.value === params.category)?.label ?? params.category.split('.').pop()!.replace(/-/g, ' '), remove: () => go({ ...params, category: undefined }) });
  if (params.delivery === 'tonight') chips.push({ key: 'delivery', label: t('f_tonightRun'), remove: () => go({ ...params, delivery: undefined }) });
  if (params.openNow) chips.push({ key: 'open', label: t('f_open'), remove: () => go({ ...params, openNow: undefined }) });
  if (params.instantBook) chips.push({ key: 'instant', label: t('f_instant'), remove: () => go({ ...params, instantBook: undefined }) });
  const bucket = priceBucketOf(params);
  if (params.minPrice !== undefined || params.maxPrice !== undefined) {
    const b = bucket ? PRICE_BUCKETS[bucket]! : { min: params.minPrice, max: params.maxPrice };
    const label = b.min === undefined ? t('underPrice', { price: money((b.max ?? 0) + 1, { whole: true }) })
      : b.max === undefined ? t('p_100_plus', { a: money(b.min, { whole: true }) })
        : t('p_10_25', { a: money(b.min, { whole: true }), b: money(b.max + 1, { whole: true }) });
    chips.push({ key: 'price', label, remove: () => go({ ...params, minPrice: undefined, maxPrice: undefined }) });
  }
  if (params.minRating) chips.push({ key: 'rating', label: t('ratingChip', { rating: number(params.minRating) }), remove: () => go({ ...params, minRating: undefined }) });
  if (params.radiusKm) chips.push({ key: 'km', label: t('within', { km: number(params.radiusKm) }), remove: () => go({ ...params, radiusKm: undefined }) });
  for (const d of (params.dietary ?? '').split(',').filter(Boolean)) {
    chips.push({ key: `diet-${d}`, label: dietLabel(t, { value: d }), remove: () => go({ ...params, dietary: (params.dietary ?? '').split(',').filter(x => x && x !== d).join(',') || undefined }) });
  }
  if (params.tier) chips.push({ key: 'tier', label: t(params.tier === 'master' ? 'f_master' : 'tier_trusted'), remove: () => go({ ...params, tier: undefined }) });
  if (params.allergenFree) chips.push({ key: 'allergen', label: t('f_nutFree'), remove: () => go({ ...params, allergenFree: undefined }) });
  return <>{chips.map(c => <FilterChip key={c.key} label={c.label} applied onRemove={c.remove} />)}</>;
}

function SortControl({ t, value, located, onChange }: { t: T; value: Sort; located: boolean; onChange: (s: Sort) => void }) {
  return (
    <label className="nl-results-sort">
      <span className="nl-results-sort-label">{t('sortBy')}</span>
      <select className="input" value={value} onChange={e => onChange(e.target.value as Sort)}>
        {SORTS.filter(s => s !== 'distance' || located || value === 'distance').map(s => <option key={s} value={s}>{t(`sortOpt_${s}`)}</option>)}
      </select>
    </label>
  );
}

/** Loading: a grid of the cards' own boxes. */
export function ResultsSkeleton() {
  return (
    <ul className="nl-card-grid nl-card-grid-results" aria-hidden>
      {Array.from({ length: 10 }, (_, i) => (
        <li key={i} className="nl-results-skel"><span className="nl-results-skel-pic" /><Skeleton width="80%" height={14} /><Skeleton width="40%" height={20} /></li>
      ))}
    </ul>
  );
}

export function SearchSkeleton() {
  return (
    <div className="nl-page nl-results-page">
      <div className="nl-results-bar"><Skeleton width={320} height={18} /></div>
      <div className="nl-results-layout">
        <aside className="nl-results-aside" aria-hidden><div className="nl-results-rail">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} width={160} height={16} />)}</div></aside>
        <div className="nl-results-main"><ResultsSkeleton /></div>
      </div>
    </div>
  );
}

/** Under "No results": popular ways in — services and shop departments as photo tiles. */
function Suggestions({ t }: { t: T }): ReactNode {
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
