import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { IdentificationCard, ListChecks, Scales, ShieldWarning, Truck, UsersThree, type Icon } from '@phosphor-icons/react';
import {
  ActionCard, ActionGrid, BoHeader, ChartCard, ErrorState, FactList, formatCompactMoney, formatDate, formatNumber, formatPrice, KpiStrip, PageSkeleton, StackedBarChart, useLocale,
  type KpiItem, type Locale, type StatusTone,
} from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { meQuery, opens } from '../shell/api';
import { useActiveGrant } from '../shell/ConsoleLayout';
import { usePlace } from '../shell/scope';
import { SCREEN_PATH, type ScreenKey } from '../shell/screens';
import { overviewQuery, type Overview as OverviewData } from './api';
import { useOverviewT, type OverviewKey, type OverviewT } from './messages';
import './overview.css';

const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };

/** "$212k", "$24.1k", "$2.71": the shared compact format (S-177: one money format across the console). */
export const compactMoney = (cents: number, locale: Locale): string => formatCompactMoney(cents, locale);

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
 * The console overview (S-91; E-15 S-177): the page head with the day, the KPI strip of the last 7 days, the queues that
 * need attention as action cards (most urgent first, one button each into its screen), GMV of the last 12 weeks
 * (goods vs services), what is live now and system health — for the top bar's scope (every market, a province, a
 * market; region model). Every staff role opens it.
 */
export function Overview() {
  const t = useOverviewT();
  const filter = usePlace();
  const query = useQuery(overviewQuery(filter));
  const { locale } = useLocale();
  const head = (sub?: string) => <BoHeader title={t('title')} sub={sub} />;
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
    { key: 'fee', label: t('kpiFee'), value: k.averageDeliveryFeeCents == null ? t('unknown') : formatPrice(k.averageDeliveryFeeCents, locale) },
  ];
  return (
    <>
      <KpiStrip label={t('kpis')} items={kpis} />
      <WorkQueue data={data} />
      <div className="nl-ov-wrap"><div className="nl-ov-grid">
        {/* E-15 colour roles: chart series are ink and the 3:1 grey, never the action colour */}
        <ChartCard id="ov-gmv" title={t('gmvTitle')} className="nl-ov-gmv">
          <StackedBarChart title={t('gmvTitle')} height={240} format={c => compactMoney(c, locale)}
            series={[{ key: 'goods', label: t('goods'), color: 'var(--color-selected)' }, { key: 'services', label: t('services'), color: 'var(--color-border-control)' }]}
            data={data.weeks.map((w, i) => ({ label: t('week', { n: i + 1 }), values: { goods: w.goodsCents, services: w.servicesCents } }))} />
        </ChartCard>
        <ChartCard id="ov-live" title={t('liveTitle')} className="nl-ov-live">
          <FactList rows={[
            [t('couriers'), `${formatNumber(data.live.couriersOnRuns, locale)} / ${formatNumber(data.live.couriersActive, locale)}`],
            [t('providers'), formatNumber(data.live.providersOnJobs, locale)],
            ...data.live.pools.map(p => [t(sameDay(p.startsAt, data.asOf, zone) ? 'poolTonight' : 'poolTomorrow', { city: p.city }),
              t('poolValue', { n: formatNumber(p.orders, locale), time: formatDate(p.closesAt, locale, 'time', zone) })] as const),
            [t('escrow'), formatPrice(data.live.escrowHeldCents, locale)],
          ]} />
        </ChartCard>
        <ChartCard id="ov-health" title={t('healthTitle')} className="nl-ov-hc">
          <ul className="nl-ov-health">
            {data.health.map(h => (
              <li key={h.key} className="nl-ov-tile">
                <span className="nl-ov-tile-head"><span>{t(`h_${h.key}` as OverviewKey)}</span><span className="nl-ov-dot" data-status={h.status} role="img" aria-label={t(h.status === 'ok' ? 'statusOk' : h.status === 'degraded' ? 'statusDegraded' : 'statusUnknown')} /></span>
                <span className="nl-ov-tile-value">{healthValue(h, t, locale)}</span>
              </li>
            ))}
          </ul>
        </ChartCard>
      </div></div>
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

const RANK: Record<StatusTone, number> = { danger: 0, warning: 1, info: 2, success: 3, neutral: 4 };
const olderThan = (iso: string | null | undefined, asOf: string, hours: number) => !!iso && Date.parse(asOf) - Date.parse(iso) > hours * 3_600_000;

/**
 * "Needs attention" (E-15 S-177, the console's ActionGrid): one card per queue with work in it — the job and its count,
 * the SLA and the oldest item, one button into the screen. Past its SLA (or a stuck run, an off-platform payment) a
 * card is urgent; with work inside its SLA it is due soon; sellers below a floor are to know. Most urgent first, its
 * button primary. A queue the active role can't open is left out (the api refuses it too).
 */
function WorkQueue({ data }: { data: OverviewData }) {
  const t = useOverviewT();
  const { locale } = useLocale();
  const go = useGo();
  const q = data.workQueue;
  const since = (iso: string | null | undefined) => (iso ? age(iso, data.asOf, t, locale) : undefined);
  const cards: { screen: ScreenKey; icon: Icon; n: number; what: OverviewKey; action: OverviewKey; note: string; tone: StatusTone }[] = [
    { screen: 'verify', icon: IdentificationCard, n: q.verifications.count, what: 'n_verifications', action: 'act_verifications',
      note: since(q.verifications.oldest) ? t('sla_verifications', { age: since(q.verifications.oldest)! }) : t('sla_verifications_none'), tone: olderThan(q.verifications.oldest, data.asOf, 48) ? 'danger' : 'warning' },
    { screen: 'vetting', icon: ListChecks, n: q.flaggedListings.count, what: 'n_flagged', action: 'act_flagged',
      note: since(q.flaggedListings.oldest) ? t('sla_flagged', { age: since(q.flaggedListings.oldest)! }) : t('sla_flagged_none'), tone: olderThan(q.flaggedListings.oldest, data.asOf, 4) ? 'danger' : 'warning' },
    { screen: 'disputes', icon: Scales, n: q.disputes.count, what: 'n_disputes', action: 'act_disputes',
      note: since(q.disputes.oldest) ? t('sla_disputes', { age: since(q.disputes.oldest)! }) : t('sla_disputes_none'), tone: olderThan(q.disputes.oldest, data.asOf, 48) ? 'danger' : 'warning' },
    { screen: 'delivery', icon: Truck, n: q.stuckRuns.count, what: 'n_stuck', action: 'act_stuck', note: since(q.stuckRuns.oldestOverdue) ? t('stuck_note', { age: since(q.stuckRuns.oldestOverdue)! }) : '', tone: 'danger' },
    { screen: 'trust', icon: ShieldWarning, n: q.trustFlags.count, what: 'n_flags', action: 'act_flags', note: q.trustFlags.offPlatformPayment ? t('flags_offPlatform') : '', tone: q.trustFlags.offPlatformPayment ? 'danger' : 'warning' },
    { screen: 'sellers', icon: UsersThree, n: q.sellersBelowFloor, what: 'n_floor', action: 'act_floor', note: t('floor_note'), tone: 'info' },
  ];
  const shown = cards.filter(c => c.n > 0 && go(c.screen)).sort((a, b) => RANK[a.tone] - RANK[b.tone]);
  if (!shown.length) return <ChartCard id="ov-todo" title={t('queueTitle')}><p className="bo-muted">{t('nothing')}</p></ChartCard>;
  return (
    <ActionGrid title={t('queueTitle')} count={shown.length}>
      {shown.map((c, i) => (
        <ActionCard key={c.screen} tone={c.tone} icon={c.icon} primary={i === 0} title={t(c.what, { n: c.n })}
          detail={c.note || undefined} action={{ label: t(c.action), href: SCREEN_PATH[c.screen], onClick: go(c.screen) }} />
      ))}
    </ActionGrid>
  );
}
