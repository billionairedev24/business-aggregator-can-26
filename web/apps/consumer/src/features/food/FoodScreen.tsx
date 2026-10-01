import { useEffect, useMemo, useState } from 'react';
import { Link } from '@tanstack/react-router';
import { Chip, EmptyState, ErrorState, Segmented, SiteLink, Skeleton, Tag, useFormatters, useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { useKitchens, type Card } from './api';
import { CUISINE_NAMES, TAGS, useFoodT } from './messages';

type T = ReturnType<typeof useFoodT>;
type Filter = 'open' | 'fast' | 'halal' | 'vegan' | 'cheap' | 'nutfree';
const FILTERS: readonly Filter[] = ['open', 'fast', 'halal', 'vegan', 'cheap', 'nutfree'];
const MODE_KEY = 'nl.food.mode';

const passes = (k: Card, f: Filter, pickup: boolean) => {
  switch (f) {
    case 'open': return k.open;
    case 'fast': return (pickup ? k.pickupFromMin : k.etaFromMin) < 30;
    case 'halal': return k.dietary.includes('halal');
    case 'vegan': return k.dietary.includes('vegan') || k.dietary.includes('vegetarian');
    case 'cheap': return k.priceLevel === '$';
    case 'nutfree': return k.dietary.includes('nut_free');
  }
};

/**
 * Food landing (design 06 `food`, S-57): the kitchens of the visitor's city — open now first, then by distance —
 * with Deliver / Pickup, schedule for later, filters and cuisines. Loaded in the browser once the location is known
 * (the server doesn't know where the visitor is).
 */
export function FoodScreen({ cuisine: initialCuisine }: { cuisine?: string }) {
  const t = useFoodT();
  const { locale } = useLocale();
  const { location } = useDeliveryLocation();
  const [mode, setMode] = useState<'delivery' | 'pickup'>('delivery');
  // The visitor's last choice this visit, read after hydration (the server renders Deliver).
  useEffect(() => { try { if (window.sessionStorage.getItem(MODE_KEY) === 'pickup') setMode('pickup'); } catch { /* ignore */ } }, []);
  const [sched, setSched] = useState(false);
  const [filters, setFilters] = useState<Set<Filter>>(new Set());
  const [cuisine, setCuisine] = useState(initialCuisine ?? 'all');
  const kitchens = useKitchens(location.city, location.lat, location.lng);
  const pickup = mode === 'pickup';
  const area = location.zone ?? location.label?.split(',')[0] ?? location.city ?? '';

  const all = kitchens.data?.items ?? [];
  const reachable = all.filter(k => (pickup ? k.fulfilment.includes('pickup') : k.fulfilment.includes('courier') && k.delivers !== false))
    .filter(k => !sched || k.fulfilment.includes('scheduled'));
  const cuisines = useMemo(() => {
    const codes = new Set<string>();
    all.forEach(k => k.cuisines.forEach(c => codes.add(c)));
    if (all.some(k => k.fulfilment.includes('meal_kits'))) codes.add('meal_kits');
    return [...codes];
  }, [all]);
  const shown = reachable
    .filter(k => cuisine === 'all' || (cuisine === 'meal_kits' ? k.fulfilment.includes('meal_kits') : k.cuisines.includes(cuisine)))
    .filter(k => [...filters].every(f => passes(k, f, pickup)));
  const open = reachable.filter(k => k.open);
  const etaFrom = open.length ? Math.min(...open.map(k => (pickup ? k.pickupFromMin : k.etaFromMin))) : 0;
  const etaTo = open.length ? Math.max(...open.map(k => (pickup ? k.pickupToMin : k.etaToMin))) : 0;
  const cuisineName = (c: string) => CUISINE_NAMES[c]?.[locale] ?? c;

  const changeMode = (m: 'delivery' | 'pickup') => { setMode(m); try { window.sessionStorage.setItem(MODE_KEY, m); } catch { /* ignore */ } };
  const toggleFilter = (f: Filter) => setFilters(s => { const n = new Set(s); if (n.has(f)) n.delete(f); else n.add(f); return n; });

  return (
    <div className="nl-food">
      <div className="nl-food-top">
        <div>
          <h1 className="nl-food-title">{t('title')}</h1>
          <p className="nl-food-sub" aria-live="polite">
            {kitchens.data ? t(pickup ? 'subPickup' : 'sub', { count: open.length, area, eta: t('etaRange', { from: etaFrom, to: etaTo }) }) : <Skeleton width={320} height={14} />}
          </p>
        </div>
        <div className="nl-food-actions">
          <Segmented name="food-mode" aria-label={t('modeLabel')} value={mode} onChange={changeMode}
            options={[{ value: 'delivery', label: t('deliver') }, { value: 'pickup', label: t('pickup') }]} />
          <button type="button" className="btn btn-secondary" aria-pressed={sched} onClick={() => setSched(s => !s)}>{sched ? t('now') : t('schedule')}</button>
        </div>
      </div>
      {sched && <p className="nl-food-panel">{t('scheduleNote')}</p>}

      <div className="nl-food-chips" role="group" aria-label={t('filters')}>
        {FILTERS.map(f => <Chip key={f} selected={filters.has(f)} onClick={() => toggleFilter(f)}>{t(`f_${f}`)}</Chip>)}
      </div>
      <div className="nl-food-chips" role="group" aria-label={t('cuisines')}>
        <Chip selected={cuisine === 'all'} onClick={() => setCuisine('all')}>{t('all')}</Chip>
        {cuisines.map(c => <Chip key={c} selected={cuisine === c} onClick={() => setCuisine(c)}>{cuisineName(c)}</Chip>)}
      </div>

      {kitchens.isError ? (
        <div className="nl-food-block"><ErrorState message={t('loadError')} onRetry={() => void kitchens.refetch()} /></div>
      ) : !kitchens.data ? (
        <div className="nl-food-grid" aria-busy="true">{Array.from({ length: 6 }, (_, i) => <div key={i}><Skeleton height={160} radius={12} /><Skeleton width="60%" height={16} style={{ marginTop: 10 }} /></div>)}</div>
      ) : reachable.length === 0 ? (
        <div className="nl-food-block"><EmptyState action={<Link to="/location" className="btn btn-primary">{t('changeLocation')}</Link>}>{t('empty', { area })}</EmptyState></div>
      ) : (
        <>
          <h2 className="nl-food-heading">
            {cuisine === 'all'
              ? t(pickup ? 'headingPickup' : 'heading', { count: shown.length, area })
              : t('headingCuisine', { cuisine: cuisineName(cuisine), count: shown.length })}
          </h2>
          {shown.length === 0
            ? <EmptyState action={<button type="button" className="btn btn-secondary" onClick={() => { setFilters(new Set()); setCuisine('all'); }}>{t('clearFilters')}</button>}>{t('emptyFiltered')}</EmptyState>
            : <div className="nl-food-grid">{shown.map(k => <KitchenCard key={k.merchantId} t={t} k={k} pickup={pickup} cuisineName={cuisineName} />)}</div>}
        </>
      )}

      <section className="nl-food-info">
        <div><h3>{t('meal')}</h3><p>{t('mealText')}</p><Link to="/services">{t('mealLink')}</Link></div>
        <div><h3>{t('safety')}</h3><p>{t('safetyText')}</p></div>
        <div><h3>{t('points')}</h3><p>{t('pointsText')}</p></div>
      </section>
    </div>
  );
}

function KitchenCard({ t, k, pickup, cuisineName }: { t: T; k: Card; pickup: boolean; cuisineName: (c: string) => string }) {
  const { money, date } = useFormatters();
  const { locale } = useLocale();
  const eta = pickup ? t('readyIn', { from: k.pickupFromMin, to: k.pickupToMin }) : t('etaRange', { from: k.etaFromMin, to: k.etaToMin });
  const meta = [
    k.cuisines.map(cuisineName).join(', ') || null,
    k.priceLevel,
    k.distanceKm != null ? t('km', { km: k.distanceKm.toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA') }) : null,
    pickup ? null : k.delivers === false ? t('noDelivery') : money(k.deliveryFeeCents),
  ].filter(Boolean).join(' · ');
  const body = (
    <>
      <div className="nl-food-tile halftone" style={k.brandColor ? { background: `linear-gradient(135deg, ${k.brandColor}, var(--color-neutral-600))` } : undefined}>
        <span className="tag nl-food-eta">{eta}</span>
        {!k.open && <span className="tag tag-neutral nl-food-opens">{k.paused ? t('paused') : k.opensAt ? t('opens', { time: date(k.opensAt, 'time') }) : t('closedNow')}</span>}
      </div>
      <div className="nl-food-card-head"><span className="nl-food-card-name">{k.name}</span><span>{k.reviews > 0 ? t('rating', { rating: k.rating.toFixed(1), count: k.reviews }) : t('newKitchen')}</span></div>
      <div className="nl-food-card-meta">{meta}</div>
      {k.dietary.length > 0 && <div className="nl-food-tags">{k.dietary.map(d => <Tag key={d} tone="neutral">{TAGS[d]?.[locale] ?? d}</Tag>)}</div>}
    </>
  );
  return k.slug ? <SiteLink href={`/food/${k.slug}`} className="nl-food-card">{body}</SiteLink> : <div className="nl-food-card">{body}</div>;
}
