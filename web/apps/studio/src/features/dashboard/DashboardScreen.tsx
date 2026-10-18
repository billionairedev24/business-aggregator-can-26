import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ArrowUUpLeft, ChatCircleText, FileText, Package, ShieldWarning, Storefront, Tag, Warning } from '@phosphor-icons/react';
import { ErrorState, LineChart, PageSkeleton, StackedBarChart, useFormatters, useLocale, type Locale, timeZone, ChartCard, KpiStrip, TodoCard, BoHeader, type KpiItem, type TodoItem } from '@northline/ui';
import { useMerchant, useMerchantId, type MerchantType } from '../shell/api';
import { screenHref, type ScreenKey } from '../shell/nav';
import { clock, clockWithPeriod } from '../../lib/time';
import { listingsQuery } from '../catalogue/api';
import { storefrontQuery } from '../storefront/api';
import { dashboardQuery, type Dashboard } from './api';
import { useDashboardT } from './messages';
import './Dashboard.css';

type T = ReturnType<typeof useDashboardT>;
type Fmt = ReturnType<typeof useFormatters>;
export type DashboardKind = 'provider' | 'seller' | 'both';

/** Portal variant: provider-only, seller-only or both (kitchens have no dashboard). */
const variant = (type: MerchantType): DashboardKind => (type === 'seller' ? 'seller' : type === 'both' ? 'both' : 'provider');

/** What a newly approved business still has to set up (§ 3.10: page and first listings come after approval). */
export interface Setup { page: 'none' | 'draft' | 'live' | 'unknown'; listings: number | null }

export function DashboardScreen() {
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const q = useQuery(dashboardQuery(merchantId));
  const page = useQuery(storefrontQuery(merchantId));
  const listings = useQuery(listingsQuery(merchantId));
  const t = useDashboardT();
  if (q.isPending) return <PageSkeleton kpis={4} rows={6} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const setup: Setup = {
    page: page.isSuccess ? (page.data === null ? 'none' : page.data.publishedAt ? 'live' : 'draft') : 'unknown',
    listings: listings.isSuccess ? listings.data.length : null,
  };
  return <DashboardView data={q.data} kind={variant(merchant.type)} city={merchant.city ?? ''} merchantId={merchantId} setup={setup} />;
}

/**
 * Dashboard (redesign § 3.9, AfroTransact BO:263 + DashCharts, PH:53 for providers): the KPI strip, the charts and the
 * to-do cards — figures only, no generated headline, no coaching paragraph. The quality score is one KPI card.
 */
export function DashboardView({ data, kind, city, merchantId, setup }: { data: Dashboard; kind: DashboardKind; city: string; merchantId: string; setup?: Setup }) {
  const t = useDashboardT();
  const f = useFormatters();
  const { locale } = useLocale();
  const nav = useNavigate();
  const go = (k: ScreenKey) => () => void nav({ to: screenHref(merchantId, k) });
  const prov = kind !== 'seller', sell = kind !== 'provider';
  const e = data.earnings;
  const series = [
    ...(prov ? [{ key: 'services', label: t('services'), color: 'var(--color-action-primary)' }] : []),
    ...(sell ? [{ key: 'products', label: t(kind === 'both' ? 'parts' : 'products'), color: 'var(--color-action-accent)' }] : []),
  ];
  const weeks = e.weeks.map((w, i) => ({ label: weekLabel(w.start, i, locale), values: { services: w.servicesCents, products: w.partsCents } }));
  const totals = e.weeks.map(w => (prov ? w.servicesCents : 0) + (sell ? w.partsCents : 0));

  return (
    <div className="nl-dash">
      <BoHeader title={t('title')} sub={[longDate(data.today, locale), city].filter(Boolean).join(' · ')} />
      <KpiStrip label={t('kpis')} items={kpis(data, kind, t, f, locale, go)} />

      <div className="bo-grid nl-dash-charts">
        <ChartCard id="dash-earn" title={t('earningsTitle')}>
          <StackedBarChart title={t('earningsTitle')} series={series} data={weeks} format={c => f.money(c, { whole: true })} />
        </ChartCard>
        <ChartCard id="dash-trend" title={t('trendTitle')}>
          <LineChart title={t('trendTitle')} current={totals} labels={weeks.map(w => w.label)} legend={{ current: t('trendLegend') }} format={c => f.money(c, { whole: true })} />
        </ChartCard>
      </div>

      <div className="bo-grid">
        {setup ? <SetupCard setup={setup} kind={kind} t={t} go={go} /> : null}
        <ChartCard id="dash-today" title={kind === 'seller' ? (data.counts.runCutoff ? t('tonightRun', { time: clockWithPeriod(data.counts.runCutoff, locale) }) : t('nextRun')) : t('today')}>
          {kind === 'seller' ? <RunList data={data} t={t} /> : <TodayList data={data} t={t} f={f} locale={locale} />}
        </ChartCard>
        <TodoCard id="dash-needs" title={t('needsYou')} items={needsYou(data, { prov, sell, sellerOnly: kind === 'seller' }, t, f, locale, go)} empty={t('allCaughtUp')} />
      </div>
    </div>
  );
}

function kpis(d: Dashboard, kind: DashboardKind, t: T, f: Fmt, locale: Locale, go: (k: ScreenKey) => () => void): KpiItem[] {
  const e = d.earnings, c = d.counts, r = d.reputation;
  const items: KpiItem[] = [{ key: 'net', label: t('netMonth'), value: f.money(e.netThisMonthCents, { whole: true }), ...netDelta(e, d.today, t, locale), onClick: go('earnings') }];
  if (kind !== 'seller') items.push({ key: 'jobs', label: t('jobsMonth'), value: f.number(c.jobsThisMonth), delta: t('visitsTodayDelta', { count: c.visitsToday }), onClick: go('appointments') });
  if (kind !== 'provider') items.push({ key: 'orders', label: t('ordersMonth'), value: f.number(c.ordersThisMonth), delta: t('itemsDelta', { items: c.itemsThisMonth }), onClick: go('orders') });
  if (kind === 'provider') items.push({ key: 'quotes', label: t('quotesOpen'), value: f.number(c.quoteRequestsOpen), onClick: go('appointments') });
  else items.push({ key: 'pack', label: t('toPack'), value: f.number(c.toPack), delta: c.runLabel ? t('runDelta', { run: c.runLabel }) : undefined, onClick: go('orders') });
  items.push({
    key: 'releasing', label: t('releasingKpi'), value: e.releasingCents != null ? f.money(e.releasingCents, { whole: true }) : '—',
    delta: e.releasingCents != null && e.releasingAt ? new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'long', timeZone: timeZone() }).format(new Date(e.releasingAt)) : t('nothingReleasing'),
    onClick: go('payouts'),
  });
  items.push({
    key: 'rating', label: t('ratingKpi'), value: r.rating != null ? f.number(r.rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) : '—',
    delta: r.rating == null ? t('noRating') : t(kind === 'seller' ? 'ratingOrders' : 'ratingReviews', { count: f.number(r.reviews) }),
  });
  items.push(kind === 'seller'
    ? { key: 'refunds', label: t('refundKpi'), value: r.refundRateBps != null ? f.number(r.refundRateBps / 10000, { style: 'percent', maximumFractionDigits: 1 }) : '—', onClick: go('refunds') }
    : { key: 'quality', label: t('qualityKpi'), value: r.qualityScore != null ? f.number(r.qualityScore) : '—', delta: r.qualityScore == null ? t('qualityPending') : t('qualityOutOf') });
  return items;
}

/** "+18% vs Aug" in green, "−4% vs Aug" in red. */
function netDelta(e: Dashboard['earnings'], today: string, t: T, locale: Locale): Pick<KpiItem, 'delta' | 'trend'> {
  if (e.netLastMonthCents <= 0) return {};
  const pct = Math.round(((e.netThisMonthCents - e.netLastMonthCents) / e.netLastMonthCents) * 100);
  const month = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(2000, Number(today.slice(5, 7)) - 2, 15))).replace('.', '');
  return { delta: t('vsMonth', { pct: `${pct >= 0 ? '+' : '−'}${Math.abs(pct)}%`, month }), trend: pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat' };
}

/** "Tuesday 8 September" / « mardi 8 septembre ». */
function longDate(date: string, locale: Locale): string {
  const d = new Date(`${date}T12:00:00Z`);
  const p = (o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { ...o, timeZone: 'UTC' }).format(d);
  return `${p({ weekday: 'long' })} ${p({ day: 'numeric' })} ${p({ month: 'long' })}`;
}

/** A week's start as "Sep 8" (the axis of both charts); W1… when the day is missing. */
function weekLabel(start: string, i: number, locale: Locale): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(start)) return `W${i + 1}`;
  return new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${start}T12:00:00Z`)).replace('.', '');
}

function SetupCard({ setup, kind, t, go }: { setup: Setup; kind: DashboardKind; t: T; go: (k: ScreenKey) => () => void }) {
  const items: TodoItem[] = [];
  if (setup.page === 'none' || setup.page === 'draft') items.push({ key: 'page', icon: Storefront, text: t(setup.page === 'none' ? `setupPage_${kind}` : `publishPage_${kind}`), onClick: go('storefront') });
  if (setup.listings === 0) items.push({ key: 'listings', icon: Tag, text: t(`setupListings_${kind}`), onClick: go('products') });
  if (!items.length) return null;
  return <TodoCard id="dash-setup" title={t('setupTitle')} items={items} empty="" />;
}

function TodayList({ data, t, f, locale }: { data: Dashboard; t: T; f: Fmt; locale: Locale }) {
  if (data.jobsToday.length === 0) return <p className="bo-muted">{t('nothingToday')}</p>;
  return (
    <ul className="nl-dash-list">
      {data.jobsToday.map(j => (
        <li key={j.id} className="nl-dash-row">
          <span className="nl-dash-time">{clock(j.startsAt, locale)}</span>
          <span className="nl-dash-what"><strong>{j.title}</strong><span className="nl-dash-who">{[j.customerName, j.area, j.access, j.escrowHeldCents != null ? t('escrow', { money: f.money(j.escrowHeldCents) }) : null].filter(Boolean).join(' · ')}</span></span>
          {j.mine || !j.memberName
            ? <span className="tag tag-neutral nl-dash-tag">{t(`state_${j.state}` as Parameters<T>[0])}</span>
            : <span className="tag tag-brand nl-dash-tag">{j.memberName}</span>}
        </li>
      ))}
    </ul>
  );
}

function RunList({ data, t }: { data: Dashboard; t: T }) {
  if (data.run.length === 0) return <p className="bo-muted">{t('nothingToPack')}</p>;
  return (
    <ul className="nl-dash-list">
      {data.run.map(o => (
        <li key={o.orderId} className="nl-dash-row">
          <span className="nl-dash-time">{o.runLabel ?? '—'}</span>
          <span className="nl-dash-what"><strong>{[o.items.map(i => (i.qty > 1 ? `${i.title} ×${i.qty}` : i.title)).join(', '), o.ref].filter(Boolean).join(' · ')}</strong><span className="nl-dash-who">{[o.customerName, o.area].filter(Boolean).join(' · ')}</span></span>
          <span className={`tag ${o.packed ? 'tag-neutral' : 'tag-warning'} nl-dash-tag`}>{o.packed ? t('packedTag') : t('toPackTag')}</span>
        </li>
      ))}
    </ul>
  );
}

function needsYou(d: Dashboard, p: { prov: boolean; sell: boolean; sellerOnly: boolean }, t: T, f: Fmt, locale: Locale, go: (k: ScreenKey) => () => void): TodoItem[] {
  const rows: TodoItem[] = [];
  const c = d.counts;
  if (p.prov && c.quoteRequestsOpen > 0) {
    const left = c.quoteRespondBy ? Math.round((new Date(c.quoteRespondBy).getTime() - Date.now()) / 60000) : -1;
    rows.push({ key: 'quotes', icon: ChatCircleText, onClick: go('appointments'), text: left > 0 ? t('quoteRequests', { count: c.quoteRequestsOpen, left: `${Math.floor(left / 60)} h ${left % 60} m` }) : t('quoteRequestsLate', { count: c.quoteRequestsOpen }) });
  }
  if (p.sell && c.toPack > 0) rows.push({ key: 'pack', icon: Package, onClick: go('orders'), text: c.runCutoff && c.runLabel ? t('ordersToPack', { count: c.toPack, run: c.runLabel, time: clockWithPeriod(c.runCutoff, locale) }) : t('ordersToPackNoRun', { count: c.toPack }) });
  const cases = d.cases.filter(x => (p.sellerOnly ? x.kind === 'refund' : true));
  const first = cases[0];
  if (first) rows.push({ key: 'case', icon: first.kind === 'refund' ? ArrowUUpLeft : Warning, onClick: go('refunds'), text: t(first.kind === 'refund' ? 'refundCase' : 'dispute', { count: cases.filter(x => x.kind === first.kind).length, who: first.customerName ?? '—', what: (first.subject ?? '').toLowerCase() }) });
  const low = d.lowStock[0];
  if (p.sell && low) rows.push({ key: 'stock', icon: Tag, onClick: go('products'), text: t('lowStock', { name: low.name, stock: low.stock }) });
  const doc = d.compliance[0];
  if (doc) {
    const name = t(`doc_${doc.checkType}` as Parameters<T>[0]);
    const days = doc.pausesAt ? Math.ceil((new Date(doc.pausesAt).getTime() - Date.now()) / 86400000) : 0;
    rows.push({ key: 'doc', icon: doc.status === 'expired' ? ShieldWarning : FileText, onClick: go('compliance'), text: doc.status === 'expired' && doc.expiresAt
      ? (days > 0 ? t('complianceExpired', { doc: name, date: f.date(doc.expiresAt), days }) : t('complianceExpiredPaused', { doc: name, date: f.date(doc.expiresAt) }))
      : t('complianceTodo', { doc: name }) });
  }
  return rows;
}
