import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { BarList, BoHeader, ChartCard, ColumnChart, EmptyState, KpiStrip, PageSkeleton, Segmented, useFormatters, useLocale, type ColumnPoint, type Locale } from '@northline/ui';
import { useMerchant, useMerchantId } from '../shell/api';
import { downloads, reportQuery, type Period, type Report } from './api';
import { axisDollars, pct, pctFromBps, signedPct } from './format';
import { useFinanceT, type FinanceKey } from './messages';
import { QueryState } from './QueryState';
import './finance.css';

const TITLE: Record<Period, FinanceKey> = { '30d': 'last30d', '90d': 'last90d', '12mo': 'last12mo' };
const GROSS: Record<Report['granularity'], FinanceKey> = { day: 'grossDay', week: 'grossWeek', month: 'grossMonth' };
const NOW: Record<Report['granularity'], FinanceKey> = { day: 'chartToday', week: 'chartThisWeek', month: 'chartThisMonth' };
const PAST: Record<Report['granularity'], FinanceKey> = { day: 'chartPastDays', week: 'chartPastWeeks', month: 'chartPastMonths' };

/** The report's buckets as columns: "Oct 12" (days, weeks) or "Oct" (months) under each, the full name in the tooltip. */
export function reportColumns(r: Report, locale: Locale): ColumnPoint[] {
  const fmt = (iso: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: 'UTC', ...o }).format(new Date(`${iso.slice(0, 10)}T12:00:00Z`));
  return r.series.map(p => r.granularity === 'month'
    ? { label: fmt(p.start, { month: 'short' }), name: fmt(p.start, { month: 'long', year: 'numeric' }), value: p.currentCents }
    : { label: fmt(p.start, { month: 'short', day: 'numeric' }), name: fmt(p.start, { weekday: 'long', month: 'long', day: 'numeric' }), value: p.currentCents });
}

/** Sales reports (S-176): 30 d / 90 d / 12 mo, the KPI strip, gross as columns, by listing, sources, CSV + GST. */
export function ReportsScreen() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const [period, setPeriod] = useState<Period>('90d');
  const report = useQuery({ ...reportQuery(merchantId, period), placeholderData: keepPreviousData });
  const year = new Date().getFullYear();
  const r = report.data;
  return (
    <div className="fin-page">
      <BoHeader title={t('reportsKicker')}
        sub={r && r.period === period ? t('reportSub', { period: t(TITLE[period]), range: f.dateRange(r.from, r.to, 'full') }) : t(TITLE[period])}
        actions={<>
          <Segmented name="report-period" size="sm" aria-label={t('periodLabel')} value={period} onChange={setPeriod}
            options={[{ value: '30d', label: t('p30d') }, { value: '90d', label: t('p90d') }, { value: '12mo', label: t('p12mo') }]} />
          <a className="btn btn-secondary" href={downloads.export(merchantId, period)} download>{t('exportCsv')}</a>
          <a className="btn btn-secondary" href={downloads.gstPdf(merchantId, year, locale)} download>{t('taxSummary')}</a>
        </>} />
      <QueryState query={report} skeleton={<PageSkeleton kpis={5} rows={4} />}>
        {data => <ReportBody r={data} />}
      </QueryState>
    </div>
  );
}

function ReportBody({ r }: { r: Report }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const type = useMerchant()?.type ?? 'provider';
  const countKey: FinanceKey = type === 'seller' ? 'kpiCount_seller' : type === 'kitchen' ? 'kpiCount_kitchen' : 'kpiCount';
  if (r.count === 0) return <EmptyState>{t('reportEmpty')}</EmptyState>;
  const points = reportColumns(r, locale);
  const past = points.slice(0, -1);
  const avg = past.length ? Math.round(past.reduce((n, p) => n + p.value, 0) / past.length) : 0;
  // a refund rate under the category's average is good (green), over it bad (red)
  const refundTrend = r.benchmarkRefundRateBps == null ? undefined : r.refundRateBps <= r.benchmarkRefundRateBps ? 'up' as const : 'down' as const;
  return (
    <>
      <KpiStrip label={t(TITLE[r.period])} items={[
        { key: 'gross', value: f.price(r.grossCents), label: t('kpiGross'), arrow: true, delta: r.grossChangePct == null ? undefined : t('kpiVsPrevious', { change: signedPct(r.grossChangePct, locale) }), trend: r.grossChangePct == null ? undefined : r.grossChangePct > 0 ? 'up' : r.grossChangePct < 0 ? 'down' : 'flat' },
        { key: 'count', value: f.number(r.count), label: t(countKey) },
        { key: 'avg', value: f.price(r.averageTicketCents), label: t('kpiAverage') },
        { key: 'repeat', value: r.repeatCustomerPct == null ? '—' : pct(r.repeatCustomerPct, locale), label: t('kpiRepeat') },
        { key: 'refund', value: pctFromBps(r.refundRateBps, locale), label: t('kpiRefund'), trend: refundTrend, delta: r.benchmarkRefundRateBps == null ? undefined : t('kpiCategoryAvg', { avg: pctFromBps(r.benchmarkRefundRateBps, locale) }) },
      ]} />
      <div className="fin-report">
        <ChartCard id="fin-gross" title={t(GROSS[r.granularity])}>
          <ColumnChart title={t(GROSS[r.granularity])} data={points} highlightLast format={c => f.price(c)} axisFormat={c => axisDollars(c, locale)}
            legend={{ current: t(NOW[r.granularity], { amount: f.price(points[points.length - 1]?.value ?? 0) }), past: t(PAST[r.granularity], { count: past.length, amount: f.price(avg) }) }} />
        </ChartCard>
        <div className="fin-report-side">
          <ChartCard id="fin-by-listing" title={t('byListing')}>
            <BarList items={r.byListing.map(l => ({ label: l.name ?? t('everythingElse'), value: l.grossCents }))} format={c => f.price(c)} />
          </ChartCard>
          <ChartCard id="fin-sources" title={t('sourcesTitle')}>
            <dl className="fin-sources">
              {r.sources.map(s => <div key={s.source}><dt>{t(`src_${s.source}`)}</dt><dd>{pct(s.pct, locale)}</dd></div>)}
            </dl>
          </ChartCard>
        </div>
      </div>
    </>
  );
}
