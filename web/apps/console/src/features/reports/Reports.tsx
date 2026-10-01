import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { BarList, ErrorState, formatNumber, LineChart, PageSkeleton, Segmented, useFormatters, useLocale, type Locale } from '@northline/ui';
import { useRegions } from '../shell/api';
import { useRegionName } from '../shell/PlaceFilters';
import { reportQuery, type Report } from './api';
import { useReportsT, type ReportsKey, type ReportsT } from './messages';
import './reports.css';

const monthLabel = (month: string, locale: Locale) =>
  new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', timeZone: 'UTC' }).format(new Date(`${month}-15T12:00:00Z`));
const dayLabel = (day: string, locale: Locale) =>
  new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${day}T12:00:00Z`));

/** A count, or why there's none: "—" for zero-free gaps, "fewer than 5" when withheld, "not recorded". */
function count(n: number | null | undefined, locale: Locale, t: ReportsT, recorded = true) {
  if (!recorded) return t('notRecorded');
  return n == null ? t('withheld') : formatNumber(n, locale);
}

/**
 * Reports & analytics (S-95, design 03 `reports`; admin, finance, analyst — read only): weekly active customers against
 * the previous period, the shop funnel, signup-month cohorts' repeat rate, top categories by sales and waitlist demand,
 * for every province or one. The api sends counts only and withholds counts under 5.
 */
export function Reports() {
  const t = useReportsT();
  const { province } = useSearch({ strict: false }) as { province?: string };
  const query = useQuery(reportQuery(province));
  if (query.isPending) return <PageSkeleton kpis={0} rows={8} />;
  if (query.isError) return <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />;
  return <ReportView data={query.data} province={province} />;
}

function ReportView({ data, province }: { data: Report; province?: string }) {
  const t = useReportsT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const navigate = useNavigate();
  const regionName = useRegionName();
  const provinces = (useRegions(locale).data?.provinces ?? []).filter(p => p.status === 'live' || p.status === 'pilot');
  const options = [...provinces.map(p => ({ value: p.code, label: p.name })), { value: '', label: t('all') }];
  const firstCount = data.funnel.find(f => f.recorded && f.count != null)?.count ?? 0;
  return (
    <div>
      <div className="nl-rp-head">
        <div>
          <span className="nl-rp-kicker">{t('kicker')}</span>
          <h1 className="nl-rp-title">{t('title')}</h1>
        </div>
        <Segmented name="report-region" aria-label={t('region')} options={options} value={province ?? ''}
          onChange={v => void navigate({ to: '/reports', search: { province: v || undefined } as never })} />
      </div>
      <div className="nl-rp-cols">
        <div>
          <h2 className="nl-rp-h2">{t('wacTitle')}</h2>
          <div className="nl-rp-sub">{t('wacSub')}</div>
          <LineChart title={t('wacTitle')} current={data.weeks.map(w => w.customers ?? 0)} previous={data.weeks.map(w => w.previous ?? 0)}
            labels={data.weeks.map(w => dayLabel(w.week, locale))} legend={{ current: t('thisPeriod'), previous: t('previous') }} />
          <details className="nl-rp-table">
            <summary>{t('asTable')}</summary>
            <table className="table">
              <thead><tr><th>{t('week')}</th><th>{t('thisPeriod')}</th><th>{t('previous')}</th></tr></thead>
              <tbody>{data.weeks.map(w => <tr key={w.week}><td>{dayLabel(w.week, locale)}</td><td>{count(w.customers, locale, t)}</td><td>{count(w.previous, locale, t)}</td></tr>)}</tbody>
            </table>
          </details>
          <h2 className="nl-rp-h2 nl-rp-gap">{t('funnelTitle')}</h2>
          {data.funnel.map(f => (
            <div key={f.step} className="nl-meter nl-rp-step">
              <span className="nl-meter-label">{t(`step_${f.step}` as ReportsKey)}</span>
              <div className="nl-meter-track"><div className="nl-meter-fill" style={{ width: `${firstCount && f.count != null ? Math.min(100, (f.count / firstCount) * 100) : 0}%` }} /></div>
              <span className="nl-meter-value">{count(f.count, locale, t, f.recorded)}</span>
            </div>
          ))}
          <p className="nl-rp-sub">{t('funnelNote')}</p>
        </div>
        <div>
          <h2 className="nl-rp-h2">{t('cohortsTitle')}</h2>
          <table className="table nl-rp-cohorts">
            <thead><tr><th>{t('c_month')}</th><th>{t('c_customers')}</th><th>{t('c_m1')}</th><th>{t('c_m2')}</th><th>{t('c_m3')}</th></tr></thead>
            <tbody>
              {data.cohorts.map(c => (
                <tr key={c.month}>
                  <td>{monthLabel(c.month, locale)}</td>
                  <td>{c.customers == null ? t('withheld') : formatNumber(c.customers, locale)}</td>
                  {[c.m1, c.m2, c.m3].map((r, i) => (
                    <td key={i}>{r == null ? <span className="nl-rp-empty">{t('none')}</span>
                      : <span className="nl-rp-cell" style={{ ['--rate' as string]: `${Math.round(r / 2)}%` }}>{`${formatNumber(r, locale, { maximumFractionDigits: 0 })}%`}</span>}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <h2 className="nl-rp-h2 nl-rp-gap">{t('topTitle')}</h2>
          {data.topCategories.length ? (
            <BarList items={data.topCategories.map(c => ({ label: c.names[locale] ?? c.names.en ?? c.categoryId, value: c.salesCents }))}
              format={v => fmt.money(v, { whole: true })} />
          ) : <p className="nl-rp-sub">{t('noTop')}</p>}
          <h2 className="nl-rp-h2 nl-rp-gap">{t('gapsTitle')}</h2>
          {data.waitlist.length ? (
            <div className="nl-rp-gaps">
              {data.waitlist.map(w => <div key={w.province}><span>{t('waitlist', { province: regionName(w.province) })}</span><strong>{count(w.people, locale, t)}</strong></div>)}
            </div>
          ) : <p className="nl-rp-sub">{t('noGaps')}</p>}
        </div>
      </div>
      <p className="nl-rp-sub nl-rp-gap">{t('privacyNote')}</p>
    </div>
  );
}
