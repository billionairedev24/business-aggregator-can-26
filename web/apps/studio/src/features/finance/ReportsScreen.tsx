import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { BarList, EmptyState, KpiStrip, LineChart, PageSkeleton, Segmented, useFormatters, useLocale } from '@northline/ui';
import { useMerchant, useMerchantId } from '../shell/api';
import { downloads, reportQuery, type Period, type Report } from './api';
import { pct, pctFromBps, signedPct } from './format';
import { useFinanceT, type FinanceKey } from './messages';
import { QueryState } from './QueryState';
import './finance.css';

const TITLE: Record<Period, FinanceKey> = { '30d': 'last30d', '90d': 'last90d', '12mo': 'last12mo' };
const GROSS: Record<Report['granularity'], FinanceKey> = { day: 'grossDay', week: 'grossWeek', month: 'grossMonth' };

/** Design 02 · Sales reports: 30 d / 90 d / 12 mo, KPIs, gross vs previous period, by listing, sources, CSV + GST. */
export function ReportsScreen() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const { locale } = useLocale();
  const [period, setPeriod] = useState<Period>('90d');
  const report = useQuery({ ...reportQuery(merchantId, period), placeholderData: keepPreviousData });
  const year = new Date().getFullYear();
  return (
    <div className="fin-page">
      <div className="fin-head">
        <div>
          <span className="nl-kicker">{t('reportsKicker')}</span>
          <h1 className="nl-page-title" style={{ margin: 0 }}>{t(TITLE[period])}</h1>
        </div>
        <div className="fin-head-actions">
          <Segmented name="report-period" aria-label={t('periodLabel')} value={period} onChange={setPeriod}
            options={[{ value: '30d', label: t('p30d') }, { value: '90d', label: t('p90d') }, { value: '12mo', label: t('p12mo') }]} />
          <a className="btn btn-secondary" href={downloads.export(merchantId, period)} download>{t('exportCsv')}</a>
          <a className="btn btn-secondary" href={downloads.gstPdf(merchantId, year, locale)} download>{t('taxSummary')}</a>
        </div>
      </div>
      <QueryState query={report} skeleton={<PageSkeleton kpis={5} rows={4} />}>
        {r => <ReportBody r={r} />}
      </QueryState>
    </div>
  );
}

function ReportBody({ r }: { r: Report }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const type = useMerchant()?.type ?? 'provider';
  const labels = r.series.map(p => r.granularity === 'month'
    ? new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', timeZone: 'UTC' }).format(new Date(`${p.start}T12:00:00Z`))
    : new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${p.start}T12:00:00Z`)));
  const countKey: FinanceKey = type === 'seller' ? 'kpiCount_seller' : type === 'kitchen' ? 'kpiCount_kitchen' : 'kpiCount';
  if (r.count === 0) return <EmptyState>{t('reportEmpty')}</EmptyState>;
  return (
    <>
      <KpiStrip label={t(TITLE[r.period])} items={[
        { key: 'gross', value: f.money(r.grossCents, { whole: true }), label: t('kpiGross'), delta: r.grossChangePct == null ? undefined : t('kpiVsPrevious', { change: signedPct(r.grossChangePct, locale) }), trend: r.grossChangePct == null ? undefined : r.grossChangePct > 0 ? 'up' : r.grossChangePct < 0 ? 'down' : 'flat' },
        { key: 'count', value: f.number(r.count), label: t(countKey) },
        { key: 'avg', value: f.money(r.averageTicketCents), label: t('kpiAverage') },
        { key: 'repeat', value: r.repeatCustomerPct == null ? '—' : pct(r.repeatCustomerPct, locale), label: t('kpiRepeat') },
        { key: 'refund', value: pctFromBps(r.refundRateBps, locale), label: t('kpiRefund'), delta: r.benchmarkRefundRateBps == null ? undefined : t('kpiCategoryAvg', { avg: pctFromBps(r.benchmarkRefundRateBps, locale) }) },
      ]} />
      <div className="fin-report">
        <section className="bo-card" aria-labelledby="fin-gross">
          <div><h2 id="fin-gross" className="bo-card-title">{t(GROSS[r.granularity])}</h2>
            <div className="bo-muted">{t('chartLegend')}</div></div>
          <LineChart title={t(GROSS[r.granularity])} current={r.series.map(p => p.currentCents / 100)} previous={r.series.map(p => p.previousCents / 100)} labels={labels} format={v => f.money(v * 100, { whole: true })} />
        </section>
        <div className="fin-report-side">
          <section className="bo-card" aria-labelledby="fin-by-listing">
            <h2 className="bo-card-title" id="fin-by-listing">{t('byListing')}</h2>
            <BarList items={r.byListing.map(l => ({ label: l.name ?? t('everythingElse'), value: l.grossCents }))} format={c => f.money(c, { whole: true })} />
          </section>
          <section className="bo-card" aria-labelledby="fin-sources">
            <h2 className="bo-card-title" id="fin-sources">{t('sourcesTitle')}</h2>
            <div className="fin-sources">
              {r.sources.map(s => <div key={s.source}><span>{t(`src_${s.source}`)}</span><strong>{pct(s.pct, locale)}</strong></div>)}
            </div>
          </section>
        </div>
      </div>
    </>
  );
}

