import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { IdentificationCard, ListChecks, Scales, ShieldWarning, Truck, UsersThree, type Icon } from '@phosphor-icons/react';
import {
  BoHeader, ChartCard, ErrorState, FactList, formatDate, formatMoney, formatNumber, KpiStrip, PageSkeleton, StackedBarChart, TodoCard, useLocale,
  type KpiItem, type Locale, type TodoItem,
} from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { meQuery, opens } from '../shell/api';
import { useActiveGrant } from '../shell/ConsoleLayout';
import { PlaceFilters } from '../shell/PlaceFilters';
import { SCREEN_PATH, type ScreenKey } from '../shell/screens';
import { overviewQuery, type Overview as OverviewData } from './api';
import { useOverviewT, type OverviewKey, type OverviewT } from './messages';
import './overview.css';

const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };

/** "$212k", "$24.1k", "$2.71": whole thousands from $100k, one decimal below, cents under $1,000. */
export function compactMoney(cents: number, locale: Locale): string {
  const dollars = cents / 100;
  if (Math.abs(dollars) < 1000) return formatMoney(cents, locale);
  return new Intl.NumberFormat(INTL[locale], { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol', notation: 'compact', maximumFractionDigits: Math.abs(dollars) >= 100_000 ? 0 : 1 })
    .format(dollars).replace('K', 'k');
}

const pct = (ratio: number, locale: Locale, digits = 1) => formatNumber(ratio, locale, { style: 'percent', minimumFractionDigits: digits, maximumFractionDigits: digits });

/** "12 min", "2 h", "3.0 d" since an instant. */
export function age(since: string, now: string, t: OverviewT, locale: Locale): string {
  const minutes = Math.max(0, (Date.parse(now) - Date.parse(since)) / 60_000);
  if (minutes < 60) return t('ageMin', { n: Math.round(minutes) });
  if (minutes < 24 * 60) return t('ageH', { n: Math.round(minutes / 60) });
  return t('ageD', { n: formatNumber(minutes / 1440, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) });
}

/** "Tuesday 8 September" in the scope's zone. */
function dayLine(iso: string, zone: string, locale: Locale): string {
  const parts = new Intl.DateTimeFormat(INTL[locale], { weekday: 'long', day: 'numeric', month: 'long', timeZone: zone }).formatToParts(new Date(iso));
  const get = (type: string) => parts.find(p => p.type === type)?.value ?? '';
  const text = `${get('weekday')} ${get('day')} ${get('month')}`;
  return text.charAt(0).toUpperCase() + text.slice(1);
}

const sameDay = (a: string, b: string, zone: string) => {
  const f = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' });
  return f.format(new Date(a)) === f.format(new Date(b));
};

type Filter = { province?: string; market?: string };

/**
 * The console overview (S-91; redesign § 2.3 → BO:263 dashboard): the page head with the day and the place filters,
 * the KPI strip of the last 7 days, GMV of the last 12 weeks (goods vs services), the "To do" queues, what is live now
 * and system health — for every province, one, or one market (region model). Every staff role opens it.
 */
export function Overview() {
  const t = useOverviewT();
  const search = useSearch({ strict: false }) as Filter;
  const filter = { province: search.province, market: search.market };
  const query = useQuery(overviewQuery(filter));
  const { locale } = useLocale();
  const head = (sub?: string) => <BoHeader title={t('title')} sub={sub} actions={<PlaceFilters to={SCREEN_PATH.overview} filter={filter} />} />;
  if (query.isPending) return <div className="bo-page">{head()}<PageSkeleton kpis={6} rows={6} /></div>;
  if (query.isError && !query.data) {
    const bad = query.error instanceof ValidationError;
    return <div className="bo-page">{head()}{bad ? <p role="alert" className="nl-ov-note">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  return <div className="bo-page">{head(t('live', { date: dayLine(data.asOf, data.timeZone, locale) }))}<OverviewView data={data} /></div>;
}

function OverviewView({ data }: { data: OverviewData }) {
  const t = useOverviewT();
  const { locale } = useLocale();
  const go = useGo();
  const k = data.kpis;
  const zone = data.timeZone;
  const change = k.previousGmvCents > 0 ? (k.gmvCents - k.previousGmvCents) / k.previousGmvCents : undefined;
  const volume = k.orders + k.bookings;
  const signed = (r: number) => `${r > 0 ? '+' : r < 0 ? '−' : ''}${pct(Math.abs(r), locale, 0)}`;
  const kpis: KpiItem[] = [
    { key: 'gmv', label: t('kpiGmv'), value: compactMoney(k.gmvCents, locale),
      delta: change === undefined ? undefined : t('kpiGmvChange', { change: signed(change) }), trend: change === undefined || change === 0 ? 'flat' : change > 0 ? 'up' : 'down' },
    { key: 'revenue', label: t('kpiRevenue'), value: compactMoney(k.revenueCents, locale), delta: k.gmvCents > 0 ? t('kpiRevenueTake', { take: pct(k.revenueCents / k.gmvCents, locale) }) : undefined },
    { key: 'volume', label: t('kpiVolume'), value: formatNumber(volume, locale), delta: volume > 0 ? t('kpiVolumeGoods', { share: pct(k.orders / volume, locale, 0) }) : undefined, onClick: go('orders') },
    { key: 'onTime', label: t('kpiOnTime'), value: k.onTimeRatio == null ? t('unknown') : pct(k.onTimeRatio, locale), delta: t('kpiOnTimeTarget'),
      trend: k.onTimeRatio == null ? 'flat' : k.onTimeRatio >= 0.95 ? 'up' : 'down', onClick: go('delivery') },
    { key: 'disputes', label: t('kpiDisputes'), value: k.disputeRate == null ? t('unknown') : pct(k.disputeRate, locale), delta: t('kpiDisputesTarget'),
      trend: k.disputeRate == null ? 'flat' : k.disputeRate < 0.012 ? 'up' : 'down', onClick: go('disputes') },
    { key: 'fee', label: t('kpiFee'), value: k.averageDeliveryFeeCents == null ? t('unknown') : formatMoney(k.averageDeliveryFeeCents, locale) },
  ];
  return (
    <>
      <KpiStrip label={t('kpis')} items={kpis} />
      <div className="bo-grid">
        <ChartCard id="ov-gmv" title={t('gmvTitle')}>
          <StackedBarChart title={t('gmvTitle')} format={c => compactMoney(c, locale)}
            series={[{ key: 'goods', label: t('goods'), color: 'var(--color-action-primary)' }, { key: 'services', label: t('services'), color: 'var(--color-action-accent)' }]}
            data={data.weeks.map((w, i) => ({ label: t('week', { n: i + 1 }), values: { goods: w.goodsCents, services: w.servicesCents } }))} />
        </ChartCard>
        <WorkQueue data={data} />
        <ChartCard id="ov-live" title={t('liveTitle')}>
          <FactList rows={[
            [t('couriers'), `${formatNumber(data.live.couriersOnRuns, locale)} / ${formatNumber(data.live.couriersActive, locale)}`],
            [t('providers'), formatNumber(data.live.providersOnJobs, locale)],
            ...data.live.pools.map(p => [t(sameDay(p.startsAt, data.asOf, zone) ? 'poolTonight' : 'poolTomorrow', { city: p.city }),
              t('poolValue', { n: formatNumber(p.orders, locale), time: formatDate(p.closesAt, locale, 'time', zone) })] as const),
            [t('escrow'), formatMoney(data.live.escrowHeldCents, locale, { whole: true })],
          ]} />
        </ChartCard>
        <ChartCard id="ov-health" title={t('healthTitle')}>
          <ul className="nl-ov-health">
            {data.health.map(h => (
              <li key={h.key} className="nl-ov-tile">
                <span className="nl-ov-tile-head"><span>{t(`h_${h.key}` as OverviewKey)}</span><span className="nl-ov-dot" data-status={h.status} role="img" aria-label={t(h.status === 'ok' ? 'statusOk' : h.status === 'degraded' ? 'statusDegraded' : 'statusUnknown')} /></span>
                <span className="nl-ov-tile-value">{healthValue(h, t, locale)}</span>
              </li>
            ))}
          </ul>
        </ChartCard>
      </div>
    </>
  );
}

/** Opens a screen the active role opens; undefined otherwise (the figure stays, without the way in). */
function useGo(): (screen: ScreenKey) => (() => void) | undefined {
  const navigate = useNavigate();
  const me = useQuery(meQuery).data;
  const grant = useActiveGrant(me ?? { userId: '', roles: [] });
  return screen => (opens(grant, screen) ? () => void navigate({ to: SCREEN_PATH[screen] }) : undefined);
}

function healthValue(h: OverviewData['health'][number], t: OverviewT, locale: Locale): string {
  if (h.key === 'courier_app') return h.value ? t('offline', { n: formatNumber(h.value, locale) }) : t('allOnline');
  if (h.value == null) return t('unknown');
  switch (h.key) {
    case 'api_p95': case 'search_p95': return t('ms', { n: formatNumber(Math.round(h.value), locale) });
    case 'kafka_lag': return t('lag', { n: formatNumber(Math.round(h.value), locale) });
    case 'stripe': return h.status === 'degraded' ? t('degraded') : t('ok');
    case 'tracking_streams': return t('conns', { n: formatNumber(h.value, locale, { notation: 'compact', maximumFractionDigits: 1 }).replace('K', 'k') });
    default: return formatNumber(h.value, locale);
  }
}

/** The "To do" card (BO:273): each queue's count, what it is and its SLA; a row opens its screen when the role may. */
function WorkQueue({ data }: { data: OverviewData }) {
  const t = useOverviewT();
  const { locale } = useLocale();
  const go = useGo();
  const q = data.workQueue;
  const since = (iso: string | null | undefined) => (iso ? age(iso, data.asOf, t, locale) : undefined);
  const rows: { screen: ScreenKey; icon: Icon; n: number; what: OverviewKey; note: string }[] = [
    { screen: 'verify', icon: IdentificationCard, n: q.verifications.count, what: 'q_verifications', note: since(q.verifications.oldest) ? t('sla_verifications', { age: since(q.verifications.oldest)! }) : t('sla_verifications_none') },
    { screen: 'vetting', icon: ListChecks, n: q.flaggedListings.count, what: 'q_flagged', note: since(q.flaggedListings.oldest) ? t('sla_flagged', { age: since(q.flaggedListings.oldest)! }) : t('sla_flagged_none') },
    { screen: 'disputes', icon: Scales, n: q.disputes.count, what: 'q_disputes', note: since(q.disputes.oldest) ? t('sla_disputes', { age: since(q.disputes.oldest)! }) : t('sla_disputes_none') },
    { screen: 'delivery', icon: Truck, n: q.stuckRuns.count, what: 'q_stuck', note: since(q.stuckRuns.oldestOverdue) ?? '' },
    { screen: 'trust', icon: ShieldWarning, n: q.trustFlags.count, what: 'q_flags', note: q.trustFlags.offPlatformPayment ? t('flags_offPlatform') : '' },
    { screen: 'sellers', icon: UsersThree, n: q.sellersBelowFloor, what: 'q_floor', note: t('floor_note') },
  ];
  const items: TodoItem[] = rows.map(r => ({
    key: r.screen, icon: r.icon, meta: formatNumber(r.n, locale), onClick: go(r.screen),
    text: <>{t(r.what, { n: r.n })}{r.note ? <span className="bo-todo-sub">{r.note}</span> : null}</>,
  }));
  return <TodoCard id="ov-todo" title={t('queueTitle')} items={items} empty="" />;
}
