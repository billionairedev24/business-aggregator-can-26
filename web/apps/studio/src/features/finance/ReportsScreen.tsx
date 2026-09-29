import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { BarList, EmptyState, LineChart, PageSkeleton, Segmented, useFormatters, useLocale } from '@northline/ui';
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
  const [period, setPeriod] = useState<Period>('90d');
  const report = useQuery({ ...reportQuery(merchantId, period), placeholderData: keepPreviousData });
  const year = new Date().getFullYear();
  return (
    <>
      <div className="fin-head">
        <div>
          <span className="nl-kicker">{t('reportsKicker')}</span>
          <h1 className="nl-page-title" style={{ margin: 0 }}>{t(TITLE[period])}</h1>
        </div>
        <div className="fin-head-actions">
          <Segmented name="report-period" aria-label={t('periodLabel')} value={period} onChange={setPeriod}
            options={[{ value: '30d', label: t('p30d') }, { value: '90d', label: t('p90d') }, { value: '12mo', label: t('p12mo') }]} />
          <a className="btn btn-secondary" href={downloads.export(merchantId, period)} download>{t('exportCsv')}</a>
          <a className="btn btn-secondary" href={downloads.gst(merchantId, year)} download>{t('taxSummary')}</a>
        </div>
      </div>
      <QueryState query={report} skeleton={<PageSkeleton kpis={5} rows={4} />}>
        {r => <ReportBody r={r} />}
      </QueryState>
    </>
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
      <div className="fin-kpis">
        <Kpi value={f.money(r.grossCents, { whole: true })} label={r.grossChangePct == null ? t('kpiGross') : t('kpiGrossChange', { change: signedPct(r.grossChangePct, locale) })} />
        <Kpi value={f.number(r.count)} label={t(countKey)} />
        <Kpi value={f.money(r.averageTicketCents)} label={t('kpiAverage')} />
        <Kpi value={r.repeatCustomerPct == null ? '—' : pct(r.repeatCustomerPct, locale)} label={t('kpiRepeat')} />
        <Kpi value={pctFromBps(r.refundRateBps, locale)} label={r.benchmarkRefundRateBps == null ? t('kpiRefund') : t('kpiRefundBenchmark', { avg: pctFromBps(r.benchmarkRefundRateBps, locale) })} />
      </div>
      <div className="fin-cols">
        <section aria-labelledby="fin-gross">
          <h2 id="fin-gross" className="fin-h2" style={{ marginBottom: 4 }}>{t(GROSS[r.granularity])}</h2>
          <div className="fin-muted" style={{ marginBottom: 10 }}>{t('chartLegend')}</div>
          <LineChart title={t(GROSS[r.granularity])} current={r.series.map(p => p.currentCents / 100)} previous={r.series.map(p => p.previousCents / 100)} labels={labels} />
        </section>
        <section>
          <h2 className="fin-h2">{t('byListing')}</h2>
          <BarList items={r.byListing.map(l => ({ label: l.name ?? t('everythingElse'), value: l.grossCents }))} format={c => f.money(c, { whole: true })} />
          <h2 className="fin-h2" style={{ marginTop: 28 }}>{t('sourcesTitle')}</h2>
          <div className="fin-sources">
            {r.sources.map(s => <div key={s.source}><span>{t(`src_${s.source}`)}</span><strong>{pct(s.pct, locale)}</strong></div>)}
          </div>
        </section>
      </div>
    </>
  );
}

const Kpi = ({ value, label }: { value: string; label: string }) => <div><div className="fin-kpi-value">{value}</div><div className="fin-kpi-label">{label}</div></div>;
