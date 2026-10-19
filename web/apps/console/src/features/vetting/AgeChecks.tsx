import { useQuery } from '@tanstack/react-query';
import { ChartCard, ErrorState, formatDate, formatNumber, KpiStrip, PageSkeleton, useLocale } from '@northline/ui';
import { reportQuery, rulesQuery } from './ageApi';
import { useAgeVetT, type AgeVetKey } from './ageMessages';

const sum = (m: Record<string, number>, ...keys: string[]) => keys.reduce((n, k) => n + (m[k] ?? 0), 0);

/**
 * Console › Listing vetting › Age checks (2026-10-04): trust & safety's report — customers' ID checks, handoffs
 * after an ID check and refusals by reason, the latest refusals, and the minimum ages the region model holds.
 */
export function AgeChecks({ province }: { province?: string }) {
  const t = useAgeVetT();
  const { locale } = useLocale();
  const q = useQuery(reportQuery(province));
  const rules = useQuery(rulesQuery);
  if (q.isPending) return <PageSkeleton kpis={4} rows={4} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const r = q.data;
  const reasonText = (code: string) => { const k = `r_${code}` as AgeVetKey; const s = t(k); return s === k ? t('r_other') : s; };
  return (
    <div className="bo-stack">
      <div className="nl-q-intro">
        <h2 className="nl-q-h2">{t('ageTitle')}</h2>
        <p className="nl-q-lede">{t('ageLede')}</p>
      </div>
      <KpiStrip label={t('ageTitle')} items={[
        { key: 'verified', label: t('k_verified'), value: formatNumber(sum(r.verifications, 'verified'), locale) },
        { key: 'failed', label: t('k_failed'), value: formatNumber(sum(r.verifications, 'failed'), locale) },
        { key: 'passed', label: t('k_passed'), value: formatNumber(sum(r.handoffs, 'passed'), locale) },
        { key: 'refused', label: t('k_refused'), value: formatNumber(sum(r.handoffs, 'refused'), locale) },
      ]} />
      <div className="nl-cols-wrap"><div className="nl-cols" data-split="aside">
        <ChartCard id="age-refusals" title={t('refusalsTitle')}>
          {Object.keys(r.refusals).length === 0 ? <p className="bo-muted">{t('none')}</p> : (
            <ul className="nl-rows">{Object.entries(r.refusals).map(([k, n]) => <li key={k}><span>{reasonText(k)}</span><strong>{formatNumber(n, locale)}</strong></li>)}</ul>
          )}
        </ChartCard>
        <ChartCard id="age-recent" title={t('recentTitle')}>
          {r.recent.length === 0 ? <p className="bo-muted">{t('none')}</p> : (
            <div className="nl-scroll">
              <table className="table">
                <thead><tr><th scope="col">{t('colOrder')}</th><th scope="col">{t('colWhere')}</th><th scope="col">{t('colAge')}</th><th scope="col">{t('colReason')}</th><th scope="col">{t('colWhen')}</th></tr></thead>
                <tbody>{r.recent.map(x => (
                  <tr key={x.checkId}><td>{x.orderId}</td><td>{x.place === 'door' ? t('door') : t('counter')}{x.province ? ` · ${x.province}` : ''}</td><td>{x.requiredAge}+</td><td>{reasonText(x.reason)}</td><td>{formatDate(x.at, locale, 'dateTime')}</td></tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </ChartCard>
      </div></div>
      <ChartCard id="age-rules" title={t('rulesTitle')}>
        <p className="bo-muted">{t('rulesLede')}</p>
        {rules.data ? (
          <div className="nl-scroll">
            <table className="table">
              <thead><tr><th scope="col">{t('colRule')}</th><th scope="col">{t('colMin')}</th><th scope="col">{t('colHours')}</th><th scope="col">{t('colSource')}</th></tr></thead>
              <tbody>{rules.data.items.map(x => (
                <tr key={`${x.province}-${x.ageClass}`}>
                  <td>{x.province} · {t(`cls_${x.ageClass}` as AgeVetKey)}</td><td>{x.minimumAge}</td>
                  <td>{x.deliveryFrom && x.deliveryUntil ? `${x.deliveryFrom.slice(0, 5)}–${x.deliveryUntil.slice(0, 5)}` : t('anyTime')}</td>
                  <td>{x.source} · {x.confirmed ? t('confirmed') : t('toConfirm')}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        ) : null}
      </ChartCard>
    </div>
  );
}
