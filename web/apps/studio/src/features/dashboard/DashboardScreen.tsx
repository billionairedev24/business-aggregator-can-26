import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ErrorState, Kpi, KpiRow, LinkRow, Meter, PageSkeleton, StackedBarChart, useFormatters, useLocale, type Locale, timeZone } from '@northline/ui';
import { useMerchant, useMerchantId, type MerchantType } from '../shell/api';
import { screenHref, type ScreenKey } from '../shell/nav';
import { clock, clockWithPeriod } from '../../lib/time';
import { dashboardQuery, type Dashboard } from './api';
import { InsightCard } from '../assistant/InsightCard';
import { numberWord, useDashboardT } from './messages';
import './Dashboard.css';

type T = ReturnType<typeof useDashboardT>;
type Fmt = ReturnType<typeof useFormatters>;

/** Portal variant: provider-only, seller-only or both (kitchens have no dashboard). */
const variant = (type: MerchantType) => (type === 'seller' ? 'seller' : type === 'both' ? 'both' : 'provider');

export function DashboardScreen() {
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const q = useQuery(dashboardQuery(merchantId));
  const t = useDashboardT();
  if (q.isPending) return <PageSkeleton kpis={4} rows={6} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  return <>
    <DashboardView data={q.data} kind={variant(merchant.type)} city={merchant.city ?? ''} merchantId={merchantId} />
    <InsightCard merchantId={merchantId} screen="dashboard" />
  </>;
}

export function DashboardView({ data, kind, city, merchantId }: { data: Dashboard; kind: 'provider' | 'seller' | 'both'; city: string; merchantId: string }) {
  const t = useDashboardT();
  const f = useFormatters();
  const { locale } = useLocale();
  const nav = useNavigate();
  const go = (k: ScreenKey) => () => void nav({ to: screenHref(merchantId, k) });
  const prov = kind !== 'seller', sell = kind !== 'provider';
  const e = data.earnings;
  const qualityRows = (['on_time', 'photos', 'response', 'rebook', 'dispute_rate'] as const).filter(k => data.reputation.quality[k] !== undefined);

  return (
    <div className="nl-dash">
      <span className="nl-kicker">{t('kicker', { date: longDate(data.today, locale), city })}</span>
      <h1 className="nl-page-title nl-dash-title">{headline(data, kind, t, f, locale)}</h1>
      <KpiRow>
        <Kpi value={f.money(e.netThisMonthCents, { whole: true })} label={netLabel(e, data.today, t, locale)} />
        {kind === 'seller' ? <Kpi value={f.number(data.counts.ordersThisMonth)} label={t('ordersMonth', { items: data.counts.itemsThisMonth })} />
          : kind === 'provider' ? <Kpi value={f.number(data.counts.jobsThisMonth)} label={t('jobsMonth', { quotes: data.counts.quoteRequestsOpen })} />
          : <Kpi value={f.number(data.counts.jobsThisMonth + data.counts.ordersThisMonth)} label={t('bothMonth', { services: data.counts.jobsThisMonth, parts: data.counts.ordersThisMonth })} />}
        <Kpi value={data.reputation.rating != null ? f.number(data.reputation.rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) : '—'}
          label={data.reputation.rating == null ? t('noRating') : t(kind === 'seller' ? 'ratingOrders' : 'ratingReviews', { count: f.number(data.reputation.reviews) })} />
        {kind === 'seller'
          ? <Kpi value={data.reputation.refundRateBps != null ? f.number(data.reputation.refundRateBps / 10000, { style: 'percent', maximumFractionDigits: 1 }) : '—'} label={t('refundKpi')} />
          : <Kpi value={data.reputation.qualityScore ?? '—'} label={t('qualityKpi')} />}
      </KpiRow>

      <div className="nl-dash-cols">
        <section aria-labelledby="dash-today">
          <h2 id="dash-today" className="nl-h2">{kind === 'seller' ? (data.counts.runCutoff ? t('tonightRun', { time: clockWithPeriod(data.counts.runCutoff, locale) }) : t('nextRun')) : t('today')}</h2>
          {kind === 'seller' ? <RunList data={data} t={t} /> : <TodayList data={data} t={t} f={f} locale={locale} />}

          <h2 className="nl-h2 nl-dash-needs">{t('needsYou')}</h2>
          <div className="nl-dash-needs-list">
            {needsYou(data, { prov, sell, sellerOnly: kind === 'seller' }, t, f, locale, go)}
          </div>
        </section>

        <section aria-labelledby="dash-earn">
          <h2 id="dash-earn" className="nl-h2" style={{ marginBottom: 10 }}>{t('earningsTitle')}</h2>
          <StackedBarChart
            title={t('earningsTitle')}
            series={[{ key: 'services', label: t('services'), color: 'var(--color-accent)' }, { key: 'parts', label: t('parts'), color: 'var(--color-accent-2-400)' }]}
            data={e.weeks.map((w, i) => ({ label: `W${i + 1}`, values: { services: w.servicesCents, parts: w.partsCents } }))}
            format={c => f.money(c, { whole: true })}
          />
          <h2 className="nl-h2 nl-dash-quality">{t('quality')}{data.reputation.qualityScore != null ? <> · <span className="nl-dash-score">{data.reputation.qualityScore}</span></> : null}</h2>
          {qualityRows.length === 0 ? <p className="nl-muted nl-small">{t('qualityPending')}</p> : qualityRows.map(k => {
            const v = data.reputation.quality[k]!;
            const isDispute = k === 'dispute_rate';
            return <Meter key={k} label={t(`q_${k}`)} value={isDispute ? 100 - v * 10 : v} floor={FLOORS[k]} display={`${f.number(v, { maximumFractionDigits: 1 })}%`} />;
          })}
          <p className="nl-dash-coaching"><strong>{t('coaching')}</strong> {coaching(data, t, locale)}</p>
        </section>
      </div>
    </div>
  );
}

/** "Tuesday 8 September" (design) / « mardi 8 septembre ». */
function longDate(date: string, locale: Locale): string {
  const d = new Date(`${date}T12:00:00Z`);
  const p = (o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { ...o, timeZone: 'UTC' }).format(d);
  return `${p({ weekday: 'long' })} ${p({ day: 'numeric' })} ${p({ month: 'long' })}`;
}

/** Floors below which a metric shows in rosehip (design: bar(val, floor)). */
const FLOORS = { on_time: 95, photos: 90, response: 90, rebook: 40, dispute_rate: 90 } as const;

function headline(d: Dashboard, kind: 'provider' | 'seller' | 'both', t: T, f: Fmt, locale: Locale): string {
  const parts: string[] = [];
  const word = (n: number) => numberWord(n, locale, parts.length === 0);
  const c = d.counts;
  if (kind !== 'seller') parts.push(t('visits', { count: c.visitsToday, word: word(c.visitsToday) }));
  if (kind === 'provider') parts.push(t('quotes', { count: c.quoteRequestsOpen, word: word(c.quoteRequestsOpen) }));
  if (kind === 'seller') parts.push(c.runCutoff ? t('packBy', { count: c.toPack, word: word(c.toPack), time: clockWithPeriod(c.runCutoff, locale).replace(/ (am|pm)$/, '') }) : t('pack', { count: c.toPack, word: word(c.toPack) }));
  if (kind === 'both') parts.push(t('pack', { count: c.toPack, word: word(c.toPack) }));
  if (kind === 'seller') { const n = d.cases.filter(x => x.kind === 'refund').length; parts.push(t('cases', { count: n, word: word(n) })); }
  const e = d.earnings;
  parts.push(e.releasingCents != null && e.releasingAt
    ? t('releasing', { money: f.money(e.releasingCents, { whole: true }), day: new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'long', timeZone: timeZone() }).format(new Date(e.releasingAt)) })
    : t('nothingReleasing'));
  return `${parts.join(', ')}.`;
}

function netLabel(e: Dashboard['earnings'], today: string, t: T, locale: Locale): string {
  if (e.netLastMonthCents <= 0) return t('netMonth');
  const pct = Math.round(((e.netThisMonthCents - e.netLastMonthCents) / e.netLastMonthCents) * 100);
  const month = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(2000, Number(today.slice(5, 7)) - 2, 15))).replace('.', '');
  return t('netMonthVs', { pct: `${pct >= 0 ? '+' : '−'}${Math.abs(pct)}%`, month });
}

function TodayList({ data, t, f, locale }: { data: Dashboard; t: T; f: Fmt; locale: Locale }) {
  if (data.jobsToday.length === 0) return <p className="nl-muted">{t('nothingToday')}</p>;
  return (
    <ul className="nl-dash-list">
      {data.jobsToday.map(j => (
        <li key={j.id} className="nl-row">
          <span className="nl-dash-time">{clock(j.startsAt, locale)}</span>
          <span className="nl-dash-what"><strong>{j.title}</strong><span className="nl-dash-who">{[j.customerName, j.area, j.access, j.escrowHeldCents != null ? t('escrow', { money: f.money(j.escrowHeldCents) }) : null].filter(Boolean).join(' · ')}</span></span>
          {j.mine || !j.memberName
            ? <span className="tag tag-neutral nl-dash-tag">{t(`state_${j.state}` as Parameters<T>[0])}</span>
            : <span className="tag tag-accent nl-dash-tag">{j.memberName}</span>}
        </li>
      ))}
    </ul>
  );
}

function RunList({ data, t }: { data: Dashboard; t: T }) {
  if (data.run.length === 0) return <p className="nl-muted">{t('nothingToPack')}</p>;
  return (
    <ul className="nl-dash-list">
      {data.run.map(o => (
        <li key={o.orderId} className="nl-row">
          <span className="nl-dash-time">{o.runLabel ?? '—'}</span>
          <span className="nl-dash-what"><strong>{[o.items.map(i => (i.qty > 1 ? `${i.title} ×${i.qty}` : i.title)).join(', '), o.ref].filter(Boolean).join(' · ')}</strong><span className="nl-dash-who">{[o.customerName, o.area].filter(Boolean).join(' · ')}</span></span>
          <span className={`tag ${o.packed ? 'tag-neutral' : 'tag-accent'} nl-dash-tag`}>{o.packed ? t('packedTag') : t('toPackTag')}</span>
        </li>
      ))}
    </ul>
  );
}

function needsYou(d: Dashboard, p: { prov: boolean; sell: boolean; sellerOnly: boolean }, t: T, f: Fmt, locale: Locale, go: (k: ScreenKey) => () => void) {
  const rows: { key: string; text: string; onClick: () => void }[] = [];
  const c = d.counts;
  if (p.prov && c.quoteRequestsOpen > 0) {
    const left = c.quoteRespondBy ? Math.round((new Date(c.quoteRespondBy).getTime() - Date.now()) / 60000) : -1;
    rows.push({ key: 'quotes', onClick: go('appointments'), text: left > 0 ? t('quoteRequests', { count: c.quoteRequestsOpen, left: `${Math.floor(left / 60)} h ${left % 60} m` }) : t('quoteRequestsLate', { count: c.quoteRequestsOpen }) });
  }
  if (p.sell && c.toPack > 0) rows.push({ key: 'pack', onClick: go('orders'), text: c.runCutoff && c.runLabel ? t('ordersToPack', { count: c.toPack, run: c.runLabel, time: clockWithPeriod(c.runCutoff, locale) }) : t('ordersToPackNoRun', { count: c.toPack }) });
  const cases = d.cases.filter(x => (p.sellerOnly ? x.kind === 'refund' : true));
  const first = cases[0];
  if (first) rows.push({ key: 'case', onClick: go('refunds'), text: t(first.kind === 'refund' ? 'refundCase' : 'dispute', { count: cases.filter(x => x.kind === first.kind).length, who: first.customerName ?? '—', what: (first.subject ?? '').toLowerCase() }) });
  const low = d.lowStock[0];
  if (p.sell && low) rows.push({ key: 'stock', onClick: go('products'), text: t('lowStock', { name: low.name, stock: low.stock }) });
  const doc = d.compliance[0];
  if (doc) {
    const name = t(`doc_${doc.checkType}` as Parameters<T>[0]);
    const days = doc.pausesAt ? Math.ceil((new Date(doc.pausesAt).getTime() - Date.now()) / 86400000) : 0;
    rows.push({ key: 'doc', onClick: go('compliance'), text: doc.status === 'expired' && doc.expiresAt
      ? (days > 0 ? t('complianceExpired', { doc: name, date: f.date(doc.expiresAt), days }) : t('complianceExpiredPaused', { doc: name, date: f.date(doc.expiresAt) }))
      : t('complianceTodo', { doc: name }) });
  }
  if (rows.length === 0) return <p className="nl-muted nl-small">{t('allCaughtUp')}</p>;
  return rows.map(r => <LinkRow key={r.key} onClick={r.onClick}>{r.text}</LinkRow>);
}

function coaching(d: Dashboard, t: T, locale: Locale): string {
  const date = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${d.coaching.nextTierReview}T12:00:00Z`));
  if (d.coaching.jobs === 0) return t('coachingNone', { date });
  return d.coaching.withoutPhotos > 0 ? t('coachingPhotos', { missing: d.coaching.withoutPhotos, jobs: d.coaching.jobs, date }) : t('coachingGood', { jobs: d.coaching.jobs, date });
}
