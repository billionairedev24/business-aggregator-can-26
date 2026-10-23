import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ArrowRight, Pause, Play } from '@phosphor-icons/react';
import {
  ActionCard, ActionGrid, Button, ChartCard, ColumnChart, ErrorState, KpiStrip, PageSkeleton, Segmented, Skeleton, StatusTag, SummaryTable, Thumb,
  formatWhen, useFormatters, useLocale, type SummaryColumn,
} from '@northline/ui';
import { useSession } from '../../lib/session';
import { useMerchant, useMerchantId, type MerchantType } from '../shell/api';
import { screenHref, type ScreenKey } from '../shell/nav';
import { listingsQuery } from '../catalogue/api';
import { payoutOverviewQuery, reportQuery, type Report } from '../finance/api';
import { liveQuery, menuQuery, menusQuery, setupQuery, useKitchenToggle } from '../kitchen/api';
import { threadsQuery } from '../messages/api';
import { ordersQuery } from '../orders/api';
import { storefrontQuery } from '../storefront/api';
import { dashboardQuery } from './api';
import { useDashboardT, type DashboardT } from './messages';
import { actions, dayLine, greeting, kpis, salesSeries, stockTone, storeState, topDishes, topListings, weeklySeries, type DashboardInputs, type DashboardKind, type Range, type TopRow } from './model';
import './Dashboard.css';

export type { DashboardKind, Setup } from './model';

const kindOf = (type: MerchantType): DashboardKind => type;

/** Loads what the dashboard reads (each part optional: a role that can't read it, or a failed call, hides that part). */
export function DashboardScreen() {
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const kind = kindOf(merchant.type);
  const sells = kind === 'seller' || kind === 'both', kitchen = kind === 'kitchen';
  const { data: session } = useSession();
  const q = useQuery(dashboardQuery(merchantId));
  const [range, setRange] = useState<Range>('14d');
  const report = useQuery({ ...reportQuery(merchantId, '30d'), retry: false });
  const year = useQuery({ ...reportQuery(merchantId, '12mo'), retry: false, enabled: range === '12mo' });
  const payout = useQuery({ ...payoutOverviewQuery(merchantId), retry: false });
  const threads = useQuery({ ...threadsQuery(merchantId), retry: false });
  const orders = useQuery({ ...ordersQuery(merchantId), retry: false, enabled: sells });
  const listings = useQuery({ ...listingsQuery(merchantId), retry: false, enabled: !kitchen });
  const page = useQuery({ ...storefrontQuery(merchantId), retry: false });
  const live = useQuery({ ...liveQuery(merchantId), retry: false, enabled: kitchen });
  const kSetup = useQuery({ ...setupQuery(merchantId), retry: false, enabled: kitchen });
  const menus = useQuery({ ...menusQuery(merchantId), retry: false, enabled: kitchen });
  const menuId = menus.data?.find(m => m.status === 'live')?.id ?? menus.data?.[0]?.id;
  const menu = useQuery({ ...menuQuery(merchantId, menuId ?? '_'), retry: false, enabled: kitchen && !!menuId });
  const t = useDashboardT();
  if (q.isPending) return <PageSkeleton kpis={4} rows={6} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const inputs: DashboardInputs = {
    kind, data: q.data, now: new Date(), firstName: session?.user?.firstName, status: merchant.status,
    setup: {
      page: page.isSuccess ? (page.data === null ? 'none' : page.data.publishedAt ? 'live' : 'draft') : 'unknown',
      listings: listings.isSuccess ? listings.data.length : null,
    },
    // the 30 days by day (what the api answers for 30d); anything else is not today's figures
    report: report.data?.granularity === 'day' ? report.data : undefined, payout: payout.data, threads: threads.data, orders: orders.data, listings: listings.data,
    live: live.data, kitchenSetup: kSetup.data, dishes: menu.data?.sections.flatMap(s => s.items),
  };
  return <DashboardView inputs={inputs} merchantId={merchantId} range={range} onRange={setRange} yearReport={year.data?.granularity === 'month' ? year.data : undefined} />;
}

/**
 * The Studio dashboard (E-15, design/revamp/a.html screen 7, Seller Central-style): the greeting with the day's next
 * pickup or booking and the store's state; "Needs you now" — one card per job with its deadline and the one button
 * that does it; the key figures; sales over 14 / 30 days or 12 months; the best sellers (providers: today's bookings,
 * kitchens: today's dishes).
 */
export function DashboardView({ inputs: i, merchantId, range = '14d', onRange, yearReport }: { inputs: DashboardInputs; merchantId: string; range?: Range; onRange?: (r: Range) => void; yearReport?: Report }) {
  const t = useDashboardT();
  const f = useFormatters();
  const { locale } = useLocale();
  const nav = useNavigate();
  const href = (k: ScreenKey, search?: Record<string, string>) => `${screenHref(merchantId, k)}${search ? `?${new URLSearchParams(search)}` : ''}`;
  const go = (k: ScreenKey, search?: Record<string, string>) => () => void nav({ to: screenHref(merchantId, k), ...(search ? { search: search as never } : {}) });
  const cards = actions(i, t, f, locale);
  const store = storeState(i, t, locale);
  const series = i.report ? salesSeries(range === '12mo' ? '12mo' : range, range === '12mo' ? yearReport : i.report, t, f, locale) : weeklySeries(i, t, f, locale);

  return (
    <div className="nl-dash">
      <div className="bo-head nl-dash-head">
        <div className="bo-head-text">
          <h1 className="bo-title">{greeting(i, t)}</h1>
          <p className="bo-sub">{dayLine(i, t, locale)}</p>
        </div>
        <div className="bo-head-actions" role="group" aria-label={t('storeState')}>
          <StatusTag tone={store.tone} size="lg">{store.text}</StatusTag>
          {store.canPause ? <PauseButton merchantId={merchantId} paused={store.paused} t={t} /> : null}
        </div>
      </div>

      <ActionGrid title={t('needsYou')} count={cards.length}>
        {cards.length === 0 ? <li className="nl-dash-clear">{t('allCaughtUp')}</li> : cards.map((c, n) => (
          <ActionCard key={c.key} tone={c.tone} icon={c.icon} title={c.title} detail={c.detail} primary={n === 0}
            action={{ label: c.label, href: href(c.to, c.search), onClick: go(c.to, c.search) }} />
        ))}
      </ActionGrid>

      <KpiStrip label={t('kpis')} items={kpis(i, t, f, locale, k => go(k))} />

      <div className="bo-cols">
        <ChartCard id="dash-sales" title={series?.title ?? t(`sales_${range}`)}
          actions={i.report && onRange ? <Segmented name="dash-range" aria-label={t('range')} value={range} onChange={onRange} options={(['14d', '30d', '12mo'] as const).map(r => ({ value: r, label: t(`r_${r}`) }))} /> : null}>
          {series
            ? <ColumnChart title={series.title} data={series.points} highlightLast legend={series.legend} format={c => f.price(c)} axisFormat={c => f.money(c, { whole: true })} />
            : <Skeleton className="nl-dash-chart-skel" />}
        </ChartCard>
        <SecondPanel i={i} t={t} href={href} go={go} />
      </div>
      {i.kind === 'both' ? <div className="bo-cols"><TodayBookings i={i} t={t} href={href} go={go} /></div> : null}
    </div>
  );
}

type Href = (k: ScreenKey, search?: Record<string, string>) => string;
type Go = (k: ScreenKey, search?: Record<string, string>) => () => void;

function PanelLink({ to, label, href, go }: { to: ScreenKey; label: string; href: Href; go: Go }) {
  return <a className="nl-dash-link" href={href(to)} onClick={e => { if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return; e.preventDefault(); go(to)(); }}>{label}<ArrowRight size={14} aria-hidden /></a>;
}

function SecondPanel({ i, t, href, go }: { i: DashboardInputs; t: DashboardT; href: Href; go: Go }) {
  const f = useFormatters();
  if (i.kind === 'provider') return <TodayBookings i={i} t={t} href={href} go={go} />;
  if (i.kind === 'kitchen') {
    const cols: SummaryColumn<TopRow>[] = [
      { key: 'name', label: t('dish'), render: r => <span className="bo-item"><span>{r.name}</span></span> },
      { key: 'sold', label: t('sold'), align: 'end', render: r => f.number(r.sold) },
      { key: 'sales', label: t('sales'), align: 'end', hideOnPhone: true, render: r => (r.salesCents == null ? '—' : f.price(r.salesCents)) },
      { key: 'status', label: t('status'), align: 'end', render: r => <StatusTag tone={r.soldOut ? 'danger' : 'success'}>{t(r.soldOut ? 'soldOutTag' : 'onMenu')}</StatusTag> },
    ];
    return (
      <ChartCard id="dash-top" title={t('topDishes')} actions={<PanelLink to="menu" label={t('menuBuilder')} href={href} go={go} />}>
        <SummaryTable caption={t('topDishes')} columns={cols} rows={topDishes(i)} rowKey={r => r.id} empty={t('noDishes')} />
      </ChartCard>
    );
  }
  const low = new Set(i.data.lowStock.map(l => l.name));
  const cols: SummaryColumn<TopRow>[] = [
    { key: 'name', label: t('product'), render: r => <span className="bo-item"><Thumb src={r.imageUrl} name={r.name} /><span>{r.name}</span></span> },
    { key: 'sold', label: t('sold'), align: 'end', render: r => f.number(r.sold) },
    { key: 'sales', label: t('sales'), align: 'end', hideOnPhone: true, render: r => (r.salesCents == null ? '—' : f.price(r.salesCents)) },
    { key: 'stock', label: t('stock'), align: 'end', render: r => (r.stock == null ? '—' : <StatusTag tone={stockTone(r, low)}>{r.stock <= 0 ? t('outOfStock') : low.has(r.name) ? t('left', { count: f.number(r.stock) }) : t('inStock', { count: f.number(r.stock) })}</StatusTag>) },
  ];
  return (
    <ChartCard id="dash-top" title={t('topProducts')} actions={<PanelLink to="products" label={t('allProducts')} href={href} go={go} />}>
      <SummaryTable caption={t('topProducts')} columns={cols} rows={topListings(i, 'product')} rowKey={r => r.id} empty={t('noSales')} />
    </ChartCard>
  );
}

function TodayBookings({ i, t, href, go }: { i: DashboardInputs; t: DashboardT; href: Href; go: Go }) {
  const { locale } = useLocale();
  const jobs = [...i.data.jobsToday].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  type Job = (typeof jobs)[number];
  const cols: SummaryColumn<Job>[] = [
    { key: 'time', label: t('time'), render: j => <span className="nl-dash-time">{formatWhen(j.startsAt, locale, 'time')}</span> },
    { key: 'what', label: t('booking'), render: j => <span className="nl-dash-what"><strong>{j.title}</strong><span className="nl-dash-who">{[j.customerName, j.area, j.mine || !j.memberName ? null : j.memberName].filter(Boolean).join(' · ')}</span></span> },
    { key: 'state', label: t('status'), align: 'end', render: j => <StatusTag tone={j.state === 'requested' ? 'warning' : j.state === 'disputed' ? 'danger' : j.state === 'cancelled' ? 'neutral' : j.state === 'confirmed' ? 'info' : 'success'}>{t(`state_${j.state}` as Parameters<DashboardT>[0])}</StatusTag> },
  ];
  return (
    <ChartCard id="dash-today" title={t('todayBookings')} actions={<PanelLink to="appointments" label={t('openCalendar')} href={href} go={go} />}>
      <SummaryTable caption={t('todayBookings')} columns={cols} rows={jobs} rowKey={j => j.id} empty={t('nothingToday')} />
    </ChartCard>
  );
}

/** Kitchens pause and resume taking orders from here (the same call as the kitchen display's). */
function PauseButton({ merchantId, paused, t }: { merchantId: string; paused: boolean; t: DashboardT }) {
  const toggle = useKitchenToggle(merchantId);
  return (
    <Button variant="secondary" disabled={toggle.isPending} onClick={() => toggle.mutate(paused ? 'resume' : 'pause')}>
      {paused ? <Play size={16} aria-hidden /> : <Pause size={16} aria-hidden />}{t(paused ? 'resumeOrders' : 'pauseOrders')}
    </Button>
  );
}
