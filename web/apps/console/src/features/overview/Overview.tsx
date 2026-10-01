import { useQuery } from '@tanstack/react-query';
import { Link, useSearch } from '@tanstack/react-router';
import { ErrorState, formatDate, formatMoney, formatNumber, Kpi, KpiRow, PageSkeleton, StackedBarChart, useLocale, type Locale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { meQuery, opens, useRegions, type Regions } from '../shell/api';
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

/**
 * The console overview (S-91, design 03 `overview`): the day, the headline, six KPIs of the last 7 days, GMV of the
 * last 12 weeks (goods vs services), system health, the work queue and what is live now — for every province, one, or
 * one market (region model). Every staff role opens it.
 */
export function Overview() {
  const t = useOverviewT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as { province?: string; market?: string };
  const filter = { province: search.province, market: search.market };
  const query = useQuery(overviewQuery(filter));
  const regions = useRegions(locale).data;
  if (query.isPending) return <PageSkeleton kpis={6} rows={6} />;
  if (query.isError && !query.data) {
    const bad = query.error instanceof ValidationError;
    return <><Filters filter={filter} regions={regions} />{bad ? <p role="alert" className="nl-ov-note">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</>;
  }
  return <OverviewView data={query.data!} filter={filter} regions={regions} />;
}

function OverviewView({ data, filter, regions }: { data: OverviewData; filter: { province?: string; market?: string }; regions: Regions | undefined }) {
  const t = useOverviewT();
  const { locale } = useLocale();
  const k = data.kpis;
  const zone = data.timeZone;
  const change = k.previousGmvCents > 0 ? (k.gmvCents - k.previousGmvCents) / k.previousGmvCents : undefined;
  const volume = k.orders + k.bookings;
  const signed = (r: number) => `${r > 0 ? '+' : r < 0 ? '−' : ''}${pct(Math.abs(r), locale, 0)}`;
  return (
    <div className="nl-ov">
      <div className="nl-ov-top">
        <span className="nl-ov-kicker">{t('live', { date: dayLine(data.asOf, zone, locale) })}</span>
        <Filters filter={filter} regions={regions} />
      </div>
      <h1 className="nl-ov-title">{t('headline', { gmv: compactMoney(data.headline.gmvCents, locale), sellers: data.headline.sellers, verifications: data.headline.verifications, disputes: data.headline.disputes })}</h1>
      <KpiRow>
        <Kpi value={compactMoney(k.gmvCents, locale)} label={change === undefined ? t('kpiGmv') : t('kpiGmvChange', { change: signed(change) })} />
        <Kpi value={compactMoney(k.revenueCents, locale)} label={k.gmvCents > 0 ? t('kpiRevenueTake', { take: pct(k.revenueCents / k.gmvCents, locale) }) : t('kpiRevenue')} />
        <Kpi value={formatNumber(volume, locale)} label={volume > 0 ? t('kpiVolumeGoods', { share: pct(k.orders / volume, locale, 0) }) : t('kpiVolume')} />
        <Kpi value={k.onTimeRatio == null ? t('unknown') : pct(k.onTimeRatio, locale)} label={t('kpiOnTime')} />
        <Kpi value={k.disputeRate == null ? t('unknown') : pct(k.disputeRate, locale)} label={t('kpiDisputes')} />
        <Kpi value={k.averageDeliveryFeeCents == null ? t('unknown') : formatMoney(k.averageDeliveryFeeCents, locale)} label={t('kpiFee')} />
      </KpiRow>
      <div className="nl-ov-cols">
        <div>
          <h2 className="nl-ov-h2">{t('gmvTitle')}</h2>
          <div className="nl-ov-sub">{t('gmvSub')}</div>
          <StackedBarChart title={t('gmvTitle')} format={c => compactMoney(c, locale)}
            series={[{ key: 'goods', label: t('goods'), color: 'var(--color-accent)' }, { key: 'services', label: t('services'), color: 'var(--color-accent-2-400)' }]}
            data={data.weeks.map((w, i) => ({ label: t('week', { n: i + 1 }), values: { goods: w.goodsCents, services: w.servicesCents } }))} />
          <h2 className="nl-ov-h2 nl-ov-gap">{t('healthTitle')}</h2>
          <div className="nl-ov-health">
            {data.health.map(h => (
              <div key={h.key} className="nl-ov-tile">
                <div className="nl-ov-tile-head"><span>{t(`h_${h.key}` as OverviewKey)}</span><span className="nl-ov-dot" data-status={h.status} role="img" aria-label={t(h.status === 'ok' ? 'statusOk' : h.status === 'degraded' ? 'statusDegraded' : 'statusUnknown')} /></div>
                <div className="nl-ov-tile-value">{healthValue(h, t, locale)}</div>
              </div>
            ))}
          </div>
        </div>
        <div>
          <h2 className="nl-ov-h2">{t('queueTitle')}</h2>
          <WorkQueue data={data} />
          <h2 className="nl-ov-h2 nl-ov-gap">{t('liveTitle')}</h2>
          <dl className="nl-ov-live">
            <div><dt>{t('couriers')}</dt><dd>{formatNumber(data.live.couriersOnRuns, locale)} / {formatNumber(data.live.couriersActive, locale)}</dd></div>
            <div><dt>{t('providers')}</dt><dd>{formatNumber(data.live.providersOnJobs, locale)}</dd></div>
            {data.live.pools.map(p => (
              <div key={p.market}>
                <dt>{t(sameDay(p.startsAt, data.asOf, zone) ? 'poolTonight' : 'poolTomorrow', { city: p.city })}</dt>
                <dd>{t('poolValue', { n: formatNumber(p.orders, locale), time: formatDate(p.closesAt, locale, 'time', zone) })}</dd>
              </div>
            ))}
            <div><dt>{t('escrow')}</dt><dd>{formatMoney(data.live.escrowHeldCents, locale, { whole: true })}</dd></div>
          </dl>
        </div>
      </div>
    </div>
  );
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

function WorkQueue({ data }: { data: OverviewData }) {
  const t = useOverviewT();
  const { locale } = useLocale();
  const me = useQuery(meQuery).data;
  const grant = useActiveGrant(me ?? { userId: '', roles: [] });
  const q = data.workQueue;
  const since = (iso: string | null | undefined) => (iso ? age(iso, data.asOf, t, locale) : undefined);
  const items: { screen: ScreenKey; n: number; what: OverviewKey; note: string }[] = [
    { screen: 'verify', n: q.verifications.count, what: 'q_verifications', note: since(q.verifications.oldest) ? t('sla_verifications', { age: since(q.verifications.oldest)! }) : t('sla_verifications_none') },
    { screen: 'vetting', n: q.flaggedListings.count, what: 'q_flagged', note: since(q.flaggedListings.oldest) ? t('sla_flagged', { age: since(q.flaggedListings.oldest)! }) : t('sla_flagged_none') },
    { screen: 'disputes', n: q.disputes.count, what: 'q_disputes', note: since(q.disputes.oldest) ? t('sla_disputes', { age: since(q.disputes.oldest)! }) : t('sla_disputes_none') },
    { screen: 'delivery', n: q.stuckRuns.count, what: 'q_stuck', note: since(q.stuckRuns.oldestOverdue) ?? '' },
    { screen: 'trust', n: q.trustFlags.count, what: 'q_flags', note: q.trustFlags.offPlatformPayment ? t('flags_offPlatform') : '' },
    { screen: 'sellers', n: q.sellersBelowFloor, what: 'q_floor', note: t('floor_note') },
  ];
  return (
    <ul className="nl-ov-queue">
      {items.map(i => {
        const body = <><span><strong>{formatNumber(i.n, locale)}</strong> {t(i.what, { n: i.n })}</span><span className="nl-ov-sla">{i.note}</span></>;
        // a role that can't open the screen sees the count without a link (the api refuses the screen anyway)
        return <li key={i.screen}>{opens(grant, i.screen) ? <Link to={SCREEN_PATH[i.screen]} className="nl-ov-item">{body}</Link> : <div className="nl-ov-item">{body}</div>}</li>;
      })}
    </ul>
  );
}

/** Province and market from the region model (`?province=&market=`; S-91 region-aware). */
function Filters({ filter }: { filter: { province?: string; market?: string }; regions?: Regions | undefined }) {
  return <PlaceFilters to={SCREEN_PATH.overview} filter={filter} />;
}
